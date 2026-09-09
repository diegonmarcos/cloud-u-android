package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the `mailto:` unsubscribe actually puts in the outbox.
 */
class UnsubscribeMailTest {

    private val identityName = "Alex Doe"
    private val identityEmail = "alex@masto.top"

    @Test fun `the mail goes to the address the header named`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com"),
            identityName,
            identityEmail,
        )

        assertEquals(listOf("leave@list.example.com"), mail.to)
    }

    /**
     * Some lists key the unsubscribe off the subject line, so the sender's own subject is used
     * when there is one — a mail with our default subject would be silently ignored by them.
     */
    @Test fun `the subject and body come from the uri when it carries them`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com", subject = "unsubscribe abc", body = "token=xyz"),
            identityName,
            identityEmail,
        )

        assertEquals("unsubscribe abc", mail.subject)
        assertEquals("token=xyz", mail.body)
    }

    /**
     * The witness: with nothing in the URI, a fixed subject and an empty body.
     */
    @Test fun `a bare mailto falls back to a fixed english subject and an empty body`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com"),
            identityName,
            identityEmail,
        )

        assertEquals("Unsubscribe", UNSUBSCRIBE_SUBJECT)
        assertEquals(UNSUBSCRIBE_SUBJECT, mail.subject)
        assertEquals("", mail.body)
    }

    /**
     * The account's own identity travels with the mail (issue #31): a delegated sub-account is
     */
    @Test fun `the account identity is carried, so a delegated sub-account unsubscribes itself`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com"),
            identityName,
            identityEmail,
        )

        assertEquals("Alex Doe", mail.fromName)
        assertEquals("alex@masto.top", mail.fromEmail)
    }

    /** No identity known: nothing is invented — the send path falls back to the account's own. */
    @Test fun `no identity leaves the from fields alone`() {
        val mail = unsubscribeMail(MailtoUnsubscribe("leave@list.example.com"), null, null)

        assertNull(mail.fromName)
        assertNull(mail.fromEmail)
    }

    // ---- the hand-over to the outbox, read out of the shipped source: a LAST RESORT ----

    /**
     * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `MailRepository.kt` as text. It proves nothing
     */
    @Test fun `the repository hands each field to the argument of the same name`() {
        val body = sendUnsubscribeMailBody()
        assertTrue(
            "the mail must be built by unsubscribeMail, from the header's mailto and the " +
                "account's own identity — not assembled at the call site. Body was:\n$body",
            "val mail = unsubscribeMail(mailto, identity?.name, identity?.email)" in body,
        )
        listOf(
            "to = mail.to,",
            "subject = mail.subject,",
            "body = mail.body,",
            "fromName = mail.fromName,",
            "fromEmail = mail.fromEmail,",
        ).forEach { argument ->
            assertTrue(
                "enqueueSend must be given '$argument'. Two of these swapped is a send nobody " +
                    "can call back, agreeing with the dialog all the way down. Body was:\n$body",
                argument in body,
            )
        }
    }

    /** The body of `MailRepository.sendUnsubscribeMail`, from its signature to the next block. */
    private fun sendUnsubscribeMailBody(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun sendUnsubscribeMail(")
        check(start >= 0) { "MailRepository no longer declares sendUnsubscribeMail(...)" }
        val end = listOf("\n    // ----", "\n    /**")
            .mapNotNull { source.indexOf(it, start).takeIf { at -> at > start } }
            .minOrNull()
        check(end != null) { "nothing follows sendUnsubscribeMail — the slice would be the whole file" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
