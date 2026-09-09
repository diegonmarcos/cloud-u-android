package app.sterna.core.data.mail

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * The deadline arithmetic every IMAP call in the app goes through (Codeberg #99).
 */
class ImapBudgetTest {

    private val start = 1_000_000L

    @Test fun `no budget means no deadline and a blocking socket`() {
        // The 21 call sites that ask for no budget must keep the behaviour they have always had:
        // 0 is what a socket reads as "wait as long as it takes".
        val deadline = ImapBudget.deadline(ImapBudget.NO_BUDGET, start)

        assertEquals(0L, deadline)
        assertEquals(0, ImapBudget.remaining(deadline, start))
        assertEquals(0, ImapBudget.remaining(deadline, start + 10 * 60_000))
    }

    @Test fun `what the connect spends is not given back to the reads`() {
        val deadline = ImapBudget.deadline(15_000, start)

        // The bound handed to the connect...
        assertEquals(15_000, ImapBudget.remaining(deadline, start))
        // ...and the one handed to the reads AFTER a connect that took 6 s. Recomputing is the
        // whole point: reading it once would have given the reads a fresh 15 s on top.
        assertEquals(9_000, ImapBudget.remaining(deadline, start + 6_000))
    }

    @Test fun `a spent budget refuses to connect or read one last time`() {
        val deadline = ImapBudget.deadline(15_000, start)

        // This is the fence that stops the reconnect-once retry doubling the wait: the second
        // attempt asks for its bound and is told the call is over.
        val thrown = runCatching { ImapBudget.remaining(deadline, start + 15_001) }.exceptionOrNull()

        assertTrue("expected a timeout, got $thrown", thrown is SocketTimeoutException)
        // NOT a cancellation: the snapshot falls back to the cache on a timeout, but must let a
        // real Undo through. The two must never arrive as the same type.
        assertFalse(thrown is CancellationException)
    }

    @Test fun `the exact instant of expiry is already too late`() {
        val deadline = ImapBudget.deadline(15_000, start)

        // 0 would mean "block forever" to a socket, so the boundary must throw, not return 0.
        assertTrue(runCatching { ImapBudget.remaining(deadline, deadline) }.exceptionOrNull() is SocketTimeoutException)
        assertEquals(1, ImapBudget.remaining(deadline, deadline - 1))
    }

    @Test fun `an ambient budget serves the callers that named none, and overrules nobody`() {
        // The rule behind [ImapReadBudget] (#95): `appendDraft` passes no budget of its own — it
        // cannot, it is the same function the interactive save uses — so the deferred upload
        // installs one on the COROUTINE, and this is where the two meet.

        // A caller that named a budget keeps EXACTLY what it named, shorter or longer than the
        // ambient one. `min` would compile here and would quietly shorten `ENUMERATE_BUDGET_MS`.
        assertEquals(10_000, ImapBudget.effective(10_000, 60_000))
        assertEquals(90_000, ImapBudget.effective(90_000, 60_000))
        assertEquals(15_000, ImapBudget.effective(15_000, ImapBudget.NO_BUDGET))

        // The case the whole element exists for: nothing at the call site, a budget in the context.
        assertEquals(60_000, ImapBudget.effective(ImapBudget.NO_BUDGET, 60_000))

        // And with neither, NO_BUDGET — i.e. every existing caller, in every coroutine that
        // installs no element, exactly as blocking as it has always been.
        assertEquals(
            ImapBudget.NO_BUDGET,
            ImapBudget.effective(ImapBudget.NO_BUDGET, ImapBudget.NO_BUDGET),
        )
    }

    // There was a second test here, and it was a fake: it recomposed `deadline(effective(…))`
    // itself and checked a subtraction, i.e. it re-derived the rule it was meant to be watching and

    @Test fun `an absurd budget cannot wrap around into a timeout`() {
        // Int arithmetic on millis is where a "very long" budget silently becomes a negative
        // one; the socket would then be handed a nonsense value.
        val deadline = ImapBudget.deadline(Int.MAX_VALUE, start)

        assertTrue(ImapBudget.remaining(deadline, start) > 0)
        assertEquals(Int.MAX_VALUE, ImapBudget.remaining(deadline, start))
    }
}
