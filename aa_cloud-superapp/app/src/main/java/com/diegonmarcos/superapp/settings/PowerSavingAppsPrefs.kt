package com.diegonmarcos.superapp.settings

import android.content.Context

/**
 * The twelve launch targets of the Cloud Power Saving home pane — two rows of
 * six — as the user has edited them.
 *
 * The DEFAULTS are data: build.json::ui.launcher_themes[cloud_power_saving]
 * .home_apps, decoded by [LauncherThemes.homeAppsFor]. This class stores only
 * the slots the user actually changed, keyed by the slot id, and falls back to
 * the baked default for every slot it has nothing for. That is deliberate: a
 * device that has never opened the editor keeps receiving new defaults when
 * build.json ships new ones, and only an explicit choice shadows them.
 *
 * The store name is the LITERAL "power_saving_apps" — never built from a page
 * or theme id. Configs ▸ Launcher already learned that lesson once: a store
 * named after the page it is edited on moves when the page moves, and takes
 * every setting the user had with it.
 */
class PowerSavingAppsPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user's override for [slotId], or null to use the baked default. */
    fun target(slotId: String): String? = sp.getString(slotId, null)

    fun setTarget(slotId: String, target: String?) {
        val e = sp.edit()
        // Removing rather than storing "" is what makes "reset to default" a
        // real state instead of a stored empty string that no dispatcher can
        // launch and no fallback would ever replace.
        if (target.isNullOrBlank()) e.remove(slotId) else e.putString(slotId, target)
        e.apply()
    }

    /**
     * The twelve slots as they should render right now: the theme's declared
     * order, each slot carrying the user's target if they set one.
     *
     * Order comes from build.json and never from the stored map, so an edit can
     * change WHAT a slot launches but never where a slot sits — the grid stays
     * the grid the theme declares.
     */
    fun resolved(themeId: String = LauncherTheme.CloudPowerSaving.id): List<LauncherThemes.HomeApp> =
        LauncherThemes.homeAppsFor(themeId).map { slot ->
            target(slot.id)?.takeIf { it.isNotBlank() }
                ?.let { slot.copy(target = it) }
                ?: slot
        }

    companion object { private const val PREFS = "power_saving_apps" }
}
