package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH arguments `MailRepository` hands [app.sterna.core.data.account.autocryptHeaderValue] at each
 */
class AutocryptSendWiringTest {

    private val source = DaoQuerySource.mailSource("MailRepository")

    /** The argument text of every `autocryptHeaderValue(…)` call, whitespace collapsed. */
    private fun callArguments(): List<String> {
        val out = mutableListOf<String>()
        var at = source.indexOf(NAME)
        while (at >= 0) {
            var i = at + NAME.length
            var depth = 1
            val start = i
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            out += source.substring(start, i - 1).replace(Regex("\\s+"), " ").trim().trimEnd(',').trim()
            at = source.indexOf(NAME, i)
        }
        return out
    }

    @Test fun `the three send call sites, and their arguments`() {
        assertEquals(
            listOf(
                // IMAP/SMTP submission (and the Sent APPEND, which reuses this same message):
                // `outgoing` builds the From from fromEmail, falling back to the account's own
                // address, and the rule is asked about that very address.
                "sendingAccount, fromEmail?.takeIf { it.isNotBlank() } ?: credentials.username",
                // JMAP with a pre-built entity — a signed or encrypted send, whose raw message is
                // built by OutgoingMime.build.
                "sendingAccount, from.email",
                // JMAP, the ordinary send.
                "sendingAccount, from.email",
            ),
            callArguments(),
        )
    }

    @Test fun `the account handed over is the one this send belongs to`() {
        assertEquals(
            listOf("val sendingAccount = accountStore.account(credentials.id)"),
            source.lines().map { it.trim() }.filter { it.startsWith("val sendingAccount") },
        )
    }

    @Test fun `from is still the address the correspondent will see`() {
        // `from.email` only means what this test claims while THIS is how `from` is built: the
        // message's own sender address, trimmed — not the identity the submission runs under, which
        // on a delegated send belongs to the login.
        assertTrue(
            source.lines().map { it.trim() }.contains(
                "val from = if (!fromEmail.isNullOrBlank()) EmailAddress(name = fromName, email = fromEmail.trim())",
            ),
        )
    }

    private companion object {
        const val NAME = "autocryptHeaderValue("
    }
}
