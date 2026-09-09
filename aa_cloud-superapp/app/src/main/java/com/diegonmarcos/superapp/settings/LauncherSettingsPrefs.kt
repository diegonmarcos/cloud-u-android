package com.diegonmarcos.superapp.settings

import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.onehand.OneHandController
import com.diegonmarcos.superapp.onehand.OneHandPrefs
import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs → Launcher → Screensaver + Others. Single source of truth for the
 * user's launcher-settings choices: the picked screensaver, the per-feature
 * on/off toggles (pets / cube / stars / haptics / eye-protection), and the two
 * sliders (system brightness, eye-protection strength).
 *
 * Defaults come from build.json (baked into BuildConfig.UI_LAUNCHER_SETTINGS_B64
 * + UI_SCREENSAVERS_B64) so nothing is hardcoded here. Every subsystem reads its
 * own key — Haptics gates on "haptics", GalaxyBackdropView on "stars_anim",
 * Home3DFragment on "cube_anim", LauncherStatusStripView on "pets_anim".
 */
class LauncherSettingsPrefs(context: Context) {

    private val appCtx = context.applicationContext
    private val sp = appCtx
        .getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    var screensaver: String
        get() = sp.getString("screensaver", null) ?: Config.defaultScreensaverId
        set(v) { sp.edit().putString("screensaver", v).apply() }

    fun toggle(id: String): Boolean = sp.getBoolean("t_$id", Config.toggleDefault(id))
    fun setToggle(id: String, v: Boolean) { sp.edit().putBoolean("t_$id", v).apply() }

    /**
     * Store-aware read/write. Most switches live in this class's own
     * `launcher_settings` store, but a switch may declare `"store"` in
     * build.json when the state it shows is already owned by another subsystem.
     * `edge_menus` does: the One-Hand tab writes OneHandPrefs, and a second copy
     * of that boolean here would let the two tabs of the SAME page disagree
     * about whether edge menus are on. So the switch is a VIEW of the owning
     * store, never a duplicate of it.
     */
    fun toggle(item: Item): Boolean = when (item.store) {
        Config.STORE_ONEHAND -> OneHandPrefs.isEnabled(appCtx)
        else          -> toggle(item.id)
    }

    fun setToggle(item: Item, v: Boolean) = when (item.store) {
        // OneHandController, not OneHandPrefs — enabling edge menus has to bring
        // the overlay up (and check the permissions it needs), which is exactly
        // what the One-Hand tab's own switch calls.
        Config.STORE_ONEHAND -> { if (v) OneHandController.enable(appCtx) else OneHandController.disable(appCtx) }
        else          -> setToggle(item.id, v)
    }

    /** Master motion gate. Every animated surface asks through here, so one
     *  "all_anim" flip stops UI motion regardless of the per-feature *_anim
     *  toggles. Pass a feature id to AND it with that feature's own switch;
     *  omit it for surfaces that have no per-feature toggle of their own. */
    fun anim(id: String? = null): Boolean =
        toggle("all_anim") && (id == null || toggle(id))

    var brightness: Int
        get() = sp.getInt("brightness", Config.brightness.default)
        set(v) { sp.edit().putInt("brightness", v).apply() }

    var eyeIntensity: Int
        get() = sp.getInt("eye_intensity", Config.eyeIntensity.default)
        set(v) { sp.edit().putInt("eye_intensity", v).apply() }

    /** Idle seconds before the screensaver auto-starts (0 = never). */
    var screensaverTimeout: Int
        get() = sp.getInt("screensaver_timeout", Config.screensaverTimeout.default)
        set(v) { sp.edit().putInt("screensaver_timeout", v).apply() }

