package com.diegonmarcos.superapp.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * #802 the autofill profile at rest: EncryptedSharedPreferences `browser_autofill` (declared
 * kind encrypted, class secret, so FleetConfig carries it through the same cipher and the
 * Account masks it). Flat keys, so the fleet contract moves them with no code here:
 *
 *   vault_profile / vault_addresses   raw `about.profile` / `about.addresses` JSON, written by
 *                                     the Account's server → runtime (cockpit derived_settings)
 *   profile                           what the user imported here (BrowserProfile.toJson)
 *
 * The effective profile is the vault's, with the imported one merged on top.
 * Same MasterKey scheme FleetConfig opens encrypted stores with — a different key would make
 * the fleet import write a file this class cannot read.
 */
class BrowserProfileStore(context: Context) {
    private val sp: SharedPreferences = run {
        val app = context.applicationContext
        EncryptedSharedPreferences.create(
            app, STORE,
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): BrowserProfile {
        val vault = BrowserProfile.fromVaultBundle(
            sp.getString(KEY_VAULT_PROFILE, null)?.let { runCatching { JSONObject(it) }.getOrNull() },
            sp.getString(KEY_VAULT_ADDRESSES, null)?.let { runCatching { JSONArray(it) }.getOrNull() },
        )
        val own = sp.getString(KEY_PROFILE, null)?.let { runCatching { BrowserProfile.fromNative(JSONObject(it)) }.getOrNull() }
        return if (own == null) vault else vault.merge(own)
    }

    /** Merge [p] into what the user imported before; the vault's half is the Account's to change. */
    fun import(p: BrowserProfile) {
        val own = sp.getString(KEY_PROFILE, null)?.let { runCatching { BrowserProfile.fromNative(JSONObject(it)) }.getOrNull() } ?: BrowserProfile()
        sp.edit().putString(KEY_PROFILE, own.merge(p).toJson().toString()).apply()
    }

    /** Forget both halves on this phone (the next server → runtime brings the vault's back). */
    fun clear() = sp.edit().clear().apply()

    companion object {
        const val STORE = "browser_autofill"
        const val KEY_PROFILE = "profile"
        const val KEY_VAULT_PROFILE = "vault_profile"
        const val KEY_VAULT_ADDRESSES = "vault_addresses"
    }
}
