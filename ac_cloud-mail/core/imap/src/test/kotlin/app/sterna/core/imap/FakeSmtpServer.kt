package app.sterna.core.imap

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * A one-connection, scripted SMTP submission server on loopback — the `SmtpClient` sibling of
 */
internal class FakeSmtpServer(
    /** The `AUTH` capability to advertise in the EHLO response (e.g. `"AUTH PLAIN LOGIN"`), or none. */
    private val authLine: String? = null,
    /** When true every canned reply goes out as a multi-line ESMTP response (`250-…` then `250 …`). */
    private val multiline: Boolean = false,
    /**
     * Full override for one command: a non-null result is written verbatim (one entry per line,
     */
    private val respond: (String) -> List<String>? = { null },
    /**
     * The greeting, called once the connection is accepted. `null` (the default) writes the canned
     */
    private val greeting: (() -> List<String>?)? = null,
    /**
     * Write these lines and then **stop answering, for good** — the one thing [respond] cannot do.
     */
    private val cutAfter: (String) -> List<String>? = { null },
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** Every command line received, in order, exactly as written by the client. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /**
     * The DATA payload of the accepted message, or null when nothing was accepted.
     */
    @Volatile
    var delivered: String? = null
        private set

    /** Every byte the client wrote, in order, before any line-ending interpretation — see [wire]. */
    private val wireBytes = ByteArrayOutputStream()

    /**
     * The raw octets the client put on the socket, greeting to `QUIT`, exactly as written.
     */
    val wire: ByteArray get() = synchronized(wireBytes) { wireBytes.toByteArray() }

    val config = MailServerConfig(
        host = server.inetAddress.hostAddress,
        port = server.localPort,
        security = MailSecurity.NONE,
        username = USER,
        // Long on purpose: base64 of it, and of the SASL PLAIN blob, both exceed 76 characters, so a
        // line-wrapping encoder (OutgoingMime's getMimeEncoder) would split the command in two and
        // the sequence assertions would see the halves.
        password = PASSWORD,
    )

    /**
     * The accepted connection, so [close] can end it. Interrupting the serving thread does NOT
     * unblock a socket read, and the mute path spends its whole life in one.
     */
    @Volatile
    private var accepted: java.net.Socket? = null

    private val thread = Thread(::serve, "fake-smtp").apply { isDaemon = true; start() }

    private fun serve() {
        runCatching {
            server.accept().use { socket ->
                accepted = socket
                // The tee sits UNDER the reader, so what is recorded is what the client wrote and
                // not what the reader made of it. Nothing else in the dialogue changes.
                val reader = BufferedReader(InputStreamReader(TeeInputStream(socket.inputStream), Charsets.UTF_8))
                // ISO-8859-1 so a scripted reply goes out byte for byte, as in FakeImapServer.
                val writer = OutputStreamWriter(socket.outputStream, Charsets.ISO_8859_1)
                fun reply(lines: List<String>) {
                    lines.forEach { writer.write("$it\r\n") }
                    writer.flush()
                }

                /**
                 * Honour [cutAfter] for [trigger]: write the partial response, half-close, then keep
                 */
                fun cut(trigger: String): Boolean {
                    val partial = cutAfter(trigger) ?: return false
                    reply(partial)
                    runCatching { socket.shutdownOutput() }
                    runCatching { socket.soTimeout = QUIET_MS }
                    while (true) {
                        val after = runCatching { reader.readLine() }.getOrNull() ?: break
                        commands.add(after)
                    }
                    return true
                }

                if (cut(GREETING)) return@use
                val scriptedGreeting = greeting
                val hello = if (scriptedGreeting == null) canned("220", "fake ESMTP ready") else scriptedGreeting()
                if (hello == null) {
                    // Accepted and mute: hold the connection open, saying nothing, until the client
                    // gives up on its own and closes — which is the whole point of the case.
                    runCatching { socket.soTimeout = MUTE_HOLD_MS }
                    while (true) {
                        val after = runCatching { reader.readLine() }.getOrNull() ?: break
                        commands.add(after)
                    }
                    return@use
                }
                reply(hello)
                var loginStep = 0 // 1 = awaiting the base64 user, 2 = awaiting the base64 password
                var inData = false
                val body = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (inData) {
                        if (line == ".") {
                            inData = false
                            // A cut here queues nothing: the server never got to confirm.
                            if (cut(END_OF_DATA)) break
                            delivered = body.toString()
                            // Scriptable like any command, so a test can make the acknowledgement
                            // slow: the server queueing, signing and scanning the message is the
                            // one wait a submission must survive.
                            reply(respond(END_OF_DATA) ?: canned("250", "2.0.0 queued as FAKE1"))
                        } else {
                            body.append(line).append("\r\n")
                        }
                        continue
                    }
                    commands.add(line)
                    if (cut(line)) break
                    val scripted = respond(line)
                    if (scripted != null) {
                        reply(scripted)
                        continue
                    }
                    val response = when {
                        line.startsWith("EHLO", ignoreCase = true) -> ehloReply()
                        line.equals("AUTH LOGIN", ignoreCase = true) -> {
                            loginStep = 1
                            listOf("334 VXNlcm5hbWU6")
                        }
                        line.startsWith("AUTH PLAIN", ignoreCase = true) -> canned("235", "2.7.0 authenticated")
                        loginStep == 1 -> {
                            loginStep = 2
                            listOf("334 UGFzc3dvcmQ6")
                        }
                        loginStep == 2 -> {
                            loginStep = 0
                            canned("235", "2.7.0 authenticated")
                        }
                        line.startsWith("MAIL FROM", ignoreCase = true) -> canned("250", "2.1.0 sender ok")
                        line.startsWith("RCPT TO", ignoreCase = true) -> canned("250", "2.1.5 recipient ok")
                        line.equals("DATA", ignoreCase = true) -> {
                            inData = true
                            // Canned like every other reply, so [multiline] reaches it too: a real
                            // server's answer to DATA may be multi-line, and a literal here made
                            // that the ONE response no test could give more than a single line.
                            canned("354", "end with <CRLF>.<CRLF>")
                        }
                        line.equals("QUIT", ignoreCase = true) -> listOf("221 2.0.0 bye")
                        else -> canned("250", "2.0.0 ok")
                    }
                    reply(response)
                    if (line.equals("QUIT", ignoreCase = true)) break
                }
            }
        }
    }

    /** Copies every byte read through it into [wireBytes], and changes nothing else. */
    private inner class TeeInputStream(source: InputStream) : FilterInputStream(source) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) synchronized(wireBytes) { wireBytes.write(b) }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) synchronized(wireBytes) { wireBytes.write(b, off, n) }
            return n
        }
    }

    /** The EHLO response: greeting line, the advertised AUTH capability when there is one, HELP. */
    private fun ehloReply(): List<String> = buildList {
        add("250-fake.local greets you")
        authLine?.let { add("250-$it") }
        add("250 HELP")
    }

    private fun canned(code: String, text: String): List<String> =
        if (multiline) listOf("$code-$text", "$code-still $text", "$code $text") else listOf("$code $text")

    /**
     * Commands received so far, minus `QUIT` — the client writes it after the send has already
     * returned, so whether it arrived before the assertions is a race, and it proves nothing.
     */
    fun issued(): List<String> = commands.toList().filterNot { it.equals("QUIT", ignoreCase = true) }

    /**
     * Block until the served connection is finished. After a cut this is what makes [issued]
     */
    fun awaitDone(timeoutMs: Long = 10_000) {
        thread.join(timeoutMs)
        check(!thread.isAlive) {
            "the fake server was still serving after ${timeoutMs}ms — the assertions below would " +
                "have read a list still being written; commands so far: ${commands.toList()}"
        }
    }

    override fun close() {
        runCatching { server.close() }
        // The accepted socket too: closing the LISTENING socket leaves an established connection
        // untouched, and the serving thread of a mute session is parked on a read of it.
        runCatching { accepted?.close() }
        thread.interrupt()
    }

    companion object {
        /**
         * An address, not a bare word, and that is the whole point: a real SMTP username is an
         */
        const val USER = "tester@example.org"
        const val PASSWORD = "correct-horse-battery-staple-correct-horse-battery-staple-0123456789"

        /** [cutAfter] trigger for the server greeting, which no command of the client precedes. */
        const val GREETING = "<greeting>"

        /** [cutAfter] trigger for the reply to the dot that ends the DATA body. */
        const val END_OF_DATA = "."

        /** How long a cut connection keeps listening for a client that has not noticed yet. */
        private const val QUIET_MS = 1_500

        /** How long a mute server holds a connection open before letting go of it. */
        private const val MUTE_HOLD_MS = 30_000
    }
}

/** A message fixture for the submission tests. */
internal fun outgoingFixture(subject: String = "hello there") = OutgoingMessage(
    from = "Tester <tester@example.org>",
    to = listOf("dest@example.org"),
    subject = subject,
    body = "body text",
    messageId = "mid-1@example.org",
    dateMillis = 1_750_000_000_000L,
)
