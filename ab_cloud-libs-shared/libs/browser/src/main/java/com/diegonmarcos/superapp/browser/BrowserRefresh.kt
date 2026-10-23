package com.diegonmarcos.superapp.browser

import android.webkit.WebView
import org.json.JSONTokener

/** #1141 page scripts that need an answer from a Promise, and the Hard / Light refresh built on them. MAIN THREAD. */
object BrowserJs {
    private const val POLL_MS = 100L
    private const val POLL_MAX = 30

    /**
     * evaluateJavascript does not wait for a Promise, so [expr] (an expression, possibly a Promise) parks its JSON
     * answer on `window` and this polls for it. [done] gets the answer's JSON text, or null when the page gave none in ~3 s.
     */
    fun await(wv: WebView, expr: String, done: (String?) -> Unit) {
        val key = "__c3_" + System.nanoTime()
        val start = "(function(){window['$key']=null;Promise.resolve().then(function(){return ($expr)})" +
            ".then(function(v){window['$key']=JSON.stringify({v:v===undefined?null:v})},function(e){window['$key']=JSON.stringify({error:String(e)})});return 1})()"
        wv.evaluateJavascript(start, null)
        var tries = 0
        lateinit var poll: Runnable
        poll = Runnable {
            wv.evaluateJavascript("(function(){var r=window['$key'];if(r!==null&&r!==undefined)delete window['$key'];return r})()") { raw ->
                val s = unquote(raw)
                if (s != null) done(s)
                else if (++tries >= POLL_MAX) done(null)
                else wv.postDelayed(poll, POLL_MS)
            }
        }
        wv.postDelayed(poll, POLL_MS)
    }

    /** evaluateJavascript answers a JSON-encoded value; a string result is unwrapped, `null` / undefined is null. */
    fun unquote(raw: String?): String? = runCatching {
        if (raw == null || raw == "null") null else JSONTokener(raw).nextValue() as? String
    }.getOrNull()
}

object BrowserRefresh {
    /** Page-side half of a refresh: what only the page itself can clear for its own origin. */
    fun pageScript(plan: RefreshPlan): String {
        val parts = ArrayList<String>()
        if (plan.domStorage) {
            parts.add("try{localStorage.clear()}catch(e){}")
            parts.add("try{sessionStorage.clear()}catch(e){}")
            parts.add("try{if(indexedDB.databases){var d=await indexedDB.databases();d.forEach(function(x){indexedDB.deleteDatabase(x.name)})}}catch(e){}")
        }
        if (plan.serviceWorkers) {
            parts.add("try{var rs=await navigator.serviceWorker.getRegistrations();await Promise.all(rs.map(function(r){return r.unregister()}))}catch(e){}")
            parts.add("try{var ks=await caches.keys();await Promise.all(ks.map(function(k){return caches.delete(k)}))}catch(e){}")
        }
        return "(async function(){" + parts.joinToString(";") + ";return 1})()"
    }

    /**
     * Clear what [mode] says for the page's origin, then reload past the HTTP cache. WebView has no per-site HTTP
     * cache clear, so the cache step empties the app's whole WebView cache (the site's files included).
     */
    fun run(wv: WebView, mode: RefreshMode, done: () -> Unit = {}) {
        val url = wv.url?.takeIf { it.startsWith("http") } ?: return
        val plan = RefreshPlan.of(mode)
        val finish = {
            if (plan.cache) wv.clearCache(true)
            val boxes = buildSet {
                if (plan.domStorage) add(BrowserClearCategories.SITE_STORAGE)
                if (plan.cookies) add(BrowserClearCategories.SITE_COOKIES)
            }
            val reload = { wv.loadUrl(url, plan.reloadHeaders); done() }
            if (boxes.isEmpty()) reload() else BrowserStorage.clearSiteData(url, boxes) { reload() }
        }
        if (plan.domStorage || plan.serviceWorkers) BrowserJs.await(wv, pageScript(plan)) { finish() } else finish()
    }
}
