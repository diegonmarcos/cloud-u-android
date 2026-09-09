package app.sterna.core.imap

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * A one-connection, scripted IMAP server on loopback, so the folder walk can be driven end to
 */
internal class FakeImapServer(
    /**
     * Write these lines and then **stop answering, for good** — the IMAP twin of
     */
    private val cutAfter: (String) -> List<String>? = { null },
    /**
     * The silence between the last line [cutAfter] writes and the half-close: the server that
     */
    private val cutDelayMs: Long = 0,
    /**
     * What the server says once it has taken in a LITERAL (`APPEND … {N}`): called with the tag
     */
    private val afterLiteral: (tag: String, line: String, literal: ByteArray) -> String = { tag, _, _ -> ok(tag) },
    /**
     * LAST on purpose, and it must stay last: every existing call site writes
     */
    private val responder: (tag: String, line: String) -> String,
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** Every command line received, tag stripped, in order. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /**
     * Every literal received, in order, BYTE FOR BYTE: what the client put on the wire after the
     */
    val literals: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf<ByteArray>())

    val config = MailServerConfig(
        host = server.inetAddress.hostAddress,
        port = server.localPort,
        security = MailSecurity.NONE,
        username = "tester",
        password = "secret",
    )

    /**
     * The accepted connection, so [close] can end it. Interrupting the serving thread does NOT
     * unblock a socket read, and a cut session spends its last seconds in one.
     */
    @Volatile
    private var accepted: java.net.Socket? = null

    private val thread = Thread(::serve, "fake-imap").apply { isDaemon = true; start() }

    private fun serve() {
        runCatching {
            server.accept().use { socket ->
                accepted = socket
                // Asymmetric on purpose. READING is UTF-8 because that is what the client writes
                // (a `UID SEARCH CHARSET UTF-8 SUBJECT "école"` really does go out as UTF-8, and
                //
                // The reading side is a byte stream with a line reader of its own rather than a
                // BufferedReader: a literal (`APPEND … {N}` followed by N octets) has to be taken
                // off the SAME stream as raw bytes, and a Reader would have decoded them — and
                // read ahead past them — before a test could look at them.
                val input = BufferedInputStream(socket.inputStream)
                val writer = OutputStreamWriter(socket.outputStream, Charsets.ISO_8859_1)

                /** One line, CRLF (or LF) stripped, decoded as UTF-8; null at end of stream. */
                fun readLine(): String? {
                    val buf = ByteArrayOutputStream()
                    while (true) {
                        val b = input.read()
                        if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
                        if (b == '\n'.code) break
                        buf.write(b)
                    }
                    val bytes = buf.toByteArray()
                    val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                    return String(bytes, 0, end, Charsets.UTF_8)
                }

                /** Exactly [n] octets, as they are. */
                fun readLiteral(n: Int): ByteArray {
                    val out = ByteArray(n)
                    var off = 0
                    while (off < n) {
                        val r = input.read(out, off, n - off)
                        if (r < 0) throw EOFException("stream ended $off bytes into a $n-byte literal")
                        off += r
                    }
                    return out
                }

                /**
                 * Honour [cutAfter] for [trigger]: write the partial response, go quiet for
                 */
                fun cut(trigger: String): Boolean {
                    val partial = cutAfter(trigger) ?: return false
                    partial.forEach { writer.write("$it\r\n") }
                    writer.flush()
                    if (cutDelayMs > 0) Thread.sleep(cutDelayMs)
                    runCatching { socket.shutdownOutput() }
                    runCatching { socket.soTimeout = QUIET_MS }
                    while (true) {
                        val after = runCatching { readLine() }.getOrNull() ?: break
                        commands.add(after.substringAfter(' ')) // tag stripped, as above: see [cutAfter]
                    }
                    return true
                }

                if (cut(GREETING)) return@use
                writer.write("* OK fake IMAP ready\r\n")
                writer.flush()
                while (true) {
                    val raw = readLine() ?: break
                    val tag = raw.substringBefore(' ')
                    val line = raw.substringAfter(' ')
                    commands.add(line)
                    if (cut(line)) break
                    val answer = responder(tag, line)
                    writer.write(answer)
                    writer.flush()
                    val size = literalSize(line)
                    if (size != null && answer.startsWith("+")) {
                        // The client sends the literal's octets and then the CRLF that ends the
                        // command line the literal was part of; that empty tail is not a command.
                        val literal = readLiteral(size)
                        literals.add(literal)
                        readLine()
                        if (cut(LITERAL)) break
                        writer.write(afterLiteral(tag, line, literal))
                        writer.flush()
                    }
                    if (line.startsWith("LOGOUT")) break
                }
            }
        }
    }

    /** Open a logged-in session against this server. */
    fun session(): ImapSession = ImapClient().openSession(config)

    /** Command lines received so far, excluding the LOGIN (whose arguments are credentials). */
    fun issued(): List<String> = commands.toList().filterNot { it.startsWith("LOGIN") }

    /**
     * Block until the served connection is finished. After a cut this is what makes an assertion
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
        // untouched, and a cut session's serving thread is parked on a read of it.
        runCatching { accepted?.close() }
        thread.interrupt()
    }

    companion object {
        /** [cutAfter] trigger for the server banner, which no command of the client precedes. */
        const val GREETING = "<greeting>"

        /** [cutAfter] trigger for the moment a literal's octets have all been taken in. */
        const val LITERAL = "<literal>"

        /** How long a cut connection keeps listening for a client that has not noticed yet. */
        private const val QUIET_MS = 1_500

        /** The `{N}` a command line ends with when a literal follows it, or null. */
        fun literalSize(line: String): Int? =
            Regex("""\{(\d+)\}$""").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }
}

