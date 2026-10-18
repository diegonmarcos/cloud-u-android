package com.diegonmarcos.superapp.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/**
 * Opening a saved web page archive (.mhtml / .mht) that another app, the file manager or the download
 * notification hands to the browser. The rules are pure (no android.*) so a JVM test pins them; [MhtmlOpener]
 * is the thin Android layer. WebView renders an archive from a file:// path (the same engine that already
 * writes them for the offline copies), so a content:// stream is copied into the app cache first.
 */
object MhtmlIntent {
    /** The MIME types an archive travels under (browsers: multipart/related; mail apps: message/rfc822). */
    val MIMES = listOf("multipart/related", "message/rfc822", "application/x-mimearchive")
    val EXTENSIONS = listOf(".mhtml", ".mht")

    /** Where an open request points. */
    sealed class Source {
        /** A file:// archive: WebView loads it as it is. */
        data class Load(val url: String) : Source()
        /** A content:// archive: copy [uri] into the cache, then load that file. */
        data class Copy(val uri: String) : Source()
        /** Anything else the VIEW intent carried (an http link): the url as given. */
        data class Plain(val url: String) : Source()
        object None : Source()
    }

    /** The last path segment of [uriOrName], without query or fragment. */
    fun fileName(uriOrName: String?): String =
        uriOrName.orEmpty().substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')

    fun isMhtmlName(uriOrName: String?): Boolean {
        val n = fileName(uriOrName).lowercase()
        return EXTENSIONS.any { n.endsWith(it) }
    }

    fun isMhtmlMime(mime: String?): Boolean = mime?.substringBefore(';')?.trim()?.lowercase() in MIMES

    /** True for a file:// page that is an archive: the one kind of local file the browser may read besides its own caches. */
    fun isMhtmlFileUrl(url: String?): Boolean = url?.startsWith("file://") == true && isMhtmlName(url)

    /**
     * What [action] / [data] / [stream] / [mime] ask for. SEND carries the archive in [stream]; anything else
     * in [data]. message/rfc822 alone is not enough for a file:// path (it is also what .eml files are): a
     * file needs the extension or one of the two archive-only types, while a content:// stream from a mail
     * or chat app is taken on any of the three.
     */
    fun source(action: String?, data: String?, stream: String?, mime: String?): Source {
        val send = action == ACTION_SEND
        val raw = (if (send) stream else data)?.trim()?.takeIf { it.isNotEmpty() } ?: return Source.None
        val scheme = raw.substringBefore(':', "").lowercase()
        val archiveOnly = mime?.substringBefore(';')?.trim()?.lowercase().let { it == "multipart/related" || it == "application/x-mimearchive" }
        return when (scheme) {
            "file" -> if (isMhtmlName(raw) || archiveOnly) Source.Load(raw) else if (send) Source.None else Source.Plain(raw)
            "content" -> if (isMhtmlName(raw) || isMhtmlMime(mime)) Source.Copy(raw) else if (send) Source.None else Source.Plain(raw)
            else -> if (send) Source.None else Source.Plain(raw)
        }
    }

    /** The cache file name for an archive called [displayName] at [uri]: safe characters, an archive extension, unique per uri. */
    fun cacheName(displayName: String?, uri: String): String {
        var base = fileName(displayName?.takeIf { it.isNotBlank() } ?: uri)
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifEmpty { "page" }.take(80)
        if (!isMhtmlName(base)) base += ".mhtml"
        val h = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        return "$h-$base"
    }

    /**
     * The MIME a download of an archive is queued under: servers answer .mhtml with octet-stream or text/plain,
     * and the download notification opens the file by that type, so it must be one this browser handles.
     */
    fun downloadMime(name: String?, mime: String?): String? {
        if (!isMhtmlName(name)) return mime
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (m.isEmpty() || m == "application/octet-stream" || m == "text/plain" || m == "binary/octet-stream") MIMES[0] else mime
    }

    // android.content.Intent.ACTION_SEND, spelled out so this object stays free of android.* (JVM-testable).
    const val ACTION_SEND = "android.intent.action.SEND"
}

/** The Android side of [MhtmlIntent]: the cache copy of a content:// archive and the intent reading. */
class MhtmlOpener(context: Context) {
    private val ctx = context.applicationContext
    private val dir = File(ctx.cacheDir, "mhtml")

    /** Is [url] a file the browser may read: an archive it copied into its cache, or a file:// archive it was asked to open. */
    fun allowsFile(url: String?): Boolean =
        url?.startsWith("file://" + dir.absolutePath + "/") == true || MhtmlIntent.isMhtmlFileUrl(url)

    /**
     * The url [intent] asks the browser to open, handed to [done] on the main thread (null: nothing to open).
     * A file:// archive and a plain link answer at once; a content:// archive is copied first, off the main thread.
     */
    fun open(intent: Intent?, done: (String?) -> Unit) {
        if (intent == null) return done(null)
        val stream: Uri? = if (intent.action == Intent.ACTION_SEND)
            androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
        val mime = intent.type ?: (stream ?: intent.data)?.let { u -> runCatching { ctx.contentResolver.getType(u) }.getOrNull() }
        openUri(MhtmlIntent.source(intent.action, intent.dataString, stream?.toString(), mime), mime, done)
    }

    /** Open the archive at [uri] (a download's content:// uri, say). */
    fun openUri(uri: Uri, done: (String?) -> Unit) {
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        val s = uri.toString()
        openUri(if (uri.scheme == "file") MhtmlIntent.Source.Load(s) else MhtmlIntent.Source.Copy(s), mime, done)
    }

    private fun openUri(src: MhtmlIntent.Source, mime: String?, done: (String?) -> Unit) {
        when (src) {
            is MhtmlIntent.Source.Load -> done(src.url)
            is MhtmlIntent.Source.Plain -> done(src.url)
            MhtmlIntent.Source.None -> done(null)
            is MhtmlIntent.Source.Copy -> {
                val main = Handler(Looper.getMainLooper())
                Thread {
                    val url = runCatching { copy(Uri.parse(src.uri)) }.getOrNull()
                    main.post { done(url) }
                }.start()
            }
        }
    }

    /** Copy the stream at [uri] to the cache and return its file:// url. Blocking IO. */
    private fun copy(uri: Uri): String {
        dir.mkdirs()
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val out = File(dir, MhtmlIntent.cacheName(name, uri.toString()))
        val tmp = File(dir, out.name + ".part")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        out.delete()
        if (!tmp.renameTo(out)) { tmp.delete(); error("could not place ${out.name}") }
        prune(out)
        return "file://" + out.absolutePath
    }

    /** Keep the newest few archives; the cache is a staging area, not a library (offline copies are the library). */
    private fun prune(keep: File) {
        dir.listFiles()?.filter { it != keep }?.sortedByDescending { it.lastModified() }?.drop(KEEP - 1)?.forEach { it.delete() }
    }

    private companion object { const val KEEP = 8 }
}
