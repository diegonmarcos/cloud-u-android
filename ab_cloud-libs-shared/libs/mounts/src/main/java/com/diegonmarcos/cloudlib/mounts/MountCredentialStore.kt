package com.diegonmarcos.cloudlib.mounts

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/** Passwords and key passphrases at rest, keystore-backed, keyed by mount id; never inside mounts.json. */
class MountCredentialStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context, "mounts-credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    val knownHosts: File = File(context.filesDir, "mounts/known_hosts")

    fun set(id: String, secret: String) = prefs.edit().putString(id, secret).apply()
    fun clear(id: String) = prefs.edit().remove(id).apply()
    fun has(id: String): Boolean = prefs.contains(id)
    fun secretFor(m: MountSpec): MountSecret {
        val s = prefs.getString(m.id, null)
        return if (m.keyPath.isNotBlank()) MountSecret(null, s) else MountSecret(s, null)
    }
}
