package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * THE BOUNDARY BETWEEN SHARED MECHANISM AND PER-APP CONTENT.
 *
 * libs:browser is shared by reference — ac_cloud-browser/build.json and
 * ac_cloud-vault/build.json both name this directory, and anything
 * hardcoded here reaches every app that ever links it. So the mechanism
 * for pinning, grouping, history, suggestion and searching lives in this
 * module, and the CONTENT — which tabs are pinned on a fresh install,
 * which search engines exist and which is default — arrives as JSON
 * from the consuming app.
 *
 * The rule this enforces came out of #209: shared code is fine, but one
 * app's configuration must never turn up inside another app.
 *
 * [DEFAULT] is therefore deliberately EMPTY of tabs. An app that links
 * libs:browser and supplies no config gets a browser with no pinned
 * tabs at all — never somebody else's homepage set.
 *
 * ac_cloud-browser supplies its own via build.json::ui.browser, which
 * app/build.gradle bakes into BuildConfig.UI_BROWSER_CONFIG_B64 and
 * MainActivity hands to BrowserHostFragment.newInstance.
 */
data class BrowserConfig(
    /** Pinned on FIRST RUN ONLY. Never re-seeded — see [BrowserTabPrefs.seedOnce]. */
    val defaultPinnedTabs: List<String>,
    val engines: List<BrowserSearchEngine>,
    val defaultEngineId: String,
) {

    /** The configured default, or the first engine, or Qwant. Never null. */
    fun defaultEngine(): BrowserSearchEngine =
        engines.firstOrNull { it.id == defaultEngineId }
            ?: engines.firstOrNull()
            ?: QWANT

    /** [engines] entry by id, falling back to [defaultEngine]. */
    fun engine(id: String?): BrowserSearchEngine =
        engines.firstOrNull { it.id == id } ?: defaultEngine()

    companion object {

        /**
         * Qwant is the fallback engine because the owner asked for it and
         * because a browser with no engine at all cannot answer a typed
         * query. It is an ENGINE default, not a content default — the
         * shipped engine list still comes from build.json.
         */
        val QWANT = BrowserSearchEngine(
            id = "qwant",
            label = "Qwant",
            template = "https://www.qwant.com/?q=${BrowserSearchEngine.QUERY}",
        )

        /**
         * What a consuming app gets when it supplies no configuration.
         * ZERO pinned tabs — this is the assertion that keeps one app's
         * homepage set out of every other app that links this module.
         */
        val DEFAULT = BrowserConfig(
            defaultPinnedTabs = emptyList(),
            engines = listOf(QWANT),
            defaultEngineId = QWANT.id,
        )

        /**
         * Parse the per-app block. Any malformed or absent field falls
         * back to [DEFAULT]'s value rather than throwing — a bad config
         * must not brick the browser, and an EMPTY pin list is the safe
         * direction to fail in.
         */
        fun parse(json: String?): BrowserConfig {
            if (json.isNullOrBlank()) return DEFAULT
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return DEFAULT

            val pins = ArrayList<String>()
            (o.optJSONArray("default_pinned_tabs") ?: JSONArray()).let { arr ->
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    if (u.isNotEmpty()) pins.add(u)
                }
            }

            val engines = ArrayList<BrowserSearchEngine>()
            (o.optJSONArray("search_engines") ?: JSONArray()).let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id").trim()
                    val tpl = e.optString("template").trim()
                    if (id.isEmpty() || !tpl.contains(BrowserSearchEngine.QUERY)) continue
                    engines.add(BrowserSearchEngine(id, e.optString("label", id), tpl))
                }
            }

            return BrowserConfig(
                defaultPinnedTabs = pins,
                engines = if (engines.isEmpty()) DEFAULT.engines else engines,
                defaultEngineId = o.optString("default_engine", DEFAULT.defaultEngineId),
            )
        }

        /** Same, for the base64 BuildConfig field the gradle script bakes. */
        fun parseBase64(b64: String?): BrowserConfig {
            if (b64.isNullOrBlank()) return DEFAULT
            val json = runCatching {
                String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            }.getOrNull()
            return parse(json)
        }
    }
}
