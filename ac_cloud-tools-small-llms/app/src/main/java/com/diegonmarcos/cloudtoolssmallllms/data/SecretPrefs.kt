package com.diegonmarcos.cloudtoolssmallllms.data

import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.diegonmarcos.cloudtoolssmallllms.core.SecretStore

/**
 * The token's one home: EncryptedSharedPreferences (keys AES-256-SIV, values AES-256-GCM) under an
 * Android Keystore AES-256-GCM master key. Fleet manifest store [PREFS] (fleet-config.json, class
 * secret). Nothing here logs, and nothing reads the value except [read], whose only caller is the
 * request that sends it as the bearer header (core TokenVault).
 */
class SecretPrefs(context: Context) : SecretStore {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        SafeEncryptedPrefs.prefs(app, PREFS)
    }

    override fun read(): String? = runCatching { prefs.getString(TOKEN, null) }.getOrNull()

    override fun write(value: String) {
        prefs.edit().putString(TOKEN, value).apply()
    }

    override fun clear() {
        prefs.edit().remove(TOKEN).apply()
    }

    companion object {
        const val PREFS = "cloud_tools_small_llms_secrets"
        private const val TOKEN = "openrouter_token"
    }
}
