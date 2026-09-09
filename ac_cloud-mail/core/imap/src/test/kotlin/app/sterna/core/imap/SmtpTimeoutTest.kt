package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * A submission against a server that ACCEPTS and then makes the client wait.
 */
class SmtpTimeoutTest {

    /**
     * How long a bounded send is given to come back: ten times the bound handed to the client, a
     */
    private val waitMs = 5_000L

    /**
     * Short bound on the COMMANDS only; the connect keeps its shipped default and is never passed.
     */
    private val commandTimeoutMs = 500

    /**
     * Run [block] on a daemon thread and give it [millis] to return; the throwable it ended with,
     */
    private fun sendWithin(millis: Long, block: () -> Unit): Throwable? {
        val outcome = AtomicReference<Result<Unit>>()
        val thread = Thread({ outcome.set(runCatching(block)) }, "smtp-under-test")
            .apply { isDaemon = true; start() }
        thread.join(millis)
        check(!thread.isAlive) {
            "send() had not returned after ${millis}ms — the dialogue is not bounded in time. " +
                "That is the outbox row frozen on 'sending', reproduced."
        }
        return outcome.get().exceptionOrNull()
    }

    private fun sendBlocking(server: FakeSmtpServer, subject: String = "hello there") = runBlocking {
        SmtpClient().send(server.config, outgoingFixture(subject), commandTimeoutMs = commandTimeoutMs)
    }

    /** B-J5 — the connection is accepted and the greeting never comes. The first read of all. */
    @Test
    fun `a server that accepts and never greets fails inside the command timeout`() {
        FakeSmtpServer(greeting = { null }).use { server ->
            val error = sendWithin(waitMs) { sendBlocking(server) }

            assertTrue(
                "expected the read of the greeting to time out, got ${error?.javaClass?.name}: ${error?.message}",
                error is SocketTimeoutException,
            )
            // Nothing was written at a server that never said hello: EHLO waits on the 220. Behind
            // awaitDone like its neighbour below — a list still being written by the serving thread
            // can be empty for a moment for reasons that have nothing to do with the claim.
            server.awaitDone()
            assertEquals(emptyList<String>(), server.issued())
        }
    }

    /** B-J6 — the server goes quiet in the MIDDLE of the dialogue, connection still open. */
    @Test
    fun `a server that goes silent after MAIL FROM fails inside the command timeout`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            // An empty reply: not a byte written, and the connection stays up. The client is left
            // reading a socket nobody will ever write to again.
            respond = { line -> if (line.startsWith("MAIL FROM")) emptyList() else null },
        ).use { server ->
            val error = sendWithin(waitMs) { sendBlocking(server) }

            assertTrue(
                "expected the read after MAIL FROM to time out, got ${error?.javaClass?.name}: ${error?.message}",
                error is SocketTimeoutException,
            )
            server.awaitDone()
            assertEquals("MAIL FROM:<tester@example.org>", server.issued().last())
        }
    }

    /**
     * B-J8 — the acknowledgement of the BODY takes far longer than the command timeout, and the
     */
    @Test
    fun `a slow acknowledgement of the body is waited for, past the command timeout`() {
        val ackDelayMs = 4L * commandTimeoutMs
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) {
                    Thread.sleep(ackDelayMs)
                    listOf("250 2.0.0 queued as FAKE1")
                } else {
                    null
                }
            },
        ).use { server ->
            val error = sendWithin(ackDelayMs * 3) { sendBlocking(server, subject = "slow queue") }

            assertNull(
                "the send was cut while the server was still queueing the message; " +
                    "OutboxWorker would send it a second time. Got: $error",
                error,
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: slow queue"),
            )
        }
    }

    /**
     * B-J9 — a send that FAILS closes its connection.
     */
    @Test
    fun `a rejected recipient still closes the connection`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line -> if (line.startsWith("RCPT TO")) listOf("550 5.1.1 no such user") else null },
        ).use { server ->
            val error = sendWithin(waitMs) { sendBlocking(server) }

            assertTrue(
                "expected the refusal to surface as an SmtpException, got ${error?.javaClass?.name}",
                error is SmtpException,
            )
            assertEquals("RCPT TO: 550 5.1.1 no such user", error?.message)
            // The claim: the server saw the connection go. A client that merely dropped the object
            // leaves this thread parked on a read, and awaitDone says so.
            server.awaitDone(3_000)
        }
    }

    /** The nominal path, with the shipped defaults and no argument at all: unchanged. */
    @Test
    fun `a plain send still goes through with no timeout argument`() {
        FakeSmtpServer(authLine = "AUTH PLAIN LOGIN").use { server ->
            runBlocking { SmtpClient().send(server.config, outgoingFixture("nominal")) }

            assertEquals(
                listOf("EHLO [127.0.0.1]", "MAIL FROM:<tester@example.org>", "RCPT TO:<dest@example.org>", "DATA"),
                server.issued().filterNot { it.startsWith("AUTH ") },
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: nominal"),
            )
        }
    }
}

/**
 * SOURCE LINT, and it is a lint and not a measurement: `FakeSmtpServer` speaks no TLS, so
 */
class SmtpTimeoutLintTest {

    /**
     * The STATEMENTS of `send()`. Comment lines are dropped, and only they: what is asserted below
     */
    private val body = ImapSource.functionBody("SmtpClient", "send")
        .filterNot { Regex("""^(//|/?\*)""").containsMatchIn(it) }

