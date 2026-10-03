package com.diegonmarcos.cloudbrowser.debugapi

import android.content.Context
import com.diegonmarcos.cloudbrowser.BuildConfig
import com.diegonmarcos.superapp.browser.BrowserBookmarkOps
import com.diegonmarcos.superapp.browser.BrowserBookmarks
import com.diegonmarcos.superapp.browser.BrowserBus
import com.diegonmarcos.superapp.browser.BrowserClearData
import com.diegonmarcos.superapp.browser.BrowserSitePermissions
import com.diegonmarcos.superapp.browser.BrowserDownloads
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserHistory
import com.diegonmarcos.superapp.browser.BrowserProfile
import com.diegonmarcos.superapp.browser.BrowserProfileStore
import com.diegonmarcos.superapp.browser.BrowserSettings
import com.diegonmarcos.superapp.browser.BrowserTabPrefs
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.devtools.AppDebugServer.Op
import org.json.JSONArray
import org.json.JSONObject

/**
 * #802 Cloud Browser on the fleet debug API (loopback, fleet token), so every browser
 * setting and store can be read and driven with the phone locked:
 * `/api/browser/<op>`. The group is build.json::ui.debug_api.group. Store ops act on the
 * stores the screen reads and [BrowserBus.post] tells a live screen to redraw; page ops
 * run on the live page through [BrowserBus.call] and answer what it observed.
 * No op is named `state` (update-ack-guard owns GET /api/state).
 */
object BrowserDebugApi {
    @Volatile private var registered = false

