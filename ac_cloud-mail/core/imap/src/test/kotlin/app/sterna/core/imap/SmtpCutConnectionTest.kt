package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A submission that dies in the MIDDLE of a multi-line response.
 */
class SmtpCutConnectionTest {

    private fun send(server: FakeSmtpServer, subject: String = "hello there") =
        runBlocking { SmtpClient().send(server.config, outgoingFixture(subject)) }

    /** J1 — the cut lands between two lines of the EHLO response, the one that is always multi-line. */
    @Test
    fun `an EHLO cut after a continuation line fails at the EHLO, not three steps later`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line ->
                // One continuation line, then nothing: the "250 " final line never comes, and
                // neither does the AUTH capability that was going to be on it.
                if (line.startsWith("EHLO")) listOf("250-fake.local greets you") else null
            },
        ).use { server ->
            val error = assertThrows(SmtpException::class.java) { send(server) }
            server.awaitDone()

            assertEquals("Connection closed mid-response", error.message)
            // The whole point: the session stops at the EHLO. Before the fix the client accepted the
            // truncated response, computed its capabilities from a partial list, and went on to
            // write an AUTH command at a server that had already gone.
            assertEquals(
                "the client kept talking after a response that never finished",
                listOf("EHLO [127.0.0.1]"),
                server.issued(),
            )
        }
    }

    /**
     * J2 — the cut lands inside the 250 that acknowledges the message BODY, after a continuation
     */
    @Test
    fun `a cut inside the 250 that acknowledges the body is a successful send`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) listOf("250-2.0.0 accepted") else null
            },
        ).use { server ->
            send(server)
            server.awaitDone()

            assertEquals("DATA", server.issued().last())
        }
    }

    /**
     * J2-witness — the same two-line acknowledgement, complete this time.
     */
    @Test
    fun `a complete multi-line acknowledgement of the body queues the message`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) {
                    listOf("250-2.0.0 accepted", "250 2.0.0 queued as FAKE1")
                } else {
                    null
                }
            },
        ).use { server ->
            send(server, subject = "acknowledged whole")

            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: acknowledged whole"),
            )
        }
    }

    /**
     * J2b — the cut lands BEFORE a single line of the body's acknowledgement.
     */
    @Test
    fun `a cut before any line of the body acknowledgement is still a failed send`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line -> if (line == FakeSmtpServer.END_OF_DATA) emptyList() else null },
        ).use { server ->
            val error = assertThrows(
                "send() returned normally: a message nobody ever acknowledged was reported as sent",
                SmtpException::class.java,
            ) { send(server) }
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertEquals("DATA", server.issued().last())
        }
    }

    /**
     * J2c — a cut acknowledgement whose code is a REFUSAL. The code decides, not the fact that a
     */
    @Test
    fun `a cut 421 answering the body is a failed send, quoting the line`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) {
                    listOf("421-4.3.2 service closing transmission channel")
                } else {
                    null
                }
            },
        ).use { server ->
            val error = assertThrows(
                "a refusal spread over two lines was reported as a successful send",
                SmtpException::class.java,
            ) { send(server) }
            server.awaitDone()

            assertEquals(
                "message body: 421-4.3.2 service closing transmission channel",
                error.message,
            )
        }
    }

    /** J3 — the case that already worked: not a single byte of the response. Must not change. */
    @Test
    fun `a cut before the first line of a response still says Connection closed`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line -> if (line.startsWith("MAIL FROM")) emptyList() else null },
        ).use { server ->
            val error = assertThrows(SmtpException::class.java) { send(server) }
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertEquals("MAIL FROM:<tester@example.org>", server.issued().last())
        }
    }

    /** J4 — the nominal multi-line response, complete. The final line is returned, `expect` is happy. */
    @Test
    fun `a complete multi-line response is still read whole and still sends`() {
        FakeSmtpServer(authLine = "AUTH PLAIN LOGIN", multiline = true).use { server ->
            send(server, subject = "complete multiline")

            val issued = server.issued()
            // AUTH PLAIN was announced on a CONTINUATION line of the EHLO response: choosing it
            // proves every line was accumulated. Reaching MAIL FROM proves only that the reader is
            assertEquals(
                "expected exactly one AUTH command between EHLO and MAIL FROM, got $issued",
                1,
                issued.count { it.startsWith("AUTH ") },
            )
            assertTrue(
                "PLAIN was advertised on a continuation line but the client wrote: $issued",
                issued.any { it.startsWith("AUTH PLAIN ") },
            )
            assertEquals(
                listOf("EHLO [127.0.0.1]", "MAIL FROM:<tester@example.org>", "RCPT TO:<dest@example.org>", "DATA"),
                issued.filterNot { it.startsWith("AUTH ") },
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: complete multiline"),
            )
        }
    }

    /**
     * J5 — a REAL EHLO, eight continuation lines before the final one. Gmail and Stalwart answer
     */
    @Test
    fun `an eight-line EHLO is read to its last line, capability and all`() {
        val longEhlo = listOf(
            "250-fake.local greets you",
            "250-PIPELINING",
            "250-SIZE 35882577",
            "250-8BITMIME",
            "250-AUTH PLAIN",
            "250-ENHANCEDSTATUSCODES",
            "250-CHUNKING",
            "250-SMTPUTF8",
            "250 HELP",
        )
        FakeSmtpServer(
            respond = { line -> if (line.startsWith("EHLO")) longEhlo else null },
        ).use { server ->
            send(server, subject = "long ehlo")

            val issued = server.issued()
            // The capability was on line five: choosing PLAIN is only possible if lines four to
            // eight were accumulated too.
            assertEquals(
                "the fifth line of the EHLO was never accumulated; the client wrote $issued",
                1,
                issued.count { it.startsWith("AUTH PLAIN ") },
            )
            // And reaching the envelope means the loop stopped on the REAL final line, "250 HELP",
            // leaving nothing of the EHLO in the buffer for MAIL FROM to trip over.
            assertEquals(
                listOf("EHLO [127.0.0.1]", "MAIL FROM:<tester@example.org>", "RCPT TO:<dest@example.org>", "DATA"),
                issued.filterNot { it.startsWith("AUTH ") },
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: long ehlo"),
            )
        }
    }

    /**
     * J6 — the FINAL line, not merely "a line of the response".
     */
    @Test
    fun `the line handed back is the last one, which is the one the error message quotes`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line ->
                if (line.startsWith("AUTH PLAIN")) {
                    listOf(
                        "535-5.7.8 first line, which is not the verdict",
                        "535 5.7.8 last line, which is the verdict",
                    )
                } else {
                    null
                }
            },
        ).use { server ->
            val error = assertThrows(SmtpException::class.java) { send(server) }

            assertEquals(
                "AUTH PLAIN: 535 5.7.8 last line, which is the verdict",
                error.message,
            )
        }
    }
}

