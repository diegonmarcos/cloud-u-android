package com.diegonmarcos.superapp.browser

import com.diegonmarcos.cloudlib.web.CloudWeb
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.net.URI

/** One per-site permission the app declares (build.json::ui.browser.site_permissions[]). */
data class BrowserSitePerm(
    val id: String,
    val label: String,
    val default: String,
    /** Android runtime permissions the app must also hold before a page can be granted it. */
    val android: List<String> = emptyList(),
    /** What WebView asks with: PermissionRequest resources, or "geolocation" for the location prompt. */
    val webkit: List<String> = emptyList(),
)

/**
 * #802 per-site decisions, pure: a rule for `example.org` also covers `a.example.org`,
 * the most specific host wins, and a host with no rule gets the permission's declared
 * default. Plus the one privacy rule for private tabs: they are never recorded.
 */
object BrowserSitePolicy {
    const val ALLOW = "allow"
    const val DENY = "deny"
    const val ASK = "ask"
    val VALUES = listOf(ALLOW, DENY, ASK)

    fun key(host: String, perm: String) = "${host.lowercase()}|$perm"

    fun hostOf(url: String?): String = runCatching { URI(url ?: "").host?.lowercase() }.getOrNull().orEmpty()

    /** [host] itself, then each parent domain: a.b.example.org → b.example.org → example.org → org. */
    fun suffixes(host: String): List<String> {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        return parts.indices.map { parts.drop(it).joinToString(".") }
    }

    fun resolve(rules: Map<String, String>, host: String, perm: String, default: String): String =
        suffixes(host).firstNotNullOfOrNull { rules[key(it, perm)] } ?: default

    /** History, previews and suggestions never see a private tab. */
    fun shouldRecord(tab: BrowserTab?): Boolean = tab?.isPrivate != true

    /** #900 the per-site rule ids that are not allow/deny/ask permissions but a stored value. */
    const val ZOOM = "zoom"
    const val ZOOM_MIN = 50
    const val ZOOM_MAX = 300

    /** The text zoom (percent) set for [host] (or a parent domain), else null = use the global one. */
    fun zoomOf(rules: Map<String, String>, host: String): Int? =
        suffixes(host).firstNotNullOfOrNull { rules[key(it, ZOOM)] }?.toIntOrNull()?.coerceIn(ZOOM_MIN, ZOOM_MAX)

    /** Hosts that hold at least one rule, with their rules: the Sites page of Configs. */
    fun hostsWithRules(rules: Map<String, String>): Map<String, Map<String, String>> =
        rules.entries.mapNotNull { (k, v) -> k.substringBefore('|', "").takeIf { it.isNotEmpty() }?.let { h -> Triple(h, k.substringAfter('|'), v) } }
            .groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }.toSortedMap()

    fun parsePerms(arr: org.json.JSONArray?): List<BrowserSitePerm> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            BrowserSitePerm(o.optString("id"), o.optString("label", o.optString("id")), o.optString("default", ASK),
                list("android"), list("webkit"))
        }.filter { it.id.isNotEmpty() }
}

