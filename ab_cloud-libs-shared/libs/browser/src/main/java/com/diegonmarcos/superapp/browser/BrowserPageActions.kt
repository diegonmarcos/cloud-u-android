package com.diegonmarcos.superapp.browser

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.print.PrintManager
import android.webkit.WebView
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * #802 what the menu does TO A PAGE, apart from the fragment that draws the menu.
 * Script-driven actions read their JavaScript from assets/browser/ (declared files,
 * not strings in Kotlin) and answer through a callback, because evaluateJavascript does.
 */
object BrowserPageActions {

    /** assets/browser/<name>.js with `__N__` replaced. */
    fun script(ctx: Context, name: String, n: Int = 0): String =
        ctx.assets.open("browser/$name.js").bufferedReader().use { it.readText() }.replace("__N__", n.toString())

    /** evaluateJavascript answers a JSON-encoded string of what the script returned. */
    fun decode(raw: String?): JSONObject? = runCatching {
        val s = JSONTokener(raw ?: return null).nextValue() as? String ?: return null
        JSONObject(s)
    }.getOrNull()

    fun run(wv: WebView, js: String, done: (JSONObject?) -> Unit) = wv.evaluateJavascript(js) { done(decode(it)) }

    /** Reader view of the current page: extracted, then rendered with the app's declared CSS. */
    fun reader(wv: WebView, css: String, done: (JSONObject?) -> Unit) = run(wv, script(wv.context, "reader")) { r ->
        if (r != null) {
            val html = "<!doctype html><meta name=viewport content='width=device-width'><style>$css</style>" +
                "<h1>${esc(r.optString("title"))}</h1>${r.optString("html")}"
            wv.loadDataWithBaseURL(wv.url, html, "text/html", "utf-8", wv.url)
        }
        done(r)
    }

    /** Find, answering the match count once the async search finishes. */
    fun find(wv: WebView, q: String, done: (Int) -> Unit) {
        if (q.isBlank()) { wv.clearMatches(); done(0); return }
        wv.setFindListener { _, count, finished -> if (finished) done(count) }
        wv.findAllAsync(q)
    }

    fun print(wv: WebView) {
        val pm = wv.context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val name = (wv.title ?: "page").take(60)
        pm.print(name, wv.createPrintDocumentAdapter(name), null)
    }

    /** One client for the process: constructing one binds the text-tools service. */
    @Volatile private var tools: TextToolsClient? = null
    fun textTools(ctx: Context): TextToolsClient =
        tools ?: synchronized(this) { tools ?: TextToolsClient(ctx.applicationContext).also { tools = it } }

    /** Can Android make this app the default browser right now (and it is not already)? */
    fun canRequestDefault(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    fun defaultBrowserIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
        else ctx.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_BROWSER)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
}
