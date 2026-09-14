package com.diegonmarcos.superapp.webserver

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A self-contained static HTTP/1.1 server.
 *
 * One accept thread, a small worker pool, a document root, and honest
 * counters. No framework, no dependency: the whole engine is this file, so a
 * consumer that wants a web server gets exactly one module and one object.
 *
 * Design notes worth keeping:
 *  - [Stats.lastError] exists because a listener that failed to bind and a
 *    listener that is merely idle must never look alike on a status screen.
 *    The UI reads the error out of here instead of inferring "probably fine".
 *  - Path resolution canonicalises and then checks containment in the
 *    document root. A request for `/../../databases/x.db` resolves to a real
 *    file outside the root and must be refused, not served.
 *  - Binding is loopback-only by default. Reaching the LAN is a decision the
 *    user makes explicitly, and the status screen says which one is in force.
 */
class WebServer(@Volatile var config: Config) {

    /**
     * @param port             TCP port to listen on.
     * @param docRoot          directory served as `/`.
     * @param loopbackOnly     bind 127.0.0.1 (true) or every interface (false).
     * @param directoryListing render an index for a directory that has no
     *                         index.html, instead of answering 403.
     */
    data class Config(
        val port: Int,
        val docRoot: File,
        val loopbackOnly: Boolean = true,
        val directoryListing: Boolean = true,
    )

    /** Everything a status surface needs, read without touching the socket. */
    data class Stats(
        val running: Boolean,
        val boundHost: String?,
        val port: Int,
        val docRoot: String,
        val loopbackOnly: Boolean,
        val startedAtMillis: Long,
        val uptimeMillis: Long,
        val requests: Long,
        val bytesServed: Long,
        val filesInRoot: Int,
        val recent: List<String>,
        val lastError: String?,
    )

    private val running = AtomicBoolean(false)
    private val requests = AtomicLong(0)
    private val bytesServed = AtomicLong(0)
    private val recent = ArrayDeque<String>()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers = Executors.newCachedThreadPool()
    private var startedAt = 0L

    @Volatile private var lastError: String? = null

    fun isRunning(): Boolean = running.get()

