package com.diegonmarcos.cloudbrowser.debugapi

import android.content.Context
import com.diegonmarcos.cloudbrowser.BuildConfig
import com.diegonmarcos.superapp.browser.BrowserBus
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserHistory
import com.diegonmarcos.superapp.browser.BrowserSettings
import com.diegonmarcos.superapp.browser.BrowserTabPrefs
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.devtools.AppDebugServer.Op
import org.json.JSONArray
import org.json.JSONObject

/**
 * #802 Cloud Browser on the fleet debug API (loopback, fleet token), so every browser
 * setting and store can be read and driven with the phone locked:
 * `/api/browser/<op>`. The group is build.json::ui.debug_api.group. Ops act on the
 * stores the screen reads; [BrowserBus] tells a live screen to redraw.
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
