package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT on the ONE call of `SmtpClient.send` in the repo.
 */
class SmtpSubmissionCallSiteTest {

    private val body = DaoQuerySource.mailFunctionBody("ImapMailService", "send")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `the submission passes no timeout, so it gets the shipped bounds`() {
        val at = body.indexOfFirst { it.startsWith("smtpClient.send(") }
        assertTrue(
            "ImapMailService.send no longer calls smtpClient.send( — if the call moved, this lint " +
                "moved with it or the app has no bounded submission left:\n" + body.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "the submission call site changed. Any argument added here overrides a bound the whole " +
                "of fix/smtp-coupure-et-attente exists to set; `commandTimeoutMs = 0` puts the " +
                "frozen outbox row back, silently:\n" + body.joinToString("\n"),
            listOf(
                "smtpClient.send(",
                "MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), " +
                    "credentials.username, credentials.password, token),",
                "message,",
                ")",
            ),
            body.drop(at).take(4),
        )
    }
}
