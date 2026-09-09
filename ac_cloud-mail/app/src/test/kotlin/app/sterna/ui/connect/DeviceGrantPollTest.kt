package app.sterna.ui.connect

import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-grant wait, EXECUTED — the first test in this repo that runs it at all.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceGrantPollTest {

    private val tokens = OAuthTokens(accessToken = "granted", refreshToken = "later")

    /** What `OAuthClient` answers at its `catch (_: IOException)` — the exchange reached nobody. */
    private val unreachable = DeviceTokenResult.Failed("network_error")

    /**
     * Replays [script] in order, then [thenForever] for as long as it is asked — and records the
     */
    private class ScriptedPoll(
        private val script: List<DeviceTokenResult>,
        private val thenForever: DeviceTokenResult? = null,
        private val overrunMeans: String = "",
    ) {
        val askedAt = mutableListOf<Long>()
        val calls: Int get() = askedAt.size

        fun answer(at: Long): DeviceTokenResult {
            val i = askedAt.size
            askedAt += at
            return script.getOrNull(i) ?: thenForever ?: error(
                "the poll was called ${i + 1} times, but this scenario scripted only ${script.size} " +
                    "results: the wait went on past the point it was supposed to end. $overrunMeans",
            )
        }
    }

    /** The reporter's case: the connection comes and goes, the approval still lands. */
    @Test fun `polls that reached no server are retried, and the approval still arrives`() = runTest {
        val bench = ScriptedPoll(listOf(unreachable, unreachable, unreachable, DeviceTokenResult.Success(tokens)))

        val verdict = pollDeviceGrant(
            intervalSeconds = 5, expiresInSeconds = 1800, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ #55: three polls that reached NO SERVER are not a refusal, and the device code is " +
                "still valid. Ending the sign-in there is what left the reporter on \"Couldn't " +
                "reach the server while waiting for approval.\" seconds after the code appeared.",
            DevicePollVerdict.Approved(tokens), verdict,
        )
        assertEquals(
            "and it must keep asking across all three: anything less means the wait gave up " +
                "somewhere in the middle and only looks right.",
            4, bench.calls,
        )
    }

    /** The other half: a server that answered, and said no, is still the end of it. */
    @Test fun `a refusal the server actually spoke ends the wait at once`() = runTest {
        val denied = DeviceTokenResult.Failed("access_denied", "the user said no")
        val bench = ScriptedPoll(listOf(DeviceTokenResult.Pending, denied))

        val verdict = pollDeviceGrant(
            intervalSeconds = 5, expiresInSeconds = 1800, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ access_denied is a SERVER'S ANSWER, not a transport failure: retrying it would sit " +
                "on the approval screen for half an hour after the user pressed Deny, and would " +
                "hide the one sentence that explains why.",
            DevicePollVerdict.Refused(denied), verdict,
        )
        assertEquals(
            "and it stops on the spot: a second poll after a real refusal is the widened guard.",
            2, bench.calls,
        )
    }

    /** Half an hour of reaching nothing: the deadline must say WHICH end was quiet. */
    @Test fun `a wait that never reached a server runs out saying so`() = runTest {
        val bench = ScriptedPoll(emptyList(), thenForever = unreachable)

        val verdict = pollDeviceGrant(
            intervalSeconds = 5, expiresInSeconds = 30, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ Nothing was ever reached, so \"The sign-in request expired. Try again.\" would be a " +
                "lie sending the reader back to a server she never spoke to. The deadline has to " +
                "carry everReachedAServer = false so the screen can say the connection is the " +
                "problem.",
            DevicePollVerdict.RanOut(everReachedAServer = false), verdict,
        )
        assertEquals("6 polls fit in 30 s at 5 s apart, delay first.", 6, bench.calls)
    }

    /** And the reverse: polls that WERE answered, and simply were never approved. */
    @Test fun `a wait the server kept answering runs out as an expiry`() = runTest {
        val bench = ScriptedPoll(emptyList(), thenForever = DeviceTokenResult.Pending)

        val verdict = pollDeviceGrant(
            intervalSeconds = 5, expiresInSeconds = 30, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ Every poll was answered by the server; the user just never approved. Telling her " +
                "to check her connection here points at the one thing that was working.",
            DevicePollVerdict.RanOut(everReachedAServer = true), verdict,
        )
        assertEquals("6 polls fit in 30 s at 5 s apart, delay first.", 6, bench.calls)
    }

    /**
     * And the third way a server talks: `slow_down` is an ANSWER, so a wait made of nothing else
     */
    @Test fun `a wait answered only by slow down runs out as an expiry`() = runTest {
        val bench = ScriptedPoll(emptyList(), thenForever = DeviceTokenResult.SlowDown)

        val verdict = pollDeviceGrant(
            intervalSeconds = 5, expiresInSeconds = 30, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ slow_down is a server TALKING: every poll of this wait was answered, so the " +
                "deadline is an expiry and not an unreachable server.",
            DevicePollVerdict.RanOut(everReachedAServer = true), verdict,
        )
        assertEquals(
            "and the gap widens by 5 s each time it is asked to: 5 s, then 10, then 15.",
            listOf(5_000L, 15_000L, 30_000L), bench.askedAt,
        )
    }

    /**
     * The cadence, and the reason the `delay` sits at the HEAD of the loop. Moved to the foot it
     */
    @Test fun `each poll waits its full interval before going out`() = runTest {
        val bench = ScriptedPoll(
            listOf(
                DeviceTokenResult.Pending, DeviceTokenResult.Pending, DeviceTokenResult.Pending,
                DeviceTokenResult.Success(tokens),
            ),
        )

        pollDeviceGrant(
            intervalSeconds = 5, expiresInSeconds = 1800, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ The nth poll must go out at exactly n × 5 s. A 0 in this list is the delay moved to " +
                "the foot of the loop, i.e. a retry burst against a server that is already down.",
            listOf(5_000L, 10_000L, 15_000L, 20_000L), bench.askedAt,
        )
    }

    /**
     * A server may legally answer `"interval": 0` — the field is optional in RFC 8628 § 3.2 and a
     */
    @Test fun `an interval of zero is still paced at one second`() = runTest {
        val bench = ScriptedPoll(
            script = listOf(DeviceTokenResult.Pending, DeviceTokenResult.Pending, DeviceTokenResult.Pending),
            overrunMeans = "⛔ With interval 0 and no floor, the virtual clock never moves and the " +
                "deadline is never reached: that is the retry burst, spelled out.",
        )

        val verdict = pollDeviceGrant(
            intervalSeconds = 0, expiresInSeconds = 3, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ A zero interval must still put one second between polls: 3 polls in a 3 s window, " +
                "at 1 s, 2 s and 3 s. Anything tighter is a client hammering a token endpoint.",
            listOf(1_000L, 2_000L, 3_000L), bench.askedAt,
        )
        assertEquals(
            "and the deadline is still reached, on polls that were all answered.",
            DevicePollVerdict.RanOut(everReachedAServer = true), verdict,
        )
    }

    /** `slow_down` is the server asking for room, and RFC 8628 § 3.5 prices it at +5 s. */
    @Test fun `slow down stretches the interval by five seconds`() = runTest {
        val bench = ScriptedPoll(
            listOf(DeviceTokenResult.SlowDown, DeviceTokenResult.Pending, DeviceTokenResult.Success(tokens)),
        )

        pollDeviceGrant(
            intervalSeconds = 5, expiresInSeconds = 1800, now = { currentTime },
        ) { bench.answer(currentTime) }

        assertEquals(
            "⛔ After slow_down the gap must be 10 s, not 5: ignoring it is how a client gets its " +
                "polls refused outright. Measured on the clock, not on the variable.",
            listOf(5_000L, 15_000L, 25_000L), bench.askedAt,
        )
    }

    /**
     * The risk this fix is closest to. Now that an unreachable poll keeps the loop going, a
     */
    @Test fun `cancellation leaves the wait instead of becoming a verdict`() = runTest {
        val outcome = runCatching {
            pollDeviceGrant(
                intervalSeconds = 5, expiresInSeconds = 1800, now = { currentTime },
            ) { throw CancellationException("the reader left the connect screen") }
        }

        assertNull(
            "⛔ Cancellation must not come back as a DevicePollVerdict: swallowed into a verdict it " +
                "becomes an error message on a screen nobody is looking at, and the wait it was " +
                "meant to stop has already been reported as something else.",
            outcome.getOrNull(),
        )
        assertTrue(
            "⛔ CancellationException must rise out of pollDeviceGrant untouched — the function " +
                "catches nothing on purpose. Caught and retried, Cancel becomes an immortal " +
                "30-minute poll. Got: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is CancellationException,
        )
    }
}
