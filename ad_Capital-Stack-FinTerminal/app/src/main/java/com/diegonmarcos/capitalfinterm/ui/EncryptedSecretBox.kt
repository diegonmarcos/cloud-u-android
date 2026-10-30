package com.diegonmarcos.capitalfinterm.ui

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.capitalfinterm.SecretBox
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs

/**
 * The keys' one home: EncryptedSharedPreferences (key names AES-256-SIV, values AES-256-GCM) under an Android Keystore AES-256-GCM
 * master key, opened through the fleet's SafeEncryptedPrefs. Fleet manifest store [PREFS] (fleet-config.json, class secret).
 * Nothing here logs, and nothing reads a value except [get], whose callers are SourceKeys.read and the key-present check.
 */
class EncryptedSecretBox(context: Context) : SecretBox {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy { SafeEncryptedPrefs.prefs(app, PREFS) }

    override fun get(id: String): String? = runCatching { prefs.getString(id, null) }.getOrNull()

    override fun put(id: String, value: String) {
        prefs.edit().putString(id, value).apply()
    }

    override fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    companion object {
        const val PREFS = "capital_finterm_source_keys"
    }
}
