package com.diegonmarcos.superapp.bottomnav

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The ids `ui.top_bar.settings` may name: the entries of the Settings dropdown, in the order declared.
 * Account launches Cloud Account (extapp/cloud-account); Configs asks the host app to open its own
 * Configs page. Anything else is a declaration the guard rejects ([TopBarDecl.problems]).
 */
public object TopBarSettings {
    public const val ACCOUNT: String = "account"
    public const val CONFIGS: String = "configs"
    public val KNOWN: List<String> = listOf(ACCOUNT, CONFIGS)
}

/**
 * THE top bar declaration of the fleet: `build.json::ui.top_bar` as an app reads it at runtime.
 *
 *   "top_bar": {"drawer": true, "ai": true, "settings": ["account", "configs"]}
 *
 *   drawer    a hamburger at the left opens a side drawer whose content the CURRENT PAGE supplies ([TopBarDrawer])
 *   ai        a circular AI button at the right opens the shared libs:ai chat in an anchored panel
 *   settings  a circular Settings button at the right opens a dropdown of these entries ([TopBarSettings])
 *   inline_actions  (#1149) replaces the Settings dropdown by three round buttons at the right: AI (sparkles), Profile
 *             (circled avatar, when `settings` names account) and Configs (vertical dots, when it names configs); the
 *             title then sits at the left beside the hamburger. AI is the host's [onAi] (the AI panel when it gives none).
 *
 * The title in the middle is the app's own name (build.json::name, declared once: the host passes it, the declaration restates none). An app bakes `UI_TOP_BAR_B64` (base64 of the object's JSON)
 * into BuildConfig and [AppTopBarScaffold] draws exactly what it declares. No `top_bar` = no bar: [fromBuildConfig]
 * answers null. Parsing is FAIL-SOFT like [NavDecl]: a malformed blob is null, never a crash on launch; the
 * top-bar guard (cloud-android-top-bar-guard) is what makes it a build failure.
 */
public data class TopBarDecl(
    val drawer: Boolean = false,
    val ai: Boolean = false,
    val settings: List<String> = emptyList(),
    val inlineActions: Boolean = false,
) {
    /** What is wrong with this declaration (empty = valid), the same rules the guard states at build time. */
    public fun problems(): List<String> = buildList {
        if (!drawer && !ai && settings.isEmpty() && !inlineActions) add("ui.top_bar declares nothing: no drawer, no ai, no settings")
        settings.filter { it !in TopBarSettings.KNOWN }
            .forEach { add("ui.top_bar.settings id '$it' is not one of ${TopBarSettings.KNOWN}") }
        if (settings.toSet().size != settings.size) add("ui.top_bar.settings repeats an id")
    }

    public companion object {
        /** From the object's JSON text (or an object whose `toString()` is that text); null when it is not a JSON object. */
        public fun parse(json: Any): TopBarDecl? = runCatching {
            val o = MiniJson.parse(json.toString()) as? Map<*, *> ?: return null
            TopBarDecl(
                drawer = o["drawer"] == true,
                ai = o["ai"] == true,
                settings = (o["settings"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                inlineActions = o["inline_actions"] == true,
            )
        }.getOrNull()

        /** From BuildConfig.UI_TOP_BAR_B64; blank or malformed is null (the app declares no top bar). */
        @OptIn(ExperimentalEncodingApi::class)
        public fun fromBuildConfig(uiTopBarB64: String): TopBarDecl? = runCatching {
            if (uiTopBarB64.isBlank()) return null
            val b64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            parse(b64.decode(uiTopBarB64.filterNot { it.isWhitespace() }).decodeToString())
        }.getOrNull()
    }
}