/** #802 the per-site rules, in the declared `browser_site_permissions` prefs (config: they move with the phone). */
class BrowserSitePermissions(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("browser_site_permissions", Context.MODE_PRIVATE)

    fun rules(): Map<String, String> = sp.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun resolve(host: String, perm: BrowserSitePerm) = BrowserSitePolicy.resolve(rules(), host, perm.id, perm.default)

    /** #900 this site's text zoom in percent, or null. */
    fun zoom(host: String): Int? = BrowserSitePolicy.zoomOf(rules(), host)

    /** #900 set (50..300) or clear (null) this site's zoom. */
    fun setZoom(host: String, percent: Int?) {
        if (host.isBlank()) return
        val k = BrowserSitePolicy.key(host, BrowserSitePolicy.ZOOM)
        if (percent == null) sp.edit().remove(k).apply()
        else sp.edit().putString(k, percent.coerceIn(BrowserSitePolicy.ZOOM_MIN, BrowserSitePolicy.ZOOM_MAX).toString()).apply()
    }

    /** #900 forget every rule of [host] (the Sites page). */
    fun forget(host: String) {
        val ed = sp.edit()
        sp.all.keys.filter { it.substringBefore('|') == host.lowercase() }.forEach { ed.remove(it) }
        ed.apply()
    }

    /** Store a decision; [value] must be allow/deny/ask. @return the refusal in words, or null. */
    fun set(host: String, perm: String, value: String): String? {
        if (host.isBlank()) return "host= is required"
        if (value !in BrowserSitePolicy.VALUES) return "value must be one of ${BrowserSitePolicy.VALUES}"
        sp.edit().putString(BrowserSitePolicy.key(host, perm), value).apply()
        return null
    }
}

/**
 * #900 Block trackers, pure: a subresource request to a declared tracker host (or a subdomain) is
 * answered empty, unless it is the page's own site or the main frame. The hosts are the app's
 * content (build.json::ui.browser.tracker_hosts); this class ships no list.
 */
class BrowserTrackers(hosts: Collection<String>) {
    private val set = hosts.map { it.lowercase().trim().trimEnd('.') }.filter { it.contains('.') }.toSet()

    /** [host] is a tracker host or a subdomain of one. A bare TLD never matches. */
    fun isTracker(host: String): Boolean =
        BrowserSitePolicy.suffixes(host).any { it.contains('.') && it in set }

    /** Answer this request empty? [pageHost] is the page it was made from. */
    fun shouldBlock(requestHost: String, pageHost: String, mainFrame: Boolean): Boolean {
        if (mainFrame || requestHost.isEmpty() || !isTracker(requestHost)) return false
        // The page IS the tracker's own site (hotjar.com opened on purpose): its own requests are not blocked.
        return !isTracker(pageHost)
    }
}

/**
 * #802 "Clear browsing data": each declared box (build.json::ui.browser.clear_data) is
 * ONE call here. Runs on the main thread (WebView and WebStorage refuse others) and
 * answers once the asynchronous cookie removal has finished.
 */
object BrowserClearData {
    fun clear(ctx: Context, boxes: Set<String>, probeUrl: String?, done: (JSONObject) -> Unit) {
        val out = JSONObject().put("ok", true).put("cleared", org.json.JSONArray(boxes.sorted()))
        for (b in boxes) when (b) {
            "history" -> BrowserHistory(ctx).clear()
            "cache" -> CloudWeb.clearCache(ctx)
            "storage" -> WebStorage.getInstance().deleteAllData()
            "previews" -> { TabThumbnails.sharedOrNull()?.clear(); File(ctx.cacheDir, TabThumbnails.DIR).deleteRecursively(); BrowserTabPrefs(ctx).clearPreviews() }
            "downloads" -> BrowserDownloads(ctx).clear()
            "cookies" -> Unit   // asynchronous: below
            else -> out.put("ok", false).put("error", "$b: not a clear-data box")
        }
        val finish = {
            if (!probeUrl.isNullOrBlank()) out.put("cookies_after", CookieManager.getInstance().getCookie(probeUrl) ?: JSONObject.NULL)
            done(out)
        }
        if ("cookies" in boxes) CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); finish() }
        else finish()
    }

    /**
     * The last private tab closed: drop the site storage of every origin it visited, and the
     * session cookies too when no normal tab is open to lose them. Cookies are process-wide in
     * Android WebView, so a private tab beside normal ones is best-effort; the UI says so.
     */
    fun endPrivateSession(origins: Collection<String>, normalTabsOpen: Boolean) {
        origins.forEach { WebStorage.getInstance().deleteOrigin(it) }
        if (!normalTabsOpen) CookieManager.getInstance().removeSessionCookies(null)
    }
}
