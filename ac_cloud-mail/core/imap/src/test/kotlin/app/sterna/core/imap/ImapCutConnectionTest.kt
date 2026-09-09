package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server that GOES AWAY while the client is waiting for it (#156).
 */
class ImapCutConnectionTest {

    /** A server that logs in, selects, and idles — the push path, and nothing else. */
    private fun idleServer(
        cutAfter: (String) -> List<String>? = { null },
        cutDelayMs: Long = 0,
    ) = FakeImapServer(
        responder = { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("IDLE") -> "+ idling\r\n"
                else -> ok(tag)
            }
        },
        cutAfter = cutAfter,
        cutDelayMs = cutDelayMs,
    )

    /** The cut every IDLE test uses: answer the continuation, go quiet, then go away. */
    private val cutInIdle: (String) -> List<String>? = { line ->
        if (line == "IDLE") listOf("+ idling") else null
    }

    /**
     * Close [socket] after [afterMs], whatever the test is doing — the net under a spin. A caller
     */
    private fun closeAfter(socket: Socket, afterMs: Long = 6_000) {
        Thread {
            Thread.sleep(afterMs)
            runCatching { socket.close() }
        }.apply { isDaemon = true; start() }
    }

    /**
     * Close an idle connection from a DAEMON thread and give it five seconds. `close()` sends a
     */
    private fun closeOffThread(connection: ImapIdleConnection) {
        Thread { connection.close() }.apply { isDaemon = true; start() }.join(5_000)
    }

    /** A logged-in session on a socket the test keeps hold of. */
    private fun sessionOn(server: FakeImapServer, socket: Socket): ImapSession {
        val session = ImapSession(socket)
        session.readGreeting()
        session.login(server.config.username, server.config.password)
        return session
    }

    /** A4 — the reported symptom: the server drops the connection in the middle of an IDLE. */
    @Test(timeout = 60_000)
    fun `an IDLE the server cuts fails at once instead of spinning`() {
        idleServer(cutAfter = cutInIdle, cutDelayMs = 300).use { server ->
            val socket = Socket(server.config.host, server.config.port)
            closeAfter(socket)
            val session = sessionOn(server, socket)
            session.select("INBOX")

            val started = System.currentTimeMillis()
            val error = assertThrows(ImapException::class.java) { session.idle(60_000) }
            val elapsed = System.currentTimeMillis() - started
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertTrue(
                "idle() took ${elapsed}ms to notice a peer that had already gone — it was reading " +
                    "the end of the stream in a loop, which is the 100 %-of-a-core defect",
                elapsed < 3_000,
            )
            // The half-close is what makes this observable: the client wrote DONE at a server that
            // was no longer there, and the server still received it.
            assertTrue(
                "the client never wrote DONE; it got out of IDLE some other way: ${server.commands}",
                server.commands.any { it.endsWith("DONE") },
            )
            runCatching { session.close() }
        }
    }

    /** A5 — the owner of the push connection is told, once, so it can reconnect. */
    @Test(timeout = 60_000)
    fun `a cut during IDLE reports the connection closed exactly once and ends the thread`() {
        idleServer(cutAfter = cutInIdle, cutDelayMs = 300).use { server ->
            val closes = AtomicInteger()
            val closed = CountDownLatch(1)
            val before = liveIdleThreads()
            val connection = ImapIdleConnection(
                client = ImapClient(),
                config = server.config,
                mailbox = "INBOX",
                onChanged = {},
                onClosed = { closes.incrementAndGet(); closed.countDown() },
            )
            try {
                assertTrue(
                    "onClosed() was never called: the push connection is gone and nothing will " +
                        "reconnect it — the idle thread is looping on a dead socket instead",
                    closed.await(10, TimeUnit.SECONDS),
                )
                Thread.sleep(500) // a second call would be a second reconnection
                assertEquals("onClosed() was called more than once", 1, closes.get())
                assertTrue(
                    "the imap-idle thread outlived the connection it was serving",
                    awaitNoNewIdleThread(before),
                )
            } finally {
                closeOffThread(connection)
            }
        }
    }

    /**
     * A6 — the arbitration that must NOT regress: a close WE asked for is not a drop. `running`
     */
    @Test(timeout = 60_000)
    fun `closing the idle connection on purpose reports nothing`() {
        idleServer().use { server ->
            val closes = AtomicInteger()
            val closed = CountDownLatch(1)
            val connection = ImapIdleConnection(
                client = ImapClient(),
                config = server.config,
                mailbox = "INBOX",
                onChanged = {},
                onClosed = { closes.incrementAndGet(); closed.countDown() },
            )
            // Wait until the client is actually parked in IDLE, or the close would race the setup.
            assertTrue("the client never reached IDLE: ${server.commands}", awaitCommand(server, "IDLE"))

            closeOffThread(connection)

            assertFalse(
                "onClosed() was called for a close we asked for: the owner will reconnect a " +
                    "connection it has just shut down",
                closed.await(2, TimeUnit.SECONDS),
            )
            assertEquals(0, closes.get())
        }
    }

    /**
     * A7 — the write path. APPEND puts the sent copy in the Sent folder; the server took the
     */
    @Test(timeout = 60_000)
    fun `an APPEND whose completion never comes fails instead of spinning`() {
        FakeImapServer(
            responder = { tag, line ->
                when {
                    line.startsWith("LOGIN") -> ok(tag)
                    line.startsWith("APPEND") -> "+ go ahead\r\n"
                    else -> ok(tag)
                }
            },
            // The message's octets are taken in whole after the `+`; the cut therefore lands
            // after the data and before the tagged completion.
            cutAfter = { line -> if (line == FakeImapServer.LITERAL) emptyList() else null },
        ).use { server ->
            val socket = Socket(server.config.host, server.config.port)
            closeAfter(socket)
            val session = sessionOn(server, socket)

            val started = System.currentTimeMillis()
            val error = assertThrows(
                "append() returned normally: a copy nobody ever acknowledged was reported as saved",
                ImapException::class.java,
            ) { session.append("Sent", "Subject: sent copy\r\n\r\nENDOFBODY", "\\Seen") }
            val elapsed = System.currentTimeMillis() - started
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertTrue("append() spun for ${elapsed}ms before failing", elapsed < 3_000)
            runCatching { session.close() }
        }
    }

    /**
     * A8 — closing a session whose peer has already gone. `close()` sends LOGOUT inside a
     */
    @Test(timeout = 60_000)
    fun `closing a session whose peer has gone still closes the socket`() {
        FakeImapServer(
            responder = { tag, _ -> ok(tag) },
            cutAfter = { line -> if (line == FakeImapServer.GREETING) listOf("* OK fake IMAP ready") else null },
        ).use { server ->
            val socket = Socket(server.config.host, server.config.port)
            closeAfter(socket)
            val session = ImapSession(socket)

            val started = System.currentTimeMillis()
            session.close()
            val elapsed = System.currentTimeMillis() - started
            server.awaitDone()

            assertTrue(
                "close() took ${elapsed}ms: it was re-reading a finished stream, and the socket " +
                    "below was closed by the test's own net, not by close()",
                elapsed < 3_000,
            )
            assertTrue("close() left the socket open", socket.isClosed)
            assertTrue(
                "close() never even wrote its LOGOUT: ${server.commands}",
                server.commands.any { it.endsWith("LOGOUT") },
            )
        }
    }

    // ── helpers ──

    /** Threads named like the one [ImapIdleConnection] starts, alive right now. */
    private fun liveIdleThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "imap-idle" && it.isAlive }.toSet()

    /** True once every imap-idle thread that did not exist at [before] has finished. */
    private fun awaitNoNewIdleThread(before: Set<Thread>, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ((liveIdleThreads() - before).isEmpty()) return true
            Thread.sleep(50)
        }
        return false
    }

    /** True once [server] has received a command line starting with [prefix]. */
    private fun awaitCommand(server: FakeImapServer, prefix: String, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (server.commands.toList().any { it.startsWith(prefix) }) return true
            Thread.sleep(20)
        }
        return false
    }
}
