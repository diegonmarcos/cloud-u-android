package com.diegonmarcos.cgomniverse.ui

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.cgc.KeySource
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs

/**
 * The user's own AI key (OpenRouter, data/avatar.json::ai.key_slot), FinTerm's Config > Sources pattern: EncryptedSharedPreferences
 * (key names AES-256-SIV, values AES-256-GCM) under an Android Keystore master key, opened through libs:crash SafeEncryptedPrefs.
 * Fleet manifest store [PREFS] (fleet-config.json, class secret). Nothing here logs; [read] is the connector's one reader.
 */
class AvatarKeys(context: Context) : KeySource {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy { SafeEncryptedPrefs.prefs(app, PREFS) }

    override fun read(slot: String): String? = runCatching { prefs.getString(slot, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun has(slot: String): Boolean = read(slot) != null

    /** Stores [raw] trimmed; false when empty or holding whitespace or a control character (a pasted line break is not kept). */
    fun save(slot: String, raw: String): Boolean {
        val v = raw.trim()
        if (slot.isEmpty() || v.isEmpty() || v.any { it.isWhitespace() || it.code < 32 }) return false
        prefs.edit().putString(slot, v).apply()
        return true
    }

    fun clear(slot: String) {
        prefs.edit().remove(slot).apply()
    }

    companion object {
        const val PREFS = "cg_omniverse_ai_keys"
    }
}
