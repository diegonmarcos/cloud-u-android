package com.diegonmarcos.cloudtoolssmallllms.core

import com.diegonmarcos.superapp.decisions.Decisions

/**
 * Where the token is kept. The app's implementation is EncryptedSharedPreferences under an
 * Android Keystore AES-256-GCM master key (data/SecretPrefs.kt); nothing else ever holds it.
 */
interface SecretStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/**
 * The user's own OpenRouter token: written only to the encrypted [store], read back only to be the
 * bearer of a call, and shown only masked. Its [toString] is the mask, so a log line or a crash
 * report that prints the vault prints no key.
 */
class TokenVault(private val store: SecretStore) {
    /** Saves [raw] trimmed; false (nothing saved) for a blank value or one with whitespace inside. */
    fun set(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return false
        store.write(t)
        return true
    }

    /** The token for a request's Authorization header, or null when none is saved. */
    fun token(): String? = store.read()?.trim()?.takeIf { it.isNotEmpty() }

    val isSet: Boolean get() = token() != null

    /** `sk-or-…abcd`: the only form of the token that reaches a screen. */
    fun masked(): String = Decisions.mask(token())

    fun clear() = store.clear()

    override fun toString(): String = "TokenVault(${masked()})"
}
