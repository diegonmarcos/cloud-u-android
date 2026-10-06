package com.diegonmarcos.superapp.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.security.MessageDigest

/**
 * #887 offline copies of pages and sites. Each copy is a folder under filesDir/offline/<id>/ of MHTML
 * archives (WebView's own saveWebArchive: the page WITH its images, styles and scripts in one file) and
 * one index.json for all copies. Nothing here is a second HTTP stack: a site is saved by loading its pages
 * in a hidden WebView (the same cookies, user agent and engine the browsing uses) and archiving each.
 * The rules (scope, limits) are [SiteScope] / [SiteCrawl], JVM-tested.
 */
class OfflineSites(context: Context) {
    private val ctx = context.applicationContext
    val root: File = File(ctx.filesDir, "offline").apply { mkdirs() }
    private val indexFile = File(root, "index.json")

    fun list(): List<OfflineSite> = OfflineIndex.fromJson(runCatching { indexFile.readText() }.getOrNull())

    private fun write(sites: List<OfflineSite>) {
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(OfflineIndex.toJson(sites))
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    fun add(site: OfflineSite) = write(listOf(site) + list().filterNot { it.id == site.id })

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        write(OfflineIndex.remove(list(), id))
    }

    fun deleteAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        write(emptyList())
    }

    /** Bytes on disk for every copy, counted from the files (the index is the label, the disk the truth). */
    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun fileUrl(p: OfflinePage): String = "file://" + File(root, p.file).absolutePath

    /** Is [url] a page of a copy (a file under [root])? */
    fun isOffline(url: String?): Boolean = url?.startsWith("file://" + root.absolutePath) == true

    /** The local file URL of the saved copy of [url], or null when no copy holds it. */
    fun resolve(url: String): String? = OfflineIndex.find(list(), url)?.second?.let { fileUrl(it) }

    fun newId(origin: String, ts: Long): String =
        MessageDigest.getInstance("SHA-256").digest("$origin|$ts".toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
}

/** What a running save reports. */
data class SaveProgress(
    val pages: Int, val bytes: Long, val pending: Int, val current: String, val limits: CrawlLimits,
    val done: Boolean = false, val stopped: String = "", val error: String = "",
)

/**
 * One site save: breadth-first from [startUrl] inside its origin ([SiteCrawl]), each page loaded in a
 * hidden WebView, archived, and its links offered to the queue. MAIN THREAD only (WebView). [cancel]
 * keeps what was saved so far. One at a time: [active].
 */
class OfflineSiteJob(
    private val ctx: Context,
    private val store: OfflineSites,
    private val startUrl: String,
    private val startTitle: String,
    private val limits: CrawlLimits,
    private val userAgent: String?,
    private val settleMs: Long,
    private val onProgress: (SaveProgress) -> Unit,
    private val onDone: (OfflineSite?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val crawl = SiteCrawl(startUrl, limits)
    private val id = store.newId(SiteScope.origin(startUrl), System.currentTimeMillis())
    private val ts = System.currentTimeMillis()
    private val saved = ArrayList<OfflinePage>()
    private var hidden: WebView? = null
    private var cancelled = false
    private var finished = false

    fun start() {
        hidden = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            userAgent?.let { settings.userAgentString = it }
        }
        step()
    }

    fun cancel() { cancelled = true; finish("stopped by you") }

    private fun report(current: String, stopped: String = "", done: Boolean = false, error: String = "") =
        onProgress(SaveProgress(crawl.pages, crawl.bytes, crawl.pending, current, limits, done, stopped, error))

    private fun step() {
        if (finished) return
        val item = crawl.next()
        if (item == null) return finish(crawl.stopReason.orEmpty())
        report(item.url)
        val h = hidden ?: return finish("")
        var handled = false
        val watchdog = Runnable { if (!handled) { handled = true; h.stopLoading(); capture(h, item) } }
        h.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (handled || url == null || url == "about:blank") return
                handled = true
                main.removeCallbacks(watchdog)
                h.postDelayed({ capture(h, item) }, settleMs)
            }
        }
        main.postDelayed(watchdog, PAGE_TIMEOUT_MS)
        h.loadUrl(item.url)
    }

    private fun capture(h: WebView, item: SiteCrawl.Item) {
        if (finished) return
        val finalUrl = h.url.orEmpty()
        // A redirect that left the site is not part of it.
        if (!SiteScope.sameOrigin(startUrl, finalUrl)) return step()
        val name = "p${saved.size}.mhtml"
        val file = java.io.File(store.dir(id), name)
        h.saveWebArchive(file.absolutePath, false) { path ->
            if (finished) return@saveWebArchive
            if (path != null && file.isFile) {
                val size = file.length()
                saved.add(OfflinePage(SiteScope.normalize(finalUrl) ?: item.url, "$id/$name", size, h.title.orEmpty()))
                crawl.saved(size)
                h.evaluateJavascript(BrowserPageActions.script(ctx, "offline_links")) { raw ->
                    val links = BrowserPageActions.decode(raw)?.optJSONArray("links")
                    crawl.offer(item.depth, if (links == null) emptyList() else (0 until links.length()).map { links.optString(it) })
                    step()
                }
            } else step()
        }
    }

    private fun finish(stopped: String) {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        hidden?.let { runCatching { it.stopLoading(); it.destroy() } }; hidden = null
        val site = if (saved.isEmpty()) { store.dir(id).deleteRecursively(); null } else
            OfflineSite(id, saved.first().title.ifBlank { startTitle }.ifBlank { SiteScope.origin(startUrl) }, SiteScope.origin(startUrl),
                startUrl, ts, saved.toList(), "site", stopped).also { store.add(it) }
        report("", stopped, done = true, error = if (site == null) "nothing could be saved" else "")
        onDone(site)
    }

    companion object {
        private const val PAGE_TIMEOUT_MS = 25_000L
        @Volatile var active: OfflineSiteJob? = null
    }
}

/** #887 "Save this page" (a single MHTML of the page as it is now, signed-in state and all). MAIN THREAD. */
object OfflinePageSaver {
    fun save(store: OfflineSites, wv: WebView, done: (OfflineSite?, String?) -> Unit) {
        val url = wv.url.orEmpty()
        val origin = SiteScope.origin(url)
        if (origin.isEmpty()) return done(null, "only http(s) pages can be saved")
        val ts = System.currentTimeMillis()
        val id = store.newId(origin, ts)
        val file = java.io.File(store.dir(id), "p0.mhtml")
        val title = wv.title.orEmpty()
        wv.saveWebArchive(file.absolutePath, false) { path ->
            if (path == null || !file.isFile) { store.dir(id).deleteRecursively(); return@saveWebArchive done(null, "WebView could not archive this page") }
            val site = OfflineSite(id, title.ifBlank { url }, origin, url, ts,
                listOf(OfflinePage(SiteScope.normalize(url) ?: url, "$id/p0.mhtml", file.length(), title)), "page")
            store.add(site)
            done(site, null)
        }
    }
}
