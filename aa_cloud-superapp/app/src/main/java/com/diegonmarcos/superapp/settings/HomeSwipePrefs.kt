package com.diegonmarcos.superapp.settings

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.BuildConfig

/**
 * Runtime overrides for the home-screen gestures (swipe up / left / right / down-left /
 * down-right and the drawn shapes), editable live in Configs › Launcher › One-Hand. Defaults come from
 * build.json::onehand.home_swipes (baked into BuildConfig.HOME_SWIPE_*), so a
 * fresh install behaves exactly as the data-driven config declares until the
 * user changes it. A plain view-pref, not a secret.
 *
 * These fire ONLY while on the Home section — MainActivity's nav-swipe handler
 * guards on currentSection == "home"; off-home the inner fragment owns the
 * gesture. Action ids are the same vocabulary MainActivity.handleHomeSwipeAction
 * understands (see [ACTIONS]).
 */
class HomeSwipePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("home_swipe_prefs", Context.MODE_PRIVATE)

    var up: String
        get() = sp.getString(K_UP, BuildConfig.HOME_SWIPE_UP) ?: BuildConfig.HOME_SWIPE_UP
        set(value) { sp.edit().putString(K_UP, value).apply() }

    var left: String
        get() = sp.getString(K_LEFT, BuildConfig.HOME_SWIPE_LEFT) ?: BuildConfig.HOME_SWIPE_LEFT
        set(value) { sp.edit().putString(K_LEFT, value).apply() }

    var right: String
        get() = sp.getString(K_RIGHT, BuildConfig.HOME_SWIPE_RIGHT) ?: BuildConfig.HOME_SWIPE_RIGHT
        set(value) { sp.edit().putString(K_RIGHT, value).apply() }

    /** Swipe down that starts on the LEFT half. Stored under the old `swipe_down` key, so an override made before the split carries over. */
    var downLeft: String
        get() = sp.getString(K_DOWN_LEFT, BuildConfig.HOME_SWIPE_DOWN_LEFT) ?: BuildConfig.HOME_SWIPE_DOWN_LEFT
        set(value) { sp.edit().putString(K_DOWN_LEFT, value).apply() }

    /** Swipe down that starts on the RIGHT half. */
    var downRight: String
        get() = sp.getString(K_DOWN_RIGHT, BuildConfig.HOME_SWIPE_DOWN_RIGHT) ?: BuildConfig.HOME_SWIPE_DOWN_RIGHT
        set(value) { sp.edit().putString(K_DOWN_RIGHT, value).apply() }

    /** The action of a drawn shape (build.json onehand.shape_gestures[id]); the declared one until overridden. */
    fun shape(id: String, declared: String): String = sp.getString("shape_$id", declared) ?: declared
    fun setShape(id: String, action: String) { sp.edit().putString("shape_$id", action).apply() }

    companion object {
        private const val K_UP = "swipe_up"
        private const val K_LEFT = "swipe_left"
        private const val K_RIGHT = "swipe_right"
        private const val K_DOWN_LEFT = "swipe_down"
        private const val K_DOWN_RIGHT = "swipe_down_right"

        /** UI-facing action vocabulary: id → human label. Order = picker order.
         *  Kept in sync with MainActivity.handleHomeSwipeAction. */
        val ACTIONS: List<Pair<String, String>> = listOf(
            "page:cloud/apps"          to "Cloud: Apps",
            "page:communication/my-rss" to "Notify",
            "page:config/controls"     to "Quick Actions",
            "page:phone/apps"          to "Phone: Apps",
            "open_last_cloud_app"      to "Last Cloud app",
            "open_last_superapp_page"  to "Last SuperApp page",
            "toggle_light"             to "Light: on/off",
            "open_cloud_calc"          to "Cloud Calc",
            "open_last_android_app"    to "Last Android app",
            "walk_step_next"           to "Walk → next",
            "walk_step_prev"           to "Walk ← prev",
        )
    }
}
