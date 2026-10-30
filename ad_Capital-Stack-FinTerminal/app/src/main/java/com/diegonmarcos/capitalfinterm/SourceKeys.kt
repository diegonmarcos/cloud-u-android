package com.diegonmarcos.capitalfinterm

/**
 * Where the user's own API keys live. The only implementation that ships is EncryptedSecretBox (EncryptedSharedPreferences under an
 * Android Keystore master key, through the fleet's SafeEncryptedPrefs); a key is never written anywhere else and never logged.
 */
interface SecretBox {
    fun get(id: String): String?
    fun put(id: String, value: String)
    fun remove(id: String)
}

/** The keys the user typed in Config > Sources, one slot per source id. */
class SourceKeys(private val box: SecretBox) {
    fun has(id: String): Boolean = !box.get(id).isNullOrBlank()

    /** Stores [raw] (trimmed) for [id]; false when it is empty or holds a space or control character, so a pasted line break is not kept. */
    fun save(id: String, raw: String): Boolean {
        val v = raw.trim()
        if (id.isEmpty() || v.isEmpty() || v.any { it.isWhitespace() || it.code < 32 }) return false
        box.put(id, v)
        return true
    }

    fun clear(id: String) = box.remove(id)

    /** The one reader: the request that sends the key to its own source. Nothing else shows or copies it. */
    fun read(id: String): String? = box.get(id)?.takeIf { it.isNotBlank() }
}