    val OPS = listOf(
        Op("settings", "", "every catalogue setting with its current value and _types"),
        Op("settings/catalogue", "", "the declared catalogue: key, type, default, values/range, section, class, doc"),
        Op("settings/set", "key=<setting>&value=<raw>", "validate against the catalogue and store; ok:false + error when refused"),
        Op("tabs", "", "open tabs in draw order: url, title, pinned, group, active"),
        Op("tabs/open", "url=<url>", "open (or focus) a tab; a live screen navigates to it"),
        Op("tabs/close", "url=<url>", "close a tab; a pinned tab is refused"),
        Op("tabs/pin", "url=<url>&on=<true|false>", "pin or unpin a tab"),
        Op("history", "n=<count, default 50>", "on-device history, newest first"),
        Op("history/clear", "confirm=1", "erase the on-device history"),
        Op("menu", "", "the declared overflow menu, by section, each row with enabled/why/checked against the live page"),
        Op("menu/act", "id=<menu item with api:true>&q=<find text>&value=<zoom %>", "run a non-destructive menu action on the live page (back, forward, reload, find, reader, desktop, zoom)"),
        Op("page/find", "q=<text>", "find in the live page: the match count (the find bar shows it)"),
        Op("page/text", "n=<chars, default 2000>", "the live page's title, url and first n chars of visible text"),
        Op("page/reader", "", "reader-mode extraction of the live page: title + text length, page unchanged"),
        Op("bookmarks", "", "every bookmark: url, title, folder"),
        Op("bookmarks/add", "url=<url>&title=<title>&folder=<a/b>", "add (or update) a bookmark"),
        Op("bookmarks/remove", "url=<url>", "remove a bookmark"),
        Op("bookmarks/folders", "", "every folder in use, parents included"),
        Op("bookmarks/folder/rename", "from=<a/b>&to=<c>", "move a folder and everything under it"),
        Op("bookmarks/folder/delete", "folder=<a/b>&confirm=1", "delete a folder and every bookmark under it"),
        Op("downloads", "", "downloads this browser started, with DownloadManager's live status"),
        Op("downloads/enqueue", "url=<url>", "download a URL into the download_dir setting (a test hook)"),
        Op("downloads/clear", "", "forget the download list (the files stay in Downloads)"),
        Op("sites", "host=<host, optional>", "per-site rules; with host=, every declared permission's effective value there"),
        Op("sites/set", "host=<host>&perm=<permission id>&value=<allow|deny|ask>", "store a per-site decision (covers subdomains)"),
        Op("profile", "", "the autofill profile, MASKED: identity present + initials, address count, card last4s"),
        Op("profile/import", "format=<csv|firefox|bitwarden|vault|native, optional> + POST body", "merge an export into the profile (card numbers and codes are dropped at parse)"),
        Op("profile/clear", "confirm=1", "forget the profile on this phone"),
        Op("profile/fill", "", "the live page's fillable fields and which profile key each would get (dry: no value is read out or written)"),
        Op("privacy/clear", "<box>=1 for each of build.json clear_data ids&url=<probe, optional>&confirm=1", "clear browsing data; cookies_after = the probe URL's cookie afterwards"),
    )

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        val config = BrowserConfig.parseBase64(BuildConfig.UI_BROWSER_CONFIG_B64)
        AppDebugServer.route(BuildConfig.DEBUG_API_GROUP, OPS) { op, q ->
            handle(app, config, op, q)?.toString()
        }
    }

    fun handle(app: Context, config: BrowserConfig, op: String, q: Map<String, String>): Any? {
        val settings = BrowserSettings(app, config.settings)
        val tabs = BrowserTabPrefs(app)
        val url = q["url"].orEmpty()
        return when (op) {
            "settings" -> settings.snapshot()
            "settings/catalogue" -> config.settings.toJson()
            "settings/set" -> {
                val key = q["key"].orEmpty()
                val err = settings.set(key, q["value"].orEmpty())
                if (err != null) JSONObject().put("ok", false).put("error", err)
                else JSONObject().put("ok", true).put("key", key).put("value", JSONObject.wrap(settings.value(key)))
            }
            "tabs" -> tabsJson(tabs)
            "tabs/open" -> need(url, "url") ?: run {
                tabs.add(url, url); tabs.setActive(url)
                BrowserBus.post(BrowserBus.OPEN + url)
                JSONObject().put("ok", true).put("url", url)
            }
            "tabs/close" -> need(url, "url") ?: run {
                val closed = tabs.remove(url)
                if (closed) BrowserBus.post(BrowserBus.TABS)
                JSONObject().put("ok", closed).put("url", url)
                    .put("why", if (closed) null else "pinned or not open")
            }
            "tabs/pin" -> need(url, "url") ?: run {
                val on = q["on"]?.lowercase() != "false"
                tabs.setPinned(url, on)
                BrowserBus.post(BrowserBus.TABS)
                JSONObject().put("ok", true).put("url", url).put("pinned", on)
            }
            "history" -> {
                val n = (q["n"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                JSONArray().also { arr ->
                    BrowserHistory(app).all().take(n).forEach {
                        arr.put(JSONObject().put("url", it.url).put("title", it.title).put("ts", it.ts))
                    }
                }
            }
            "menu" -> config.menu.toJson(BrowserBus.facts())
            "menu/act" -> {
                val id = q["id"].orEmpty()
                val item = config.menu.item(id)
                when {
                    item == null -> JSONObject().put("ok", false).put("error", "$id: not a declared menu item (see menu)")
                    !item.api -> JSONObject().put("ok", false).put("error", "$id: not runnable over the API (api:false in build.json::ui.browser.menu)")
                    else -> BrowserBus.call(id, q - "id")
                }
            }
            "page/find" -> BrowserBus.call("find", q)
            "page/text" -> BrowserBus.call("page_text", q)
            "page/reader" -> BrowserBus.call("reader_extract")
            "bookmarks" -> BrowserBookmarkOps.toJson(BrowserBookmarks(app).all())
            "bookmarks/add" -> need(url, "url") ?: run {
                BrowserBookmarks(app).add(url, q["title"].orEmpty(), q["folder"].orEmpty())
                JSONObject().put("ok", true).put("url", url).put("folder", BrowserBookmarkOps.normFolder(q["folder"].orEmpty()))
            }
            "bookmarks/remove" -> need(url, "url") ?: run {
                BrowserBookmarks(app).remove(url); JSONObject().put("ok", true).put("url", url)
            }
            "bookmarks/folders" -> JSONArray(BrowserBookmarks(app).folders())
            "bookmarks/folder/rename" -> need(q["from"].orEmpty(), "from") ?: run {
                BrowserBookmarks(app).moveFolder(q["from"].orEmpty(), q["to"].orEmpty())
                JSONObject().put("ok", true).put("folders", JSONArray(BrowserBookmarks(app).folders()))
            }
            "bookmarks/folder/delete" -> need(q["folder"].orEmpty(), "folder") ?: if (q["confirm"] != "1")
                JSONObject().put("ok", false).put("error", "add confirm=1: this deletes every bookmark under the folder")
                else { BrowserBookmarks(app).deleteFolder(q["folder"].orEmpty()); JSONObject().put("ok", true) }
            "downloads" -> {
                val dl = BrowserDownloads(app)
                JSONArray().also { arr ->
                    dl.all().forEach {
                        arr.put(JSONObject().put("id", it.id).put("url", it.url).put("file", it.file).put("ts", it.ts).put("status", dl.status(it.id)))
                    }
                }
            }
            "downloads/enqueue" -> need(url, "url") ?: run {
                val d = BrowserDownloads(app).enqueue(url, config.userAgents["mobile"], null, null, settings.string("download_dir").orEmpty())
                JSONObject().put("ok", true).put("id", d.id).put("file", d.file)
            }
            "downloads/clear" -> { BrowserDownloads(app).clear(); JSONObject().put("ok", true) }
            "sites" -> {
                val sp = BrowserSitePermissions(app)
                val host = q["host"].orEmpty().lowercase()
                if (host.isEmpty()) JSONObject(sp.rules())
                else JSONObject().put("host", host).also { o ->
                    config.sitePerms.forEach { o.put(it.id, sp.resolve(host, it)) }
                }
            }
            "sites/set" -> {
                val perm = q["perm"].orEmpty()
                if (config.sitePerms.none { it.id == perm }) JSONObject().put("ok", false).put("error", "perm must be one of ${config.sitePerms.map { it.id }}")
                else BrowserSitePermissions(app).set(q["host"].orEmpty(), perm, q["value"].orEmpty())
                    ?.let { JSONObject().put("ok", false).put("error", it) }
                    ?: run { BrowserBus.post(BrowserBus.SETTINGS); JSONObject().put("ok", true) }
            }
            "privacy/clear" -> {
                val boxes = config.clearData.map { it.first }.filter { q[it] == "1" }.toSet()
                when {
                    q["confirm"] != "1" -> JSONObject().put("ok", false).put("error", "add confirm=1")
                    boxes.isEmpty() -> JSONObject().put("ok", false).put("error", "name at least one of ${config.clearData.map { it.first }} =1")
                    else -> BrowserBus.onMain("privacy/clear") { done -> BrowserClearData.clear(app, boxes, q["url"], done) }
                }
            }
            // #802 nothing below answers a profile VALUE: masked() only, and fill is dry.
            "profile" -> BrowserProfileStore(app).load().masked()
            "profile/import" -> {
                val body = q["_body"].orEmpty()
                if (body.isBlank()) JSONObject().put("ok", false).put("error", "POST the export as the request body")
                else runCatching { BrowserProfile.parse(q["format"], body) }.fold(
                    { p -> BrowserProfileStore(app).import(p); JSONObject().put("ok", true).put("profile", BrowserProfileStore(app).load().masked()) },
                    { e -> JSONObject().put("ok", false).put("error", "not a ${q["format"] ?: "recognised"} export: ${e.javaClass.simpleName}") })
            }
            "profile/clear" -> if (q["confirm"] != "1") JSONObject().put("ok", false).put("error", "add confirm=1")
                else { BrowserProfileStore(app).clear(); JSONObject().put("ok", true) }
            "profile/fill" -> BrowserBus.call("fill_dry")
            "history/clear" -> if (q["confirm"] != "1") JSONObject().put("ok", false).put("error", "add confirm=1")
                else { BrowserHistory(app).clear(); JSONObject().put("ok", true) }
            else -> null
        }
    }

    private fun need(v: String, name: String): JSONObject? =
        if (v.isBlank()) JSONObject().put("ok", false).put("error", "$name= is required") else null

    private fun tabsJson(tabs: BrowserTabPrefs): JSONArray {
        val active = tabs.activeUrl()
        return JSONArray().also { arr ->
            tabs.all().forEach {
                arr.put(JSONObject().put("url", it.url).put("title", it.title).put("pinned", it.pinned)
                    .put("group", it.group).put("active", it.url == active))
            }
        }
    }
}
