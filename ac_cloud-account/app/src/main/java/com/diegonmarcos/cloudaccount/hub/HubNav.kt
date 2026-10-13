package com.diegonmarcos.cloudaccount.hub

/**
 * The reorganised island (build.json::ui): Profiles, Secrets, Home, Configs. Setup is not an island any
 * more: each app profile and each provider has its own Setup, and Home has "Setup all".
 *
 * Pages in libs:account still open each other with the ids of the old five islands
 * (`open("setup", "perms")`, `go("setup", "adb-shell")`, `open("account", "connect")`); [resolve] maps every
 * old (section, page) to where that page lives now, so none of those links breaks and no lib file changes.
 */
object HubNav {
    /** The island, left to right, as the owner asked for it. */
    val ORDER = listOf("profiles", "secrets", "home", "configs")
    const val DEFAULT = "home"

    /** Old (section, page) -> new (section, page). "" = the section's first page. */
    val LEGACY: Map<Pair<String, String>, Pair<String, String>> = mapOf(
        ("account" to "") to ("home" to "account"),
        ("account" to "profile") to ("home" to "account"),
        ("account" to "connect") to ("home" to "connect"),
        ("account" to "autofill") to ("profiles" to "personal"),
        ("account" to "sites") to ("profiles" to "sites"),
        ("account" to "snippets") to ("profiles" to "snippets"),
        ("account" to "import") to ("home" to "import"),
        ("profiles" to "devices") to ("profiles" to "devices"),
        ("profiles" to "working") to ("profiles" to "working"),
        ("profiles" to "diff") to ("profiles" to "diff"),
        ("setup" to "") to ("configs" to "runbook"),
        ("setup" to "runbook") to ("configs" to "runbook"),
        ("setup" to "apps") to ("configs" to "apps"),
        ("setup" to "configs") to ("configs" to "configs"),
        ("setup" to "perms") to ("configs" to "perms"),
        ("setup" to "adb-shell") to ("configs" to "adb-shell"),
        ("secrets" to "connections") to ("secrets" to "connections"),
        ("secrets" to "secrets") to ("secrets" to "secrets"),
        ("secrets" to "grants") to ("secrets" to "grants"),
        ("settings" to "") to ("configs" to "settings"),
    )

    /** Where a link with [section]/[page] goes: an old id is translated, a current one passes through. */
    fun resolve(section: String, page: String?): Pair<String, String?> {
        if (section in ORDER) return section to page
        val hit = LEGACY[section to page.orEmpty()] ?: LEGACY[section to ""] ?: return DEFAULT to null
        return hit.first to hit.second.ifEmpty { null }
    }
}
