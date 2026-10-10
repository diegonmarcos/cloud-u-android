package com.diegonmarcos.superapp.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import org.json.JSONObject
import java.io.File

/**
 * #887 what the browser keeps on this phone, itemised with sizes, and the clears for each item. The
 * items ([ITEMS]) are the ids Configs ▸ Data & storage lists; [clear] is one branch per id, so a row
 * without a branch cannot exist (test-browser-site-data.sh holds that). Cookies and WebView storage
 * are process-wide in Android WebView, so those clears affect every tab.
 */
object BrowserStorage {
    /** (id, label, what is in it). Order is the order shown. */
    val ITEMS = listOf(
        Triple("cache", "Cached pages and images", "The HTTP cache."),
        Triple("dom", "DOM and local storage", "localStorage, sessionStorage, Web SQL, file system. Cleared together with IndexedDB."),
        Triple("indexeddb", "IndexedDB", "Site databases. Cleared together with DOM and local storage."),
        Triple("cookies", "Cookies (all sites)", "Every site signs you out."),
        Triple("form", "WebView form data", "Entries WebView itself remembered in page forms. Not your autofill profiles or site rules: those live in Cloud Account (Autofill data, below)."),
        Triple("offline", "Offline copies", "Pages and sites saved for offline use."),
        Triple("previews", "Tab previews and icons", "Thumbnails and favicons of your tabs."),
        Triple("tabstate", "Saved tab state", "Each tab's back/forward list, kept so a tab reopens where it was."),
        Triple("downloads", "Downloads started here", "Deletes the files this browser downloaded, and the list."),
    )

    private fun size(f: File): Long = if (!f.exists()) 0L else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun webData(ctx: Context) = File(ctx.dataDir, "app_webview/Default")

    /** BLOCKING (file walks): off the main thread. */
    fun breakdown(ctx: Context): List<SiteData.Item> {
        val wd = webData(ctx)
        fun inWd(vararg names: String) = names.sumOf { size(File(wd, it)) }
        val bytes = mapOf(
            "cache" to size(File(ctx.cacheDir, "WebView")) + inWd("HTTP Cache", "Code Cache", "GPUCache"),
            "dom" to inWd("Local Storage", "Session Storage", "File System", "databases", "Service Worker"),
            "indexeddb" to inWd("IndexedDB"),
            "cookies" to inWd("Cookies", "Cookies-journal"),
            "form" to inWd("Web Data", "Web Data-journal"),
            "offline" to OfflineSites(ctx).bytes(),
            "previews" to size(File(ctx.cacheDir, "tabs")) + size(File(ctx.cacheDir, "favicons")),
            "tabstate" to BrowserWebState.bytes(ctx),
            "downloads" to runCatching { BrowserDownloads(ctx).bytes() }.getOrDefault(0L),
        )
        return ITEMS.map { (id, label, detail) -> SiteData.Item(id, label, bytes[id], detail) }
    }

    /**
     * Clear [ids] (MAIN THREAD: WebView and WebStorage refuse others). [done] gets {ok, cleared:[ids]}.
     * `dom` and `indexeddb` are one call (WebStorage clears both), so choosing either clears both.
     */
    fun clear(ctx: Context, ids: Set<String>, done: (JSONObject) -> Unit) {
        val out = JSONObject().put("ok", true).put("cleared", org.json.JSONArray(ids.sorted()))
        for (id in ids) when (id) {
            "cache" -> { WebView(ctx).apply { clearCache(true); destroy() }; File(ctx.cacheDir, "WebView").deleteRecursively() }
            "dom", "indexeddb" -> WebStorage.getInstance().deleteAllData()
            "form" -> @Suppress("DEPRECATION") WebViewDatabase.getInstance(ctx).clearFormData()
            "offline" -> OfflineSites(ctx).deleteAll()
            "previews" -> { File(ctx.cacheDir, "tabs").deleteRecursively(); File(ctx.cacheDir, "favicons").deleteRecursively(); BrowserTabPrefs(ctx).clearPreviews() }
            "tabstate" -> BrowserWebState.clearAll(ctx)
            "downloads" -> BrowserDownloads(ctx).removeAllFiles()
            "cookies" -> Unit   // asynchronous: below
            else -> out.put("ok", false).put("error", "$id: not a storage item")
        }
        if ("cookies" in ids) CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done(out) }
        else done(out)
    }

    /**
     * The per-site clear (menu ▸ Clear site data): [boxes] ⊆ BrowserClearCategories.SITE_BOXES — this
     * site's cookies and/or this site's storage (WebStorage.deleteOrigin of its own origins only). Every
     * other site keeps everything; autofill data (Cloud Account) is not part of it. MAIN THREAD.
     */
    fun clearSiteData(url: String, boxes: Set<String>, done: (JSONObject) -> Unit) {
        val host = BrowserSitePolicy.hostOf(url)
        val out = JSONObject().put("ok", host.isNotEmpty()).put("host", host)
        if (host.isEmpty()) return done(out)
        if (BrowserClearCategories.SITE_STORAGE in boxes) {
            val origins = BrowserClearCategories.siteOrigins(host)
            origins.forEach { WebStorage.getInstance().deleteOrigin(it) }
            out.put("origins", org.json.JSONArray(origins))
        }
        if (BrowserClearCategories.SITE_COOKIES in boxes) clearSiteCookies(url) { n -> done(out.put("cookies", n)) } else done(out)
    }

    /**
     * Cookies of the site [url] belongs to: the names Android holds for the host and its parent
     * domains are expired one by one ([CookieScope]); other sites keep theirs. [done] gets how many names it expired.
     */
    fun clearSiteCookies(url: String, done: (Int) -> Unit) {
        val host = BrowserSitePolicy.hostOf(url)
        if (host.isEmpty()) return done(0)
        val cm = CookieManager.getInstance()
        val names = CookieScope.hosts(host).flatMap { h ->
            listOf("https://$h/", "http://$h/").flatMap { u -> CookieScope.names(cm.getCookie(u)) }
        }.distinct()
        for (secure in listOf(true, false)) CookieScope.expiries(host, names, secure).forEach { (u, c) -> cm.setCookie(u, c) }
        cm.flush()
        done(names.size)
    }
}
