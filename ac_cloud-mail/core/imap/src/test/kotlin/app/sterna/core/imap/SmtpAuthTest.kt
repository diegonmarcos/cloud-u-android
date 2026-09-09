package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Codeberg #145: autistici/inventati's submission service answers every `AUTH LOGIN` with
 */
class SmtpAuthTest {

    /** What the client wrote between its EHLO and its MAIL FROM: the whole AUTH dialogue. */
    private fun authDialogue(server: FakeSmtpServer): List<String> {
        val issued = server.issued()
        val start = issued.indexOfFirst { it.startsWith("EHLO") }
        check(start >= 0) { "no EHLO was ever written: $issued" }
        val end = issued.indexOfFirst { it.startsWith("MAIL FROM") }.let { if (it < 0) issued.size else it }
        return issued.subList(start + 1, end)
    }

    private fun decode(base64: String): String =
        String(Base64.getDecoder().decode(base64), Charsets.UTF_8)

    private fun send(server: FakeSmtpServer, subject: String = "hello there") =
        runBlocking { SmtpClient().send(server.config, outgoingFixture(subject)) }

    @Test
    fun `a server advertising PLAIN only gets one AUTH PLAIN with the SASL blob`() {
        FakeSmtpServer(authLine = "AUTH PLAIN").use { server ->
            send(server)

            val dialogue = authDialogue(server)
            assertEquals("expected exactly one AUTH command, got $dialogue", 1, dialogue.size)
            assertTrue(
                "PLAIN was advertised but the client wrote: ${dialogue.single()}",
                dialogue.single().startsWith("AUTH PLAIN "),
            )
            val payload = dialogue.single().removePrefix("AUTH PLAIN ")
            // Decoded, not compared to an opaque constant: a wrong encoding must be visible.
            val nul = Char(0)
            assertEquals(
                "$nul${FakeSmtpServer.USER}$nul${FakeSmtpServer.PASSWORD}",
                decode(payload),
            )
        }
    }

    @Test
    fun `a server advertising LOGIN only still gets the three-step LOGIN dialogue`() {
        FakeSmtpServer(authLine = "AUTH LOGIN").use { server ->
            send(server)

            val dialogue = authDialogue(server)
            assertEquals("expected AUTH LOGIN + two base64 lines, got $dialogue", 3, dialogue.size)
            assertEquals("AUTH LOGIN", dialogue[0])
            assertEquals(FakeSmtpServer.USER, decode(dialogue[1]))
            assertEquals(FakeSmtpServer.PASSWORD, decode(dialogue[2]))
        }
    }

    @Test
    fun `a server advertising both gets PLAIN`() {
        FakeSmtpServer(authLine = "AUTH PLAIN LOGIN").use { server ->
            send(server)

            val dialogue = authDialogue(server)
            assertEquals("expected exactly one AUTH command, got $dialogue", 1, dialogue.size)
            assertTrue(
                "PLAIN was advertised but the client wrote: ${dialogue.single()}",
                dialogue.single().startsWith("AUTH PLAIN "),
            )
        }
    }

    @Test
    fun `a server advertising no AUTH at all still gets AUTH LOGIN tried at it`() {
        // Non-regression: this is what the client has always done, and servers that stay silent
        // about AUTH but accept LOGIN are sending mail today. They must keep sending it.
        FakeSmtpServer(authLine = null).use { server ->
            send(server)

            val dialogue = authDialogue(server)
            assertEquals("expected AUTH LOGIN + two base64 lines, got $dialogue", 3, dialogue.size)
            assertEquals("AUTH LOGIN", dialogue[0])
            assertEquals(FakeSmtpServer.PASSWORD, decode(dialogue[2]))
        }
    }

