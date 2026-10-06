package com.diegonmarcos.cloudc3

import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #648 THE ONE reader of the build-time declarations. app/build.gradle bakes every
 * declarative list this app renders — build.json::ui (bottom_nav + sections +
 * default_section, external_apps) — into BuildConfig; this file decodes each ONCE into
 * typed models and nothing else touches a BuildConfig blob.
 *
 * #868 the navigation is libs:bottomnav's [NavDecl]: ui.bottom_nav picks the bar's
 * sections, each section's `pages` are the strip PageTabsView draws over it. The
 * external-apps parser takes the JSON TEXT, not BuildConfig, so the JVM suite
 * (DeclarationsTest) exercises the exact parser the phone runs against this
 * repository's own build.json — a declared icon name with no drawable, or a tab with no
 * page, fails there before it fails on a device.
 */
object Declarations {

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

    /** THE navigation declaration (#868): ui.bottom_nav + ui.sections + ui.default_section. */
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The bar's sections, in DECLARED ORDER — the order is load-bearing, it IS the nav order. */
    val tabs: List<NavSection> get() = nav.bottomSections()
    val defaultTab: String get() = nav.default()?.id.orEmpty()

    /** A section's declared pages — the strip PageTabsView draws over it. */
    fun pages(sectionId: String): List<NavPage> = nav.section(sectionId)?.pages.orEmpty()

    val iconDefault: String get() = BuildConfig.UI_ICON_DEFAULT
    val externalApps: List<ExternalAppDecl> by lazy { parseExternalApps(decode(BuildConfig.UI_EXTERNAL_APPS_B64)) }
    val topologyPages: List<NavPage> get() = pages("topology")
    val observPages: List<NavPage> get() = pages("observ")
    val configsPages: List<NavPage> get() = pages("configs")
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
            nav.sections.flatMap { s -> s.allPages().map { it.icon } } +
            iconDefault)
            .filter { it.isNotBlank() }
            .toSet()
}
