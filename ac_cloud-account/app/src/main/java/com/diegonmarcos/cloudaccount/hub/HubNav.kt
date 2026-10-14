package com.diegonmarcos.cloudaccount.hub

/**
 * The island (build.json::ui, a0_docs/eng-specs/cloud-account-ia.md): Profiles, Secrets, Home, Configs. One place
 * per thing: Home ▸ account / connections / connect / import; Profiles ▸ apps, autofill (personal, sites, snippets),
 * phone (devices, working, diff); Secrets ▸ providers / secrets / grants / bundle; Configs ▸ setup (runbook, apps,
 * configs, perms), adb-shell, settings. A page inside a strip ("sites" under "autofill") is addressed by its own id;
 * NavSection.page resolves it at any depth.
 *
 * Pages in libs:account still open each other with the ids of the old islands (`open("setup", "perms")`,
 * `go("setup", "adb-shell")`, `open("account", "connect")`), and the 2026-10-13 layout had ids of its own
 * (`home/home`, `secrets/connections`); [resolve] maps every old (section, page) to where that page lives now, so no
 * link, OPEN intent or bookmark breaks and no lib file changes.
 */
object HubNav {
    /** The island, left to right, as the owner asked for it. */
    val ORDER = listOf("profiles", "secrets", "home", "configs")
    const val DEFAULT = "home"

    /** Old (section, page) -> new (section, page). "" = the section's first page. */
    val LEGACY: Map<Pair<String, String>, Pair<String, String>> = mapOf(
        // the five islands of the redesign (Account, Profiles, Setup, Secrets, Settings)
        ("account" to "") to ("home" to "account"),
        ("account" to "profile") to ("home" to "account"),
        ("account" to "connect") to ("home" to "connect"),
        ("account" to "autofill") to ("profiles" to "personal"),
        ("account" to "sites") to ("profiles" to "sites"),
        ("account" to "snippets") to ("profiles" to "snippets"),
        ("account" to "import") to ("home" to "import"),
        ("profiles" to "") to ("profiles" to "apps"),
        ("profiles" to "devices") to ("profiles" to "devices"),
        ("profiles" to "working") to ("profiles" to "working"),
        ("profiles" to "diff") to ("profiles" to "diff"),
        ("setup" to "") to ("configs" to "runbook"),
        ("setup" to "runbook") to ("configs" to "runbook"),
        ("setup" to "apps") to ("configs" to "apps"),
        ("setup" to "configs") to ("configs" to "configs"),
        ("setup" to "perms") to ("configs" to "perms"),
        ("setup" to "adb-shell") to ("configs" to "adb-shell"),
        ("secrets" to "") to ("secrets" to "providers"),
        ("secrets" to "connections") to ("secrets" to "bundle"),
        ("secrets" to "secrets") to ("secrets" to "secrets"),
        ("secrets" to "grants") to ("secrets" to "grants"),
        ("settings" to "") to ("configs" to "settings"),
        // the four islands of 2026-10-13 (#964): Home's one long page became four, Bundle got its own id
        ("home" to "") to ("home" to "account"),
        ("home" to "home") to ("home" to "account"),
        ("home" to "account") to ("home" to "account"),
        ("home" to "connect") to ("home" to "connect"),
        ("home" to "import") to ("home" to "import"),
        ("configs" to "") to ("configs" to "runbook"),
        ("configs" to "settings") to ("configs" to "settings"),
        ("configs" to "runbook") to ("configs" to "runbook"),
        ("configs" to "apps") to ("configs" to "apps"),
        ("configs" to "configs") to ("configs" to "configs"),
        ("configs" to "perms") to ("configs" to "perms"),
        ("configs" to "adb-shell") to ("configs" to "adb-shell"),
    )

    /**
     * Where a link with [section]/[page] goes: a pair [LEGACY] names is translated (the section may be a current
     * one whose page was renamed), any other current section passes through, an unknown section opens the default.
     */
    fun resolve(section: String, page: String?): Pair<String, String?> {
        val exact = LEGACY[section to page.orEmpty()]
        if (exact != null) return exact.first to exact.second.ifEmpty { null }
        if (section in ORDER) return section to page
        val hit = LEGACY[section to ""] ?: return DEFAULT to null
        return hit.first to hit.second.ifEmpty { null }
    }
}
