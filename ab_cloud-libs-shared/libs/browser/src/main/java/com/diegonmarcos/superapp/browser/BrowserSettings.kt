package com.diegonmarcos.superapp.browser

import android.content.Context

/**
 * User-changeable browser settings. Today that is one thing: which of
 * the configured search engines the address bar uses.
 *
 * Separate from [BrowserTabPrefs] because it is not about tabs, and
 * separate from [BrowserConfig] because config is what the APP ships
 * and this is what the USER has since chosen. The shipped default is
 * still the config's — [BrowserConfig.engine] resolves an unset or
 * unknown id back to it, so removing an engine from build.json cannot
 * strand someone on a dead one.
 */
class BrowserSettings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("browser_settings", Context.MODE_PRIVATE)

    /** null until he picks one — meaning "use the configured default". */
    fun searchEngineId(): String? = sp.getString(KEY_ENGINE, null)

    fun setSearchEngineId(id: String) {
        sp.edit().putString(KEY_ENGINE, id).apply()
    }

    private companion object {
        const val KEY_ENGINE = "search_engine_id"
    }
}
