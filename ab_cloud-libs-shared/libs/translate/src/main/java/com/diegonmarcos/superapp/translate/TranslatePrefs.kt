package com.diegonmarcos.superapp.translate

import android.content.Context
import android.content.SharedPreferences

/**
 * Translate settings + recent language pairs.
 *
 * The keys are the single source of truth for BOTH sides: the keyboard's Compose
 * settings screen (TranslationInfoScreen, inside libs:keyboard) builds its
 * preferences from these constants, and TranslateBarView / Translator read them
 * at use time. Storage is HeliBoard's own default prefs file in DEVICE-PROTECTED
 * storage — exactly what helium314.keyboard.latin.utils.DeviceProtectedUtils
 * returns — so the settings screen and the bar always see the same file. This
 * module cannot depend on libs:keyboard (it is the other way round), hence the
 * small re-implementation instead of calling DeviceProtectedUtils.
 */
object TranslatePrefs {
    const val KEY_DEFAULT_TARGET = "translate_default_target"   // "" = active keyboard language
    const val KEY_AUTO_DETECT    = "translate_auto_detect"      // false = From defaults to the keyboard language
    const val KEY_APPLY_MODE     = "translate_apply_mode"       // APPLY_INSERT | APPLY_REPLACE
    const val KEY_LIVE_COMMIT    = "translate_live_commit"      // Gboard-style: translation lands in the field as you type
    const val KEY_RECENT_PAIRS   = "translate_recent_pairs"     // "en>pt,auto>de" most recent first

    const val DEFAULT_TARGET = ""
    const val DEFAULT_AUTO_DETECT = true
    const val APPLY_INSERT = "insert"
    const val APPLY_REPLACE = "replace"
    const val DEFAULT_APPLY_MODE = APPLY_INSERT
    const val DEFAULT_LIVE_COMMIT = false
    const val MAX_RECENT = 3

    @JvmStatic
    fun prefs(context: Context): SharedPreferences {
        // minSdk 26 ≥ N: device-protected context always exists.
        val ctx = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
        // PreferenceManager.getDefaultSharedPreferencesName(context) == packageName + "_preferences"
        return ctx.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
    }

    @JvmStatic fun defaultTarget(c: Context): String = prefs(c).getString(KEY_DEFAULT_TARGET, DEFAULT_TARGET).orEmpty()
    @JvmStatic fun autoDetect(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_DETECT, DEFAULT_AUTO_DETECT)
    @JvmStatic fun applyMode(c: Context): String = prefs(c).getString(KEY_APPLY_MODE, DEFAULT_APPLY_MODE) ?: DEFAULT_APPLY_MODE
    @JvmStatic fun liveCommit(c: Context): Boolean = prefs(c).getBoolean(KEY_LIVE_COMMIT, DEFAULT_LIVE_COMMIT)

    /** Most-recent-first list of (from, to). */
    @JvmStatic
    fun recentPairs(c: Context): List<Pair<String, String>> =
        prefs(c).getString(KEY_RECENT_PAIRS, "").orEmpty().split(',')
            .mapNotNull { s -> s.split('>').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { it[0] to it[1] } }

    @JvmStatic
    fun pushRecentPair(c: Context, from: String, to: String) {
        val updated = (listOf(from to to) + recentPairs(c)).distinct().take(MAX_RECENT)
        prefs(c).edit().putString(KEY_RECENT_PAIRS, updated.joinToString(",") { "${it.first}>${it.second}" }).apply()
    }
}
