package com.diegonmarcos.superapp.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted user-imported configs (WG private keys, mail URLs, SSH key,
 * authelia token …). Stored in EncryptedSharedPreferences — AES-256-GCM
 * at rest, key in Android Keystore (hardware-backed on most phones).
 *
 * One blob (`configs_json`) holds the full pasted JSON. Per-field
 * consumers (JmapPrefs, the future libs:net WG module, ssh-agent for
 * vault clone) parse what they need lazily; this class doesn't impose a
 * schema beyond "valid JSON".
 */
class ConfigsPrefs(context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "import_configs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var json: String
        get() = prefs.getString(K_JSON, "") ?: ""
        set(v) { prefs.edit().putString(K_JSON, v).apply() }

    /**
     * The Authelia bearer, read from / written into the SAME blob at the SAME
     * path the importers already use (`auth.authelia_token` — see
     * build.json::ui.import_schema and ProfileFragment.extractToken).
     *
     * A read-modify-write of the one blob rather than a second preference key,
     * so there is exactly one place a bearer can live on this device and
     * [clear] still erases it. It is a credential: never logged, never put in
     * the profile sync document (ProfileSync.ALLOWED_PROFILE_KEYS enforces
     * that), never shown back in the UI once stored.
     */
    var autheliaToken: String
        get() = runCatching {
            org.json.JSONObject(json.ifBlank { "{}" })
                .optJSONObject(SECTION_AUTH)?.optString(K_AUTHELIA_TOKEN).orEmpty()
        }.getOrDefault("")
        set(v) {
            // A malformed existing blob must not make the token unstorable, so
            // an unparseable blob is replaced by a fresh object rather than
            // throwing out of a setter the UI calls on every keystroke.
            val root = runCatching { org.json.JSONObject(json.ifBlank { "{}" }) }
                .getOrDefault(org.json.JSONObject())
            val auth = root.optJSONObject(SECTION_AUTH) ?: org.json.JSONObject()
            if (v.isBlank()) auth.remove(K_AUTHELIA_TOKEN) else auth.put(K_AUTHELIA_TOKEN, v)
            if (auth.length() == 0) root.remove(SECTION_AUTH) else root.put(SECTION_AUTH, auth)
            json = root.toString()
        }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        private const val K_JSON = "configs_json"
        private const val SECTION_AUTH = "auth"
        private const val K_AUTHELIA_TOKEN = "authelia_token"
    }
}