    private fun onlyIndexOf(line: String): Int {
        val hits = body.indices.filter { body[it] == line }
        assertEquals("expected exactly one `$line` in send():\n" + body.joinToString("\n"), 1, hits.size)
        return hits.single()
    }

    @Test
    fun `the shipped bounds are the JMAP submission ones, and they are the defaults`() {
        // core/jmap JmapClient.defaultHttpClient(): connectTimeout(20, SECONDS) / readTimeout(30, SECONDS).
        assertEquals(20_000, SMTP_CONNECT_TIMEOUT_MS)
        assertEquals(30_000, SMTP_COMMAND_TIMEOUT_MS)
        // 0 is the socket's own default and means "no deadline of ours".
        assertEquals(0, SMTP_BLOCKING_READ)
        assertEquals(
            "send() no longer takes its two bounds as parameters defaulting to the shipped values:\n" +
                ImapSource.functionSignature("SmtpClient", "send").joinToString("\n"),
            listOf(
                "fun send(",
                "config: MailServerConfig,",
                "message: OutgoingMessage,",
                "connectTimeoutMs: Int = SMTP_CONNECT_TIMEOUT_MS,",
                "commandTimeoutMs: Int = SMTP_COMMAND_TIMEOUT_MS,",
                ")",
            ),
            ImapSource.functionSignature("SmtpClient", "send"),
        )
    }

    @Test
    fun `the connect is the form that takes a deadline, and it is given one`() {
        // `Socket(host, port)` connects in its constructor, with no timeout at all: minutes at
        // the OS's discretion. The two-step form is the only one that can be bounded.
        assertEquals(
            "the connect no longer passes the connect timeout:\n" + body.joinToString("\n"),
            listOf("plain.connect(InetSocketAddress(config.host, config.port), connectTimeout)"),
            body.filter { "connect(" in it && "InetSocketAddress" in it },
        )
        onlyIndexOf("val connectTimeout = connectTimeoutMs.coerceAtLeast(0)")
    }

    /**
     * The hostname check, on both TLS sockets.
     */
    @Test
    fun `every TLS socket verifies the hostname before it shakes hands`() {
        assertEquals(
            "the hostname check is gone from a TLS socket — an intercepted submission would hand " +
                "over the password:\n" + body.joinToString("\n"),
            listOf(".verifyingHostname()", ".verifyingHostname()"),
            body.filter { "verifyingHostname" in it },
        )
        assertEquals(
            "send() no longer builds exactly two TLS sockets; a third one would not be covered by " +
                "the rule below:\n" + body.joinToString("\n"),
            2,
            body.count { "tlsFactory.createSocket" in it },
        )
        body.indices.filter { "startHandshake()" in body[it] }.forEach { at ->
            assertEquals(
                "the handshake at line $at shakes hands with a socket that never got the hostname " +
                    "check:\n" + body.joinToString("\n"),
                ".verifyingHostname()",
                body[at - 2],
            )
        }
    }

    @Test
    fun `every TLS handshake is armed on the line before it`() {
        val handshakes = body.indices.filter { "startHandshake()" in body[it] }
        assertEquals(
            "send() no longer performs exactly two handshakes (implicit TLS, then STARTTLS):\n" +
                body.joinToString("\n"),
            listOf("secure.startHandshake()", "secure.startHandshake()"),
            handshakes.map { body[it] },
        )
        handshakes.forEach { at ->
            assertEquals(
                "the handshake at line $at is not preceded by the line that bounds its reads; a bound " +
                    "armed after it leaves a handshake against a mute server waiting for ever:\n" +
                    body.joinToString("\n"),
                "secure.soTimeout = commandTimeout",
                body[at - 1],
            )
        }
    }

    /**
     * Every read bound `send()` sets, in order, whole line — what is armed AND what is disarmed.
     */
    @Test
    fun `every read bound is armed and put back, on every socket that was armed`() {
        assertEquals(
            "the read bounds of send() are no longer these, in this order:\n" + body.joinToString("\n"),
            listOf(
                "plain.soTimeout = commandTimeout",
                "secure.soTimeout = commandTimeout",
                "secure.soTimeout = commandTimeout",
                "plain.soTimeout = SMTP_BLOCKING_READ",
                "socket.soTimeout = SMTP_BLOCKING_READ",
            ),
            body.filter { "soTimeout" in it },
        )
        // The line moved from `expect("250", "message body")` to a dedicated reader, because a
        // reply CUT after its first line is an accepted message here (SmtpCutConnectionTest J2).
        // The claim below is unchanged: whatever reads the body's acknowledgement, there is one of
        // it and it comes straight after the two statements that unbound the socket.
        val ack = onlyIndexOf("val ack = readBodyAck()")
        assertEquals(
            "the body's acknowledgement is no longer read right after the two statements that " +
                "unbound it:\n" + body.joinToString("\n"),
            listOf("plain.soTimeout = SMTP_BLOCKING_READ", "socket.soTimeout = SMTP_BLOCKING_READ"),
            listOf(body[ack - 2], body[ack - 1]),
        )
    }

    @Test
    fun `the connection is closed on every path out`() {
        val close = onlyIndexOf("runCatching { socket.close() }")
        assertEquals(
            "the close is no longer the body of a finally — a failed send would leak its socket:\n" +
                body.joinToString("\n"),
            "} finally {",
            body[close - 1],
        )
    }
}
