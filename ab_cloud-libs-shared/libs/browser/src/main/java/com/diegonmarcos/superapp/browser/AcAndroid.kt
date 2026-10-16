package com.diegonmarcos.superapp.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** EncryptedSharedPreferences as an [AcKv]: same MasterKey scheme as the rest of the fleet's encrypted stores. */
private class AcPrefsKv(private val sp: SharedPreferences) : AcKv {
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
    override fun remove(key: String) { sp.edit().remove(key).apply() }
    override fun keys(): Set<String> = sp.all.keys.toSet()
}

/** The Android Keystore AES key sealing every secret field. Created on first use; never leaves the Keystore. */
class AcKeystoreKey(private val alias: String = ALIAS) : AcKeyProvider {
    override fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // AcSealedBox draws a fresh random 12-byte IV per seal (the same code runs in the JVM tests).
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    companion object { const val ALIAS = "cloud_browser_ac_v1" }
}

/**
 * #900 the phone side of the autocomplete store: the two encrypted prefs files declared in the fleet manifest
 * (browser_ac_profiles: class secret; browser_ac_secrets: class device) and the Keystore key. Opened once per process.
 * If the encrypted files cannot be opened, the store runs in memory only and says so ([persistent]); nothing on disk is touched.
 */
object AcAndroid {
    const val PROFILES_STORE = "browser_ac_profiles"
    const val SECRETS_STORE = "browser_ac_secrets"

    @Volatile private var cached: Holder? = null

    class Holder(val store: AcStore, val persistent: Boolean, val keystoreOk: Boolean)

    fun open(ctx: Context): Holder = cached ?: synchronized(this) { cached ?: build(ctx.applicationContext).also { cached = it } }

    private fun build(app: Context): Holder {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val kvs = runCatching {
            val profiles = EncryptedSharedPreferences.create(app, PROFILES_STORE, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            val secrets = EncryptedSharedPreferences.create(app, SECRETS_STORE, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            AcPrefsKv(profiles) to AcPrefsKv(secrets)
        }.getOrNull()
        val keys = AcKeystoreKey()
        val keystoreOk = runCatching { keys.key() }.isSuccess
        val store = AcStore(kvs?.first ?: AcMemoryKv(), kvs?.second ?: AcMemoryKv(), if (keystoreOk) AcSealedBox(keys) else null)
        runCatching { store.migrate() }
        // Import the old browser profile once; it stays in its own store untouched.
        runCatching { store.importLegacyOnce(BrowserProfileStore(app).load()) }
        return Holder(store, persistent = kvs != null, keystoreOk = keystoreOk)
    }
}

/**
 * Fingerprint, face or screen lock before a password or card is revealed or filled. One success opens a short window
 * (so a login's user name AND password, then a card, do not ask three times). A phone with no screen lock refuses:
 * nothing is shown rather than shown to anyone. Setting `secrets_require_unlock` off skips the prompt.
 */
object AcUnlock {
    private val window = AcUnlockWindow()
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Null = can prompt; else the reason it cannot. */
    fun unavailable(ctx: Context): String? = when (BiometricManager.from(ctx).canAuthenticate(AUTH)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set a screen lock (or a fingerprint) to use saved passwords and cards"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "This phone cannot check a screen lock right now"
        else -> "Unlock is unavailable on this phone"
    }

    fun lock() = window.lock()

    /** [onOk] runs when the user is (still) unlocked; [onDenied] with the reason otherwise. [required] false = no prompt (the setting is off). */
    fun require(activity: FragmentActivity, required: Boolean, title: String, onOk: () -> Unit, onDenied: (String) -> Unit) {
        if (!required || window.isOpen(SystemClock.elapsedRealtime())) return onOk()
        unavailable(activity)?.let { return onDenied(it) }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                window.open(SystemClock.elapsedRealtime()); onOk()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onDenied(errString.toString()) }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Cloud Browser").setAllowedAuthenticators(AUTH).build(),
        )
    }
}
