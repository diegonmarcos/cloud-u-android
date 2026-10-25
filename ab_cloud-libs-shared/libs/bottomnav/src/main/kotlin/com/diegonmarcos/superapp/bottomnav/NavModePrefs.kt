package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * The persisted [NavModeStore]: the user's per-section navigation mode and the side menu's expanded/rail choice, in the
 * `nav_modes` SharedPreferences. It is declared in the fleet manifest (libs:fleetconfig-model, class config), so it migrates to a
 * new phone and rides the app's backup with the rest of the settings. Reads are Compose state, so a change redraws the chrome.
 */
public class NavModePrefs private constructor(context: Context) : NavModeStore {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val modes = mutableStateMapOf<String, SecondRowMode>().also { map ->
        for ((k, v) in prefs.all) {
            if (k.startsWith(MODE_PREFIX) && v is String) SecondRowMode.of(v)?.let { map[k.removePrefix(MODE_PREFIX)] = it }
        }
    }
    private var expanded: Boolean? by mutableStateOf(if (prefs.contains(KEY_EXPANDED)) prefs.getBoolean(KEY_EXPANDED, true) else null)

    override fun mode(sectionId: String): SecondRowMode? = modes[sectionId]

    override fun setMode(sectionId: String, mode: SecondRowMode?) {
        if (mode == null) { modes.remove(sectionId); prefs.edit().remove(MODE_PREFIX + sectionId).apply() }
        else { modes[sectionId] = mode; prefs.edit().putString(MODE_PREFIX + sectionId, mode.declared).apply() }
    }

    override fun sideExpanded(): Boolean? = expanded

    override fun setSideExpanded(expanded: Boolean) {
        this.expanded = expanded
        prefs.edit().putBoolean(KEY_EXPANDED, expanded).apply()
    }

    public companion object {
        public const val NAME: String = "nav_modes"
        public const val KEY_EXPANDED: String = "side_expanded"
        public const val MODE_PREFIX: String = "mode."

        @Volatile private var instance: NavModePrefs? = null

        /** The process's one store: the chrome that reads it and the Configs card that writes it must be the same object. */
        public fun of(context: Context): NavModePrefs =
            instance ?: synchronized(this) { instance ?: NavModePrefs(context).also { instance = it } }
    }
}

/** The enclosing store (null outside [ProvideNavModes]: every section then draws as declared). */
public val LocalNavModeStore = staticCompositionLocalOf<NavModeStore?> { null }

/** The app's persisted store: the process's one [NavModePrefs]. */
@Composable
public fun rememberNavModePrefs(): NavModePrefs {
    val ctx = LocalContext.current
    return remember(ctx) { NavModePrefs.of(ctx) }
}