    /**
     * Bind and start accepting. Idempotent: a second call while running is a
     * no-op. A bind failure is recorded in [Stats.lastError] and leaves the
     * server stopped — it does not throw into the caller's UI thread.
     */
    fun start() {
        if (running.get()) return
        val cfg = config
        lastError = null
        try {
            cfg.docRoot.mkdirs()
            val bind = if (cfg.loopbackOnly) InetAddress.getByName("127.0.0.1") else null
            val socket = ServerSocket(cfg.port, BACKLOG, bind)
            socket.reuseAddress = true
            server = socket
            startedAt = System.currentTimeMillis()
            requests.set(0)
            bytesServed.set(0)
            synchronized(recent) { recent.clear() }
            running.set(true)
            if (workers.isShutdown) workers = Executors.newCachedThreadPool()
            acceptThread = Thread({ acceptLoop(socket) }, "WebServer-${cfg.port}").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            lastError = "${error.javaClass.simpleName}: ${error.message ?: "no detail"}"
            Log.w(TAG, "bind failed on port ${cfg.port}", error)
            running.set(false)
            server = null
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread = null
        workers.shutdownNow()
    }

    /** Restart under a new configuration, preserving the running/stopped state. */
    fun reconfigure(next: Config) {
        val wasRunning = running.get()
        if (wasRunning) stop()
        config = next
        if (wasRunning) start()
    }

    fun stats(): Stats {
        val cfg = config
        val bound = server?.inetAddress?.hostAddress
        return Stats(
            running = running.get(),
            boundHost = if (running.get()) (bound ?: if (cfg.loopbackOnly) "127.0.0.1" else "0.0.0.0") else null,
            port = cfg.port,
            docRoot = cfg.docRoot.absolutePath,
            loopbackOnly = cfg.loopbackOnly,
            startedAtMillis = startedAt,
            uptimeMillis = if (running.get()) System.currentTimeMillis() - startedAt else 0L,
            requests = requests.get(),
            bytesServed = bytesServed.get(),
            filesInRoot = countFiles(cfg.docRoot),
            recent = synchronized(recent) { recent.toList() },
            lastError = lastError,
        )
    }

    /**
     * Write a minimal landing page if the document root holds nothing. A
     * server whose first request is a 404 reads as broken; this makes the
     * very first visit prove the engine works.
     */
    fun seedDocRoot() {
        val root = config.docRoot
        root.mkdirs()
        val index = File(root, "index.html")
        if (index.exists()) return
        runCatching {
            index.writeText(
                """
                <!doctype html>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Cloud WebServer</title>
                <style>
                  body { font-family: system-ui, sans-serif; margin: 2rem; line-height: 1.5; }
                  code { background: #eee; padding: .1rem .3rem; border-radius: .2rem; }
                </style>
                <h1>Cloud WebServer</h1>
                <p>This device is serving <code>${root.absolutePath}</code>.</p>
                <p>Drop files in that directory and they appear here.
                   Live counters: <a href="/__status">/__status</a>.</p>
                """.trimIndent(),
            )
        }
    }

    // ── accept / dispatch ────────────────────────────────────────────────

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (error: Exception) {
                if (running.get()) lastError = "accept: ${error.javaClass.simpleName}"
                break
            }
            runCatching { workers.execute { handle(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        var line = "?"
        var status = 500
        var sent = 0L
        try {
            client.getInputStream().use { input ->
                BufferedOutputStream(client.getOutputStream()).use { output ->
                    val request = readRequestLine(input) ?: return
                    line = request
                    val parts = request.split(' ')
                    val method = parts.getOrElse(0) { "" }
                    val target = parts.getOrElse(1) { "/" }
                    val path = decode(target.substringBefore('?'))
                    val result = when {
                        method != "GET" && method != "HEAD" ->
                            respond(output, 405, "text/plain; charset=utf-8", "405 Method Not Allowed\n".toByteArray(), method != "HEAD")
                        path == "/__status" ->
                            respond(output, 200, "application/json; charset=utf-8", statusJson().toByteArray(), method != "HEAD")
                        else -> serveStatic(output, path, method != "HEAD")
                    }
                    status = result.first
                    sent = result.second
                }
            }
        } catch (error: Exception) {
            lastError = "${error.javaClass.simpleName}: ${error.message ?: "no detail"}"
        } finally {
            runCatching { client.close() }
            requests.incrementAndGet()
            bytesServed.addAndGet(sent)
            note("$line → $status (${sent}B)")
        }
    }

    // ── static file serving ──────────────────────────────────────────────

    /** @return the status code and the number of body bytes written. */
    private fun serveStatic(output: BufferedOutputStream, path: String, withBody: Boolean): Pair<Int, Long> {
        val root = config.docRoot.canonicalFile
        val target = File(root, path.trimStart('/')).canonicalFile
        // Containment check, not a string prefix on the raw path: the
        // canonical form is what the filesystem will actually open.
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            return respond(output, 403, TEXT, "403 Forbidden\n".toByteArray(), withBody)
        }
        if (!target.exists()) {
            return respond(output, 404, TEXT, "404 Not Found\n".toByteArray(), withBody)
        }
        if (target.isDirectory) {
            val index = File(target, "index.html")
            if (index.isFile) return sendFile(output, index, withBody)
            if (!config.directoryListing) {
                return respond(output, 403, TEXT, "403 Forbidden\n".toByteArray(), withBody)
            }
            return respond(output, 200, HTML, listing(root, target).toByteArray(), withBody)
        }
        return sendFile(output, target, withBody)
    }

    private fun sendFile(output: BufferedOutputStream, file: File, withBody: Boolean): Pair<Int, Long> {
        val length = file.length()
        writeHeaders(output, 200, mimeOf(file.name), length)
        if (!withBody) { output.flush(); return 200 to 0L }
        var written = 0L
        file.inputStream().use { stream ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                written += read
            }
        }
        output.flush()
        return 200 to written
    }

    private fun listing(root: File, dir: File): String {
        val relative = dir.path.removePrefix(root.path).ifEmpty { "/" }
        val rows = (dir.listFiles() ?: emptyArray())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .joinToString("\n") { entry ->
                val name = escape(entry.name) + if (entry.isDirectory) "/" else ""
                val size = if (entry.isDirectory) "—" else "${entry.length()} B"
                """<tr><td><a href="${escape(entry.name)}${if (entry.isDirectory) "/" else ""}">$name</a></td><td>$size</td></tr>"""
            }
        return """
            <!doctype html>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Index of ${escape(relative)}</title>
            <style>
              body { font-family: system-ui, sans-serif; margin: 1.5rem; }
              table { border-collapse: collapse; width: 100%; }
              td { padding: .35rem .5rem; border-bottom: 1px solid #ddd; }
              td:last-child { text-align: right; color: #666; white-space: nowrap; }
            </style>
            <h1>Index of ${escape(relative)}</h1>
            <table>
            <tr><td><a href="..">../</a></td><td>—</td></tr>
            $rows
            </table>
        """.trimIndent()
    }

    // ── HTTP primitives ──────────────────────────────────────────────────

    private fun readRequestLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (builder.length < MAX_REQUEST_LINE) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) break
            if (byte != '\r'.code) builder.append(byte.toChar())
        }
        return builder.toString().ifBlank { null }
    }

    private fun respond(
        output: BufferedOutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        withBody: Boolean,
    ): Pair<Int, Long> {
        writeHeaders(output, status, contentType, body.size.toLong())
        if (withBody) output.write(body)
        output.flush()
        return status to (if (withBody) body.size.toLong() else 0L)
    }

    private fun writeHeaders(output: BufferedOutputStream, status: Int, contentType: String, length: Long) {
        val reason = when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray())
    }

    private fun statusJson(): String {
        val snapshot = stats()
        return """
            {"running":${snapshot.running},"port":${snapshot.port},
            "boundHost":${quote(snapshot.boundHost)},"docRoot":${quote(snapshot.docRoot)},
            "loopbackOnly":${snapshot.loopbackOnly},"uptimeMillis":${snapshot.uptimeMillis},
            "requests":${snapshot.requests},"bytesServed":${snapshot.bytesServed},
            "filesInRoot":${snapshot.filesInRoot},"lastError":${quote(snapshot.lastError)}}
        """.trimIndent().replace("\n", "")
    }

    private fun note(entry: String) {
        synchronized(recent) {
            recent.addFirst(entry)
            while (recent.size > RECENT_KEPT) recent.removeLast()
        }
    }

    private fun countFiles(dir: File): Int = runCatching {
        dir.walkTopDown().maxDepth(FILE_COUNT_DEPTH).count { it.isFile }
    }.getOrDefault(0)

    private fun decode(path: String): String =
        runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun quote(value: String?): String =
        if (value == null) "null" else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val TAG = "WebServer"
        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val COPY_BUFFER = 16 * 1024
        private const val MAX_REQUEST_LINE = 8 * 1024
        private const val RECENT_KEPT = 20
        private const val FILE_COUNT_DEPTH = 6
        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"

        /** Extension to media type. Deliberately short: the set a phone
         *  actually serves, plus a safe binary default. */
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> HTML
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "txt", "log", "md" -> TEXT
            "xml" -> "application/xml; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "wasm" -> "application/wasm"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