    @Test
    fun `a server offering only CRAM-MD5 fails by name, without leaking the password`() {
        FakeSmtpServer(authLine = "AUTH CRAM-MD5").use { server ->
            val error = assertThrows(SmtpException::class.java) { send(server) }
            val text = error.message ?: ""

            assertTrue("the failure does not name what the server offers: $text", "CRAM-MD5" in text)
            assertFalse("the password itself is in the error message: $text", FakeSmtpServer.PASSWORD in text)
            val nul = Char(0)
            listOf(
                Base64.getEncoder().encodeToString(FakeSmtpServer.PASSWORD.toByteArray()),
                saslPlainPayload(FakeSmtpServer.USER, FakeSmtpServer.PASSWORD),
                Base64.getEncoder().encodeToString("$nul${FakeSmtpServer.USER}".toByteArray()),
            ).forEach { secret ->
                assertFalse("an encoded credential is in the error message: $text", secret in text)
            }
            assertEquals(
                "nothing should have been sent to a server whose mechanisms we cannot speak",
                emptyList<String>(),
                authDialogue(server),
            )
        }
    }

    @Test
    fun `the witness of 145 - PLAIN advertised, every AUTH LOGIN refused, mail goes out`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line ->
                if (line.equals("AUTH LOGIN", ignoreCase = true)) {
                    listOf("535 5.7.8 Error: authentication failed: Invalid authentication mechanism: 'LOGIN'")
                } else {
                    null
                }
            },
        ).use { server ->
            send(server, subject = "inventati witness")

            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: inventati witness"),
            )
        }
    }

    @Test
    fun `every reply multi-line - the final line is still what expect compares`() {
        // The capability accumulator must not change what read() returns, nor leave a continuation
        // line in the buffer: a desync here breaks EVERY send, not just authentication.
        FakeSmtpServer(authLine = "AUTH PLAIN LOGIN", multiline = true).use { server ->
            send(server, subject = "multi line")

            assertEquals(
                listOf(
                    "EHLO [127.0.0.1]",
                    "MAIL FROM:<tester@example.org>",
                    "RCPT TO:<dest@example.org>",
                    "DATA",
                ),
                server.issued().filterNot { it in authDialogue(server) },
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: multi line"),
            )
        }
    }
}

/**
 * The decision itself, executed — not re-derived by a test that then replays what it derived.
 * Arguments in, result out.
 */
class SmtpAuthChoiceTest {

    @Test
    fun `the AUTH capability is read off the EHLO response in both spellings`() {
        val greeting = "250-fake.local greets you"
        assertEquals(
            listOf("PLAIN", "LOGIN"),
            advertisedAuthMechanisms(listOf(greeting, "250-AUTH PLAIN LOGIN", "250 HELP")),
        )
        assertEquals(
            listOf("PLAIN", "LOGIN"),
            advertisedAuthMechanisms(listOf(greeting, "250-AUTH=PLAIN LOGIN", "250 HELP")),
        )
        // Case of the keyword and of the mechanisms, and the capability on the FINAL line.
        assertEquals(
            listOf("PLAIN"),
            advertisedAuthMechanisms(listOf(greeting, "250 auth plain")),
        )
        assertEquals(
            listOf("CRAM-MD5", "DIGEST-MD5"),
            advertisedAuthMechanisms(listOf(greeting, "250-AUTH CRAM-MD5 DIGEST-MD5", "250 HELP")),
        )
        // No AUTH line at all, and a capability that merely starts with the same letters.
        assertEquals(
            emptyList<String>(),
            advertisedAuthMechanisms(listOf(greeting, "250-SIZE 35882577", "250-AUTHENTICATE FOO", "250 HELP")),
        )
    }

