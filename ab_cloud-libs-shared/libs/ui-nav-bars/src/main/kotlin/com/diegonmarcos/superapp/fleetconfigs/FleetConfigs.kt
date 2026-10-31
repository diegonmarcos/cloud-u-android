package com.diegonmarcos.superapp.fleetconfigs

import com.diegonmarcos.superapp.uinavbars.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE Configs > General and Configs > About declaration of the app this library is compiled into. Every app (Cloud SuperApp excepted,
 * it keeps its own Configs) declares `build.json::configs_pages`; fleet-configs.gradle resolves it (backend mesh URLs, the Store and
 * SuperApp packages, version, build sha) and bakes it as [BuildConfig.FLEET_CONFIGS_B64]. The rule it is held to is
 * 1_cicd/src/data/configs-pages.json, checked by cloud-android-configs-pages-guard.py. No app writes these pages in Kotlin.
 */
public data class GeneralSection(val id: String, val label: String, val note: String)

public data class Backend(val id: String, val why: String, val kind: String, val mesh: String)

public data class AboutDecl(
    val what: String,
    val features: List<String>,
    val repo: String,
    val license: String,
    val privacy: String,
    val backends: List<Backend>,
    val crash: String,
    val debug: String,
)

public data class FleetConfigsDecl(
    val app: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val sha: String,
    val applicationId: String,
    val storePkg: String,
    val superappPkg: String,
    val general: List<GeneralSection>,
    val about: AboutDecl,
)

public object FleetConfigs {
    /** The sections every General page carries (configs-pages.json::general_required), in this order unless the app reorders them. */
    public val REQUIRED_SECTIONS: List<String> = listOf("perms", "battery", "storage", "notifications", "ui", "language")

    /** The one optional section: the app's own Configs pages, opened through the host's Configs. */
    public const val APP_SECTION: String = "app"

    public fun defaultLabel(id: String): String = when (id) {
        "perms" -> "Perms"
        "battery" -> "Battery"
        "storage" -> "Storage"
        "notifications" -> "Notifications"
        "ui" -> "UI"
        "language" -> "Language"
        APP_SECTION -> "App"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    /** The Cloud Store section of every About page: how the fleet's apps are installed and updated. */
    public const val STORE_TEXT: String =
        "Every Cloud app is installed and updated by Cloud Store. It lists the whole fleet, installs an app from the signed release " +
            "built by CI for each commit, updates installed apps (on Wi-Fi, automatically, when enabled) and shows what each phone runs. " +
            "Nothing comes from a third-party store."

    /** The Cloud SuperApp section of every About page: the launcher hub of the fleet. */
    public const val SUPERAPP_TEXT: String =
        "Cloud SuperApp is the launcher hub of the fleet: the home screen that opens every Cloud app, the shared panels (communication, " +
            "boards, cloud, phone) and the debug tools (crash reports, logs, the route catalogue of each app)."

    public val EMPTY: FleetConfigsDecl = FleetConfigsDecl(
        app = "", name = "", version = "", versionCode = 0, sha = "", applicationId = "", storePkg = "", superappPkg = "",
        general = REQUIRED_SECTIONS.map { GeneralSection(it, defaultLabel(it), "") },
        about = AboutDecl("", emptyList(), "", "", "", emptyList(), "", ""),
    )

    /** This app's declaration (empty when the build found none: the pages still draw the device's own state). */
    public val current: FleetConfigsDecl by lazy { decode(BuildConfig.FLEET_CONFIGS_B64) }

    public fun decode(b64: String): FleetConfigsDecl =
        if (b64.isBlank()) EMPTY else runCatching { parse(String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)) }.getOrDefault(EMPTY)

    public fun parse(json: String): FleetConfigsDecl {
        val o = JSONObject(json)
        val g = o.optJSONObject("general") ?: JSONObject()
        val a = o.optJSONObject("about") ?: JSONObject()
        val declared = g.optJSONArray("sections").objects().map {
            val id = it.optString("id")
            GeneralSection(id, it.optString("label").ifBlank { defaultLabel(id) }, it.optString("note"))
        }.filter { it.id.isNotBlank() }
        return FleetConfigsDecl(
            app = o.optString("app"),
            name = o.optString("name"),
            version = o.optString("version"),
            versionCode = o.optInt("version_code"),
            sha = o.optString("sha"),
            applicationId = o.optString("application_id"),
            storePkg = o.optString("store_pkg"),
            superappPkg = o.optString("superapp_pkg"),
            general = declared.ifEmpty { EMPTY.general },
            about = AboutDecl(
                what = a.optString("what"),
                features = a.optJSONArray("features").strings(),
                repo = a.optString("repo"),
                license = a.optString("license"),
                privacy = a.optString("privacy"),
                backends = a.optJSONArray("backends").objects().map {
                    Backend(it.optString("id"), it.optString("why"), it.optString("kind"), it.optString("mesh"))
                },
                crash = a.optString("crash"),
                debug = a.optString("debug"),
            ),
        )
    }

    /** SPDX ids as a reader says them. */
    public fun licenceLabel(spdx: String): String = when (spdx) {
        "LicenseRef-NoLicenseGranted" -> "All rights reserved (no licence granted yet)"
        "" -> "Not declared"
        else -> spdx
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}
