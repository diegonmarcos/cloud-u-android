// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocomplete

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.diegonmarcos.superapp.autofill.AutocompleteContract
import com.diegonmarcos.superapp.autofill.AutocompleteContract.Row
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * #1103 The Autocomplete engine's own database, like a browser's autofill store: rows kept ON THIS PHONE, encrypted at
 * rest, and nothing else. It shares nothing with the clipboard history (its own file, its own table of rows, its own key)
 * and nothing with Cloud Vault (no vault call, no vault type; the rows are filled by [AutocompleteSync] from Cloud
 * Account's provider). No network code lives in this package.
 */
interface AutocompleteCipher {
    fun encrypt(plain: ByteArray): String
    /** The plain bytes, or null when [text] is not ours or the key is gone. */
    fun decrypt(text: String): ByteArray?
}

/** AES-256-GCM with a key that never leaves the Android Keystore. */
class KeystoreCipher(private val alias: String = ALIAS) : AutocompleteCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    override fun decrypt(text: String): ByteArray? = runCatching {
        val raw = Base64.decode(text, Base64.NO_WRAP)
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES)) }
            .doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
    }.getOrNull()

    companion object {
        const val ALIAS = "cloud_keyboard_autocomplete"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

/** Where the one encrypted blob lives. The phone's is a private SharedPreferences file; a JVM test hands in a variable. */
interface AutocompleteBacking {
    fun read(): String?
    fun write(value: String?)
}

class PrefsBacking(private val prefs: SharedPreferences) : AutocompleteBacking {
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(value: String?) { prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply() }
    private companion object { const val KEY = "rows" }
}

class AutocompleteStore(private val backing: AutocompleteBacking, private val cipher: AutocompleteCipher) {
    /** Every row, decrypted; empty when nothing is stored or the blob cannot be read with this key. */
    fun all(): List<Row> = load()?.first.orEmpty()
    fun page(page: String): List<Row> = all().filter { it.page == page }
    /** When the rows were last replaced (epoch ms), 0 when never. */
    fun syncedAt(): Long = load()?.second ?: 0L

    fun replaceAll(rows: List<Row>, now: Long) {
        val body = JSONObject().put("at", now).put("rows", JSONArray().also { a ->
            rows.filter { it.page in AutocompleteContract.PAGES && it.value.isNotBlank() }
                .forEach { a.put(JSONObject().put("p", it.page).put("l", it.label).put("v", it.value)) }
        })
        backing.write(cipher.encrypt(body.toString().toByteArray(Charsets.UTF_8)))
    }

    fun clear() = backing.write(null)

    private fun load(): Pair<List<Row>, Long>? {
        val text = backing.read() ?: return null
        val plain = cipher.decrypt(text) ?: return null
        val o = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return null
        val a = o.optJSONArray("rows") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Row(it.optString("p"), it.optString("l"), it.optString("v")) } } to o.optLong("at")
    }

    companion object {
        /** The declared store (fleet-config.json `autocomplete_store`, class device: it is re-filled by Account, never migrated). */
        const val PREFS = "autocomplete_store"

        fun open(context: Context): AutocompleteStore =
            AutocompleteStore(PrefsBacking(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), KeystoreCipher())
    }
}
