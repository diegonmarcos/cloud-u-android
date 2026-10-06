package com.diegonmarcos.superapp.mail

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encrypted persistence of the JMAP login: server URL, email, password.
 * AndroidX security-crypto wraps SharedPreferences with AES-256 GCM at rest
 * (key in Android Keystore — hardware-backed on most modern phones).
 *
 * Stored fields are intentionally minimal: the JMAP discovery endpoint
 * regenerates the session JSON on every connect, so we don't cache it.
 */
class JmapPrefs(context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mail_jmap_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var server:   String get() = prefs.getString(K_SERVER, BuildConfig.JMAP_DEFAULT_SERVER) ?: ""
                         set(v) { prefs.edit().putString(K_SERVER, v).apply() }

    var email:    String get() = prefs.getString(K_EMAIL,    "") ?: ""
                         set(v) { prefs.edit().putString(K_EMAIL,    v).apply() }

    var password: String get() = prefs.getString(K_PASSWORD, "") ?: ""
                         set(v) { prefs.edit().putString(K_PASSWORD, v).apply() }

    fun clear() { prefs.edit().clear().apply() }

    /** #573 One stored account: its login and the hosts the vault declares for it. */
    data class Account(val email: String, val password: String, val jmap: String, val imap: String, val smtp: String) {
        fun toJson(): JSONObject = JSONObject().put("email", email).put("password", password)
            .put("jmap", jmap).put("imap", imap).put("smtp", smtp)
        companion object {
            fun fromJson(o: JSONObject) = Account(o.optString("email"), o.optString("password"),
                o.optString("jmap"), o.optString("imap"), o.optString("smtp"))
        }
    }

    /** Every account the Fleet apply stored (the vault's mail.accounts), in declared order; the
     *  [email]/[password] login above is the one of them named active. Same encrypted file. */
    fun accounts(): List<Account> = runCatching {
        val a = JSONArray(prefs.getString(K_ACCOUNTS, null) ?: return emptyList())
        (0 until a.length()).map { Account.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Stores [list] whole and makes [active] (an email in it) the login; an unknown active keeps the current one. */
    fun saveAccounts(list: List<Account>, active: String) {
        val arr = JSONArray(); for (a in list) arr.put(a.toJson())
        prefs.edit().putString(K_ACCOUNTS, arr.toString()).apply()
        list.firstOrNull { it.email == active }?.let { email = it.email; if (it.password.isNotBlank()) password = it.password }
    }

    companion object {
        private const val K_SERVER   = "server"
        private const val K_EMAIL    = "email"
        private const val K_PASSWORD = "password"
        private const val K_ACCOUNTS = "accounts_json"
    }
}
