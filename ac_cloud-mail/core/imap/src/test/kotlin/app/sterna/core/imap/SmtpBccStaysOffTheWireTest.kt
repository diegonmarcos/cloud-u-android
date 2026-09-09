package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BLIND COPY, read off a real socket. `OutgoingMime.build` can now write a `Bcc:` header —
 */
class SmtpBccStaysOffTheWireTest {

    private fun message() = OutgoingMessage(
        from = "Tester <tester@example.org>",
        to = listOf("dest@example.org"),
        cc = listOf("copy@example.org"),
        bcc = listOf("blind@example.org"),
        subject = "hello there",
        body = "body text",
        messageId = "mid-1@example.org",
        dateMillis = 1_750_000_000_000L,
    )

    @Test
    fun `the transmitted message carries no Bcc header and never names the blind recipient`() {
        FakeSmtpServer().use { server ->
            runBlocking { SmtpClient().send(server.config, message()) }

            val delivered = server.delivered
            assertNotNull("the server accepted no message at all", delivered)
            assertEquals(
                "a Bcc header reached the DATA of a submission. These bytes are read by every " +
                    "recipient the message names, so this line tells them who the blind copies " +
                    "went to — the one disclosure a mail client cannot take back:\n$delivered",
                emptyList<String>(),
                delivered.orEmpty().lineSequence().filter { it.startsWith("Bcc:") }.toList(),
            )
            // Not just "no Bcc line": the address must not appear anywhere in the transmitted
            // bytes — a header spelled differently, or the body, discloses exactly as much.
            assertTrue(
                "the blind recipient is named in the transmitted message:\n$delivered",
                !delivered.orEmpty().contains("blind@example.org"),
            )
            // The visible addressing is untouched — this is not a test that passes by sending less.
            assertEquals(
                listOf("To: dest@example.org", "Cc: copy@example.org"),
                delivered.orEmpty().lineSequence().filter { it.startsWith("To:") || it.startsWith("Cc:") }.toList(),
            )
        }
    }

    @Test
    fun `the blind recipient is delivered to, by the envelope and by it alone`() {
        FakeSmtpServer().use { server ->
            runBlocking { SmtpClient().send(server.config, message()) }

            assertEquals(
                "the envelope is the ONLY way a blind copy travels: one RCPT TO per recipient, " +
                    "the blind one included. Missing it means the copy was never delivered.\n" +
                    server.issued().joinToString("\n"),
                listOf(
                    "RCPT TO:<dest@example.org>",
                    "RCPT TO:<copy@example.org>",
                    "RCPT TO:<blind@example.org>",
                ),
                server.issued().filter { it.startsWith("RCPT TO") },
            )
        }
    }
}