    // ── Data-driven metadata (for the picker UI + defaults) ──────────────
    /**
     * [group] is the id of the [Group] this switch is drawn under. It replaced a
     * `battery: Boolean`, which could only ever express TWO boxes — the fragment
     * split the list with `filter { it.battery }` / `filter { !it.battery }`, so
     * a third box was a Kotlin change rather than a data one.
     *
     * [userOwned] marks a switch NO THEME MAY SET. Applying a theme writes every
     * toggle the theme names; a user-owned id is dropped from that mapping when
     * it is parsed (see LauncherThemes.parseToggles), so the drop happens once,
     * at the source, rather than at each of the call sites that would each have
     * to remember.
     *
     * [store] names where the value actually lives. "" = this class's own
     * `launcher_settings` store. "onehand" = the launcher-onehand library's
     * `onehand_prefs`, which the One-Hand tab also writes — the edge-menu switch
     * has to read and write the same key as that tab or the two surfaces would
     * disagree about whether edge menus are on.
     */
    data class Item(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
        val group: String = "",
        val userOwned: Boolean = false,
        val store: String = "",
    )
    /** One box of switches on the Theme tab. [master] adds an all-on/all-off
     *  switch above the group's own rows. */
    data class Group(
        val id: String,
        val label: String,
        val subtitle: String,
        val master: Boolean = false,
        val masterLabel: String = "",
        val masterSubtitle: String = "",
    )
    data class Slider(val label: String, val subtitle: String, val min: Int, val max: Int, val default: Int)

    object Config {
        /** `store` value routing a switch at the launcher-onehand library. */
        const val STORE_ONEHAND = "onehand"

        private val settings: JSONObject by lazy {
            runCatching {
                JSONObject(String(Base64.decode(BuildConfig.UI_LAUNCHER_SETTINGS_B64, Base64.NO_WRAP)))
            }.getOrDefault(JSONObject())
        }

        val screensavers: List<Item> by lazy {
            runCatching {
                val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SCREENSAVERS_B64, Base64.NO_WRAP)))
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Item(o.optString("id"), o.optString("label"), o.optString("subtitle"), o.optBoolean("default", false))
                }
            }.getOrDefault(emptyList())
        }
        val defaultScreensaverId: String
            get() = screensavers.firstOrNull { it.default }?.id ?: screensavers.firstOrNull()?.id ?: "black_clock"

        val toggles: List<Item> by lazy {
            val arr = settings.optJSONArray("toggles") ?: JSONArray()
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(
                    id        = o.optString("id"),
                    label     = o.optString("label"),
                    subtitle  = o.optString("subtitle"),
                    default   = o.optBoolean("default", false),
                    group     = o.optString("group", ""),
                    userOwned = o.optBoolean("user_owned", false),
                    store     = o.optString("store", ""),
                )
            }
        }
        fun toggleDefault(id: String): Boolean = toggles.firstOrNull { it.id == id }?.default ?: true

        /** The switch boxes, in declared order. */
        val groups: List<Group> by lazy {
            val arr = settings.optJSONArray("toggle_groups") ?: JSONArray()
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Group(
                    id             = o.optString("id"),
                    label          = o.optString("label"),
                    subtitle       = o.optString("subtitle"),
                    master         = o.optBoolean("master", false),
                    masterLabel    = o.optString("master_label", ""),
                    masterSubtitle = o.optString("master_subtitle", ""),
                )
            }
        }

        /** Ids no theme is allowed to write. Read by LauncherThemes when it
         *  parses the theme → toggle mapping, so a theme record that names one
         *  of these loses it before any caller can act on it. */
        val userOwnedIds: Set<String> by lazy {
            toggles.filter { it.userOwned }.map { it.id }.toSet()
        }

        private fun slider(key: String, fallbackDefault: Int): Slider {
            val o = settings.optJSONObject(key) ?: JSONObject()
            return Slider(o.optString("label", key), o.optString("subtitle", ""),
                o.optInt("min", 0), o.optInt("max", 100), o.optInt("default", fallbackDefault))
        }
        val brightness: Slider by lazy { slider("brightness", -1) }
        val eyeIntensity: Slider by lazy { slider("eye_intensity", 35) }
        val screensaverTimeout: Slider by lazy { slider("screensaver_timeout", 10) }
    }
}