/**
 * SOURCE LINT (J11), the belt to the braces of the four tests above: the `break` that swallowed a
 */
class SmtpCutLintTest {

    @Test
    fun `every readLine in read either yields a line or throws`() {
        val body = ImapSource.functionBody("SmtpClient", "read")
        assertEquals(
            "read() no longer reads exactly two ways, and both of them throwing is the rule:\n" +
                body.joinToString("\n"),
            listOf(
                "var line = reader.readLine() ?: throw SmtpException(\"Connection closed\")",
                "line = reader.readLine() ?: throw SmtpException(\"Connection closed mid-response\")",
            ),
            body.filter { "reader.readLine()" in it },
        )
    }

    /**
     * The same belt for `readBodyAck`, whose two reads are NOT the same rule: the first throws like
     */
    @Test
    fun `readBodyAck throws on the first line and yields on a later one`() {
        // Comment lines dropped, and only they — the KDoc there quotes the wrong form of the very
        // line being pinned, and a comment naming a read is not a read. Nothing else is filtered: a
        // mutation that lengthens a statement still shows up whole.
        val body = ImapSource.functionBody("SmtpClient", "readBodyAck")
            .filterNot { Regex("""^(//|/?\*)""").containsMatchIn(it) }
        assertEquals(
            "readBodyAck no longer reads exactly two ways, the first refusing and the second " +
                "yielding:\n" + body.joinToString("\n"),
            listOf(
                "var line = reader.readLine() ?: throw SmtpException(\"Connection closed\")",
                "val next = reader.readLine() ?: return line",
            ),
            body.filter { "reader.readLine()" in it },
        )
    }
}
