package app.sterna.core.data.account

import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [retainReachableMailAccounts] (Codeberg #129): the pure keep/discard decision applied to the mail
 */
class ReachableMailAccountsTest {

    private val primary = DiscoveredMailAccount("s", "alex@example.org")
    private val shared = DiscoveredMailAccount("u", "jordan@example.org")
    private val calendarOnly = DiscoveredMailAccount("cal", "casey@example.org")

    private fun forbidden() = JmapException("Forbidden", httpCode = 403, errorType = "forbidden")

    /** A probe that threw, as the repository's `runCatching` would have recorded it. */
    private fun failed(cause: Throwable): Result<List<Mailbox>> = Result.failure(cause)

    private fun mailboxes(vararg names: String): Result<List<Mailbox>> =
        Result.success(names.mapIndexed { i, name -> Mailbox(id = "mb$i", name = name) })

    // The primary is the login's own account and the one the connection pins itself to: it is never
    // probed, and even an answer saying otherwise must not take it out of the list.
    @Test fun primaryIsKeptEvenWhenAProbeClaimsItIsForbidden() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf(
                "s" to failed(forbidden()),
                "u" to mailboxes("Inbox", "Sent", "Trash", "Junk"),
            ),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    // The reported case, straight from the bench: Mailbox/get on the calendar-shared account
    // answers the JMAP error `forbidden`.
    @Test fun nonPrimaryRefusedAsForbiddenIsDiscarded() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, calendarOnly),
            probes = mapOf("cal" to failed(forbidden())),
        )

        assertEquals(listOf(primary), kept)
    }

    // The #31 witness beside it: a genuinely shared mailbox answers with its mailboxes and stays.
    @Test fun theGenuinelySharedAccountBesideItSurvivesTheSameSweep() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared, calendarOnly),
            probes = mapOf(
                "u" to mailboxes("Inbox", "Sent", "Trash", "Junk"),
                "cal" to failed(forbidden()),
            ),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    // An account can legitimately expose no mailbox to this login. That is a served answer, not a
    // refusal, so counting mailboxes is NOT the rule.
    @Test fun probeThatSucceededWithNoMailboxAtAllIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to Result.success(emptyList<Mailbox>())),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    @Test fun networkFailureIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to failed(IOException("connection reset"))),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    @Test fun timeoutIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to failed(SocketTimeoutException("timeout"))),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    // Shaped like what the probe really produces: JmapClient.getMailboxes reports an HTTP failure
    // as a message only — no httpCode, no errorType (JmapClientTest pins that). A fixture carrying
    // httpCode = 429 would be evidence no server ever hands this rule.
    @Test fun rateLimitIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to failed(JmapException("Mailbox/get failed: HTTP 429 Too Many Requests"))),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    // Another JMAP method error is not the server saying "not yours to read".
    @Test fun anotherJmapErrorTypeIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to failed(JmapException("no such account", errorType = "accountNotFound"))),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    // A capitalised or namespaced spelling is not the error type the bench observed; guessing at it
    // would discard an account on a string nobody has seen a server send.
    @Test fun anErrorTypeThatMerelyLooksLikeForbiddenIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to failed(JmapException("nope", errorType = "urn:ietf:params:jmap:forbidden"))),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    // Case matters: the bench saw the lowercase JMAP error type. Matching case-insensitively would
    // be a rule about a string nobody has observed, and it discards accounts.
    @Test fun anUppercaseErrorTypeIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to failed(JmapException("nope", errorType = "FORBIDDEN"))),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    // An HTTP 403 with no method-level error type is not the evidence this rule acts on.
    @Test fun httpForbiddenWithoutAnErrorTypeIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared),
            probes = mapOf("u" to failed(JmapException("HTTP 403", httpCode = 403))),
        )

        assertEquals(listOf(primary, shared), kept)
    }

    @Test fun candidateThatWasNeverProbedIsKept() {
        val kept = retainReachableMailAccounts(
            discovered = listOf(primary, shared, calendarOnly),
            probes = emptyMap(),
        )

        assertEquals(listOf(primary, shared, calendarOnly), kept)
    }

    // The store reads the head of this list as the login's own account (AccountStore.
    // diffLinkedAccounts pins the connection to it), so the order out must be the order in.
    @Test fun outputKeepsTheInputOrderPrimaryFirst() {
        val a = DiscoveredMailAccount("a", "a@example.org")
        val b = DiscoveredMailAccount("b", "b@example.org")
        val c = DiscoveredMailAccount("c", "c@example.org")
        val d = DiscoveredMailAccount("d", "d@example.org")

        val kept = retainReachableMailAccounts(
            discovered = listOf(a, b, c, d),
            probes = mapOf(
                "b" to mailboxes("Inbox"),
                "c" to failed(forbidden()),
                "d" to Result.success(emptyList<Mailbox>()),
            ),
        )

        assertEquals(listOf(a, b, d), kept)
    }

    // A session listing nothing at all is not evidence of anything (AccountStore.kt: "never prune
    // on evidence that weak") — and there is no head to keep either.
    @Test fun anEmptySessionStaysEmpty() {
        assertEquals(
            emptyList<DiscoveredMailAccount>(),
            retainReachableMailAccounts(discovered = emptyList(), probes = emptyMap()),
        )
    }
}