    @Test
    fun `the mechanism chosen for what a server offers`() {
        assertEquals(SmtpAuthMechanism.PLAIN, chooseAuthMechanism(listOf("PLAIN")))
        assertEquals(SmtpAuthMechanism.LOGIN, chooseAuthMechanism(listOf("LOGIN")))
        // Preference, in both listing orders.
        assertEquals(SmtpAuthMechanism.PLAIN, chooseAuthMechanism(listOf("PLAIN", "LOGIN")))
        assertEquals(SmtpAuthMechanism.PLAIN, chooseAuthMechanism(listOf("LOGIN", "PLAIN")))
        // Nothing advertised: exactly what the client did before #145.
        assertEquals(SmtpAuthMechanism.LOGIN, chooseAuthMechanism(emptyList()))
        // Whole tokens, never substrings.
        assertNull(chooseAuthMechanism(listOf("CRAM-MD5")))
        assertNull(chooseAuthMechanism(listOf("PLAIN-MD5")))
        assertNull(chooseAuthMechanism(listOf("LOGIN-MD5", "XOAUTH2")))
    }

    @Test
    fun `the SASL PLAIN payload is one blob of NUL user NUL password`() {
        val nul = Char(0)
        val payload = saslPlainPayload("alex@example.org", "pä ss")
        assertEquals(
            "${nul}alex@example.org${nul}pä ss",
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8),
        )
    }
}

/**
 * SOURCE LINT. No JVM test can reach the STARTTLS branch (it needs a certificate), so the rule
 */
class SmtpCapabilityOrderLintTest {

    private val body = ImapSource.functionBody("SmtpClient", "send")

    private fun indicesOf(line: String): List<Int> =
        body.indices.filter { body[it] == line }

    private fun onlyIndexOf(line: String): Int {
        val hits = indicesOf(line)
        assertEquals("expected exactly one `$line` in send():\n" + body.joinToString("\n"), 1, hits.size)
        return hits.single()
    }

    @Test
    fun `the capability accumulator is local to send`() {
        // A field on SmtpClient would mix two accounts' capabilities: the config is per account,
        // the client object need not be.
        onlyIndexOf("val lastResponse = mutableListOf<String>()")
    }

    /**
     * The `clear()` stays counted over the WHOLE of `send()`, and that is the point of the test.
     */
    @Test
    fun `each response resets the accumulator before reading`() {
        val clear = onlyIndexOf("lastResponse.clear()")
        val firstReads = indicesOf("var line = reader.readLine() ?: throw SmtpException(\"Connection closed\")")
        assertEquals(
            "send() no longer opens exactly two readers on that line (read, readBodyAck) — a third " +
                "one would not be covered by the rule below:\n" + body.joinToString("\n"),
            2,
            firstReads.size,
        )
        assertTrue(
            "the accumulator is not cleared before the response is read (clear=$clear, " +
                "reads=$firstReads):\n" + body.joinToString("\n"),
            clear < firstReads.first(),
        )
    }

    @Test
    fun `capabilities are taken from the EHLO that follows STARTTLS`() {
        val ehlos = indicesOf("write(\"EHLO \${localHost()}\")")
        assertEquals("send() no longer issues exactly two EHLOs:\n" + body.joinToString("\n"), 2, ehlos.size)

        val first = onlyIndexOf("var advertisedAuth = advertisedAuthMechanisms(lastResponse)")
        val afterStartTls = onlyIndexOf("advertisedAuth = advertisedAuthMechanisms(lastResponse)")
        val startTlsEhloAck = onlyIndexOf("expect(\"250\", \"EHLO (TLS)\")")
        val decision = onlyIndexOf("when (chooseAuthMechanism(advertisedAuth)) {")

        assertTrue("the first capability read is not after the first EHLO", first > ehlos[0])
        assertTrue("the first capability read is not before the STARTTLS EHLO", first < ehlos[1])
        assertTrue(
            "the capabilities of the STARTTLS EHLO are not re-read after it " +
                "(ehlo=${ehlos[1]}, ack=$startTlsEhloAck, read=$afterStartTls)",
            afterStartTls > ehlos[1] && afterStartTls > startTlsEhloAck,
        )
        assertTrue("the mechanism is chosen before the capabilities are re-read", decision > afterStartTls)
    }
}
