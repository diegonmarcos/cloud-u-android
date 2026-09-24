package com.diegonmarcos.cloudlib.gitsync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Where a repository's SECRET half lives: the HTTPS token or the SSH key
 * passphrase, keyed by [ManagedRepo.id]. Encrypted at rest with the
 * Android-keystore-backed master key; never inside repos.json, never in a
 * git config file (JGit would happily write a token into .git/config's URL if
 * it were embedded there, and that file syncs with backups).
 */
class GitCredentialStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "git-sync-credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val knownHosts = File(context.filesDir, "git-sync/known_hosts")

    fun setSecret(repoId: String, secret: String) = prefs.edit().putString(repoId, secret).apply()
    fun clear(repoId: String) = prefs.edit().remove(repoId).apply()
    fun hasSecret(repoId: String): Boolean = prefs.contains(repoId)

    /** The [GitAuth] the engine needs for [repo], assembled from its public fields plus the stored secret. */
    fun authFor(repo: ManagedRepo): GitAuth = when (repo.authKind) {
        "https" -> GitAuth.Https(repo.authUsername, prefs.getString(repo.id, "") ?: "")
        "ssh" -> GitAuth.Ssh(repo.sshKeyPath, prefs.getString(repo.id, null), knownHosts.absolutePath)
        else -> GitAuth.None
    }
}
