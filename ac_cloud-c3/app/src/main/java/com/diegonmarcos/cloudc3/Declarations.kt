package com.diegonmarcos.cloudc3

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #648 THE ONE reader of the build-time declarations. app/build.gradle bakes every
 * declarative list this app renders — build.json::ui (tabs, external_apps, and the
 * per-tab page lists) — into BuildConfig as base64 JSON; this file decodes each ONCE
 * into typed models and nothing else touches a BuildConfig blob.
 *
 * The parse functions take the JSON TEXT, not BuildConfig, so the JVM suite
 * (DeclarationsTest) exercises the exact parser the phone runs against this
 * repository's own build.json — a declared icon name with no drawable, or a tab with no
 * page, fails there before it fails on a device.
 */
object Declarations {

    /** One bottom-nav tab. [id] is the shell's dispatch key and the ONLY tab id in the app. */
    data class TabDecl(val id: String, val label: String, val icon: String)

    /** One sub-page inside a content tab. */
    data class PageDecl(val id: String, val label: String, val icon: String)

    /**
     * One Apps-tab tile: a SIBLING APK, not a page. [packageName] is resolved against the
     * device rather than assumed installed, so a missing app reads as "not installed"
     * instead of a tap that does nothing.
     *
     * THERE IS NO `label`: [id] is the fleet name and is what the tile shows. A display
     * name beside a package would be a second statement of the application's one name
     * (#351), which is what #224 reverted and what the superapp's test-app-names-pattern.sh
     * T4 fails the build on.
     */
    data class ExternalAppDecl(
        val id: String,
        val icon: String,
        val packageName: String,
    ) {
        /**
         * What the tile PRINTS: the fleet name with its family prefix dropped, so
         * `c3-watchdog` reads "Watchdog" and `cloud-c3-webserver` reads "Webserver" — the
         * family prefix is `cloud-c3-` or `c3-`, so dropping only the first dash-segment
         * would print "C3-webserver".
         *
         * DERIVED, never declared. That is the whole point: a stored display name beside a
         * fleet package is a second statement of the app's one name and drifts from it
         * (#351, reverted in #224, and failed by the superapp's app-names guard). A pure
         * function of [id] cannot drift — rename the app and this follows with no edit.
         */
        val display: String
            get() = id.removePrefix("cloud-").removePrefix("c3-").replaceFirstChar { it.uppercaseChar() }
    }

    // ── the baked declarations, decoded once ───────────────────────────────

    val tabs: List<TabDecl> by lazy { parseTabs(decode(BuildConfig.UI_TABS_B64)) }
    val defaultTab: String get() = BuildConfig.UI_DEFAULT_TAB
    val iconDefault: String get() = BuildConfig.UI_ICON_DEFAULT
    val externalApps: List<ExternalAppDecl> by lazy { parseExternalApps(decode(BuildConfig.UI_EXTERNAL_APPS_B64)) }
    val topologyPages: List<PageDecl> by lazy { parsePages(decode(BuildConfig.UI_TOPOLOGY_B64)) }
    val observPages: List<PageDecl> by lazy { parsePages(decode(BuildConfig.UI_OBSERV_B64)) }
    val configsPages: List<PageDecl> by lazy { parsePages(decode(BuildConfig.UI_CONFIGS_B64)) }
    val opsBaseUrl: String get() = BuildConfig.C3_OPS_BASE_URL

    // ── parsing ───────────────────────────────────────────────────────────

    fun decode(b64: String): String =
        if (b64.isBlank()) "" else runCatching { String(Base64.getDecoder().decode(b64), Charsets.UTF_8) }.getOrDefault("")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun element(text: String): JsonElement? =
        if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun objects(e: JsonElement?): List<JsonObject> = when (e) {
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(e)
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * ui.tabs, in DECLARED ORDER — the order is load-bearing, it IS the nav order, so this
     * never sorts. A tab missing an id is dropped rather than rendered nameless; the tester
     * asserts the parsed count equals the declared count, so a drop is a build failure.
     */
    fun parseTabs(text: String): List<TabDecl> = objects(element(text)).mapNotNull { o ->
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TabDecl(id = id, label = o.str("label") ?: id, icon = o.str("icon") ?: "")
    }

    /** A tab's `{ "pages": [...] }` object, or a bare array — both shapes read the same. */
    fun parsePages(text: String): List<PageDecl> {
        val root = element(text)
        val arr = when (root) {
            is JsonObject -> root["pages"]
            else -> root
        }
        return objects(arr).mapNotNull { o ->
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PageDecl(id = id, label = o.str("label") ?: id, icon = o.str("icon") ?: "")
        }
    }

    /**
     * ui.external_apps. An entry with no package is DROPPED, because a tile that cannot name
     * the app it opens is exactly the "tab that opens nothing" the ac_c3-watchtower
     * application-id note warns about; the tester asserts the parsed count equals the
     * declared count so the drop cannot pass unnoticed.
     */
    fun parseExternalApps(text: String): List<ExternalAppDecl> = objects(element(text)).mapNotNull { o ->
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val pkg = o.str("package")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ExternalAppDecl(id = id, icon = o.str("icon") ?: "", packageName = pkg)
    }

    /**
     * Every icon name any declaration uses. These are DRAWABLE names now, not Compose
     * vocabulary keys, so the tester holds them against res/drawable in both directions:
     * a name with no file resolves to 0 and draws a blank square.
     */
    fun iconNames(): Set<String> =
        (tabs.map { it.icon } +
            externalApps.map { it.icon } +
            topologyPages.map { it.icon } +
            observPages.map { it.icon } +
            configsPages.map { it.icon } +
            iconDefault)
            .filter { it.isNotBlank() }
            .toSet()
}