/** A canned OK response with no untagged lines. */
internal fun ok(tag: String) = "$tag OK done\r\n"

/**
 * A canned SELECT response for a folder holding [exists] messages, announcing [uidValidity] as
 * its numbering — the value a renumbering test moves (Codeberg #99).
 */
internal fun selectResponse(tag: String, exists: Int = 10, uidValidity: Long = 1L): String =
    "* $exists EXISTS\r\n* OK [UIDVALIDITY $uidValidity] ok\r\n* OK [UIDNEXT ${exists + 1}] ok\r\n" +
        "$tag OK [READ-WRITE] selected\r\n"

/** A canned `UID SEARCH` result listing [uids]. */
internal fun searchResponse(tag: String, uids: List<Long>): String =
    "* SEARCH ${uids.joinToString(" ")}\r\n$tag OK search completed\r\n"

/**
 * A canned `UID FETCH` result: one envelope per requested uid, with an attachment part in the
 */
internal fun fetchResponse(
    tag: String,
    uids: List<Long>,
    flags: (Long) -> String = { "\\Seen" },
    withAttachment: (Long) -> Boolean,
): String {
    val body = uids.joinToString("") { uid ->
        val flagList = flags(uid)
        val structure = if (withAttachment(uid)) {
            """(("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)""" +
                """("application" "pdf" ("name" "f.pdf") NIL NIL "base64" 900 NIL """ +
                """("attachment" ("filename" "f.pdf")) NIL) "mixed")"""
        } else {
            """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)"""
        }
        // DISTINCT addresses in five of the envelope's six address slots — from (2), sender (3),
        // reply-to (4), to (5) and cc (6); only bcc (7) stays NIL, as a server sends it. They used
        "* $uid FETCH (UID $uid FLAGS ($flagList) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:0${uid % 10} +0000\" \"Message $uid\" " +
            "((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\")) " +
            "((\"Bounces\" NIL \"bounces\" \"masto.top\")) " +
            "((\"Support\" NIL \"support\" \"masto.top\")) " +
            "((\"Team\" NIL \"team\" \"masto.top\")) " +
            "((\"Copy Cat\" NIL \"copy\" \"masto.top\")) NIL NIL \"<$uid@masto.top>\") " +
            "BODYSTRUCTURE $structure)\r\n"
    }
    return body + "$tag OK fetch completed\r\n"
}

/** The UIDs named in a `UID FETCH <set> (...)` command line. */
internal fun uidsOf(fetchCommand: String): List<Long> =
    fetchCommand.removePrefix("UID FETCH ").substringBefore(' ')
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
