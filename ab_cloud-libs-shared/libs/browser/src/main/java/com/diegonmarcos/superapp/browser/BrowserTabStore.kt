package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * #886 the open-tab list's (de)serialisation and the rule that decides WHAT URL a tab remembers,
 * pure so a JVM test can prove it (BrowserTabPrefs is only the SharedPreferences around it).
 *
 * THE BUG THIS FIXES (task 8). A tab's stored url was written once, when the tab was OPENED, and
 * nothing ever wrote it again: onPageFinished recorded history and nothing else. Following a link,
 * a redirect or a single-page route inside the tab left the store (and `active_url`) on the page the
 * tab started at, so after the app was closed the tab came back showing that OLDER page. It was
 * "sometimes" because a tab that never navigated looked fine. Two compounding causes: leaving a tab
 * for the grid destroyed its WebView (and with it the back/forward list) and re-opening it ran
 * `loadUrl(storedUrl)` again, and the tab's identity WAS that stale url, so it could not simply be
 * rewritten. [commit] is the missing write, keyed by the stable [BrowserTab.id].
 */
object BrowserTabStore {

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** An id for a tab stored before ids existed: deterministic, so two reads agree before the first save. */
    fun legacyId(url: String, ts: Long): String = "l" + (url + "|" + ts).hashCode().toUInt().toString(16)

    fun parse(raw: String?): List<BrowserTab> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val out = ArrayList<BrowserTab>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            val ts = o.optLong("ts", 0L)
            out.add(
                BrowserTab(
                    url         = u,
                    title       = o.optString("title", u),
                    ts          = ts,
                    previewPath = o.optString("preview", ""),
                    pinned      = o.optBoolean("pinned", false),
                    order       = o.optInt("order", BrowserTab.UNSET_ORDER),
                    group       = o.optString("group", ""),
                    isPrivate   = o.optBoolean("private", false),
                    id          = o.optString("id", "").ifBlank { legacyId(u, ts) },
                    iconPath    = o.optString("icon", ""),
                )
            )
        }
        return out
    }

    fun serialize(tabs: List<BrowserTab>): String {
        val arr = JSONArray()
        for (t in tabs) {
            arr.put(JSONObject().apply {
                put("url",     t.url)
                put("title",   t.title)
                put("ts",      t.ts)
                put("preview", t.previewPath)
                put("pinned",  t.pinned)
                put("order",   t.order)
                put("group",   t.group)
                put("private", t.isPrivate)
                put("id",      t.key)
                put("icon",    t.iconPath)
            })
        }
        return arr.toString()
    }

    /**
     * Is [url] a page worth remembering for a tab? Only a real, COMMITTED document: not the blank page a
     * WebView shows before its first navigation, not the data: document reader mode renders, not an
     * error stub. Remembering one of those would replace a good URL with nothing.
     */
    fun shouldCommit(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        val low = u.lowercase()
        return low.startsWith("http://") || low.startsWith("https://") || low.startsWith("file://")
    }

    /**
     * The tab [id] now shows [url]: remember it, and its title when one is known. Identity, pin,
     * group, order and everything else stay. A refused or unknown commit returns [tabs] unchanged.
     */
    fun commit(tabs: List<BrowserTab>, id: String, url: String?, title: String? = null): List<BrowserTab> {
        if (!shouldCommit(url)) return tabs
        return tabs.map { t ->
            if (t.key != id) t
            else t.copy(url = url!!, title = title?.takeIf { it.isNotBlank() } ?: t.title, id = t.key)
        }
    }

    /** The tab that was last active: by id, else (a store from before ids) by url. */
    fun active(tabs: List<BrowserTab>, activeId: String?, activeUrl: String?): BrowserTab? =
        tabs.firstOrNull { activeId != null && it.key == activeId }
            ?: tabs.firstOrNull { activeUrl != null && it.url == activeUrl }
}
