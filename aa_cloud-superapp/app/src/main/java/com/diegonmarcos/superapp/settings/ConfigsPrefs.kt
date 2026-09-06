package com.diegonmarcos.superapp.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted user-imported configs (WG private keys, mail URLs, SSH key,
 * authelia token …). Stored in EncryptedSharedPreferences — AES-256-GCM
 * at rest, key in Android Keystore (hardware-backed on most phones).
 *
 * One blob (`configs_json`) holds the full pasted JSON. Per-field
 * consumers (JmapPrefs, the future libs:net WG module, ssh-agent for
 * vault clone) parse what they need lazily; this class doesn't impose a
 * schema beyond "valid JSON".
 */
class ConfigsPrefs(context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "import_configs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var json: String
        get() = prefs.getString(K_JSON, "") ?: ""
        set(v) { prefs.edit().putString(K_JSON, v).apply() }

    /**
     * The Authelia bearer, read from / written into the SAME blob at the SAME
     * path the importers already use (`auth.authelia_token` — see
     * build.json::ui.import_schema and ProfileFragment.extractToken).
     *
     * A read-modify-write of the one blob rather than a second preference key,
     * so there is exactly one place a bearer can live on this device and
     * [clear] still erases it. It is a credential: never logged, never put in
     * the profile sync document (ProfileSync.ALLOWED_PROFILE_KEYS enforces
     * that), never shown back in the UI once stored.
     *
     * A BEARER WITHOUT AN IDENTITY IS TREATED AS ABSENT. Authelia issues
     * tokens per account and the fleet has several, so a lone token cannot say
     * whose access it carries — which is the state that makes a refused
     * request unattributable. This getter therefore returns "" unless
     * [autheliaEmail] is present and looks like an address; the pairing is an
     * invariant of reading, not a convention the UI is trusted to keep. That
     * also closes the import path: a config blob pasted straight into [json]
     * with a token and no address does not become a usable credential.
     */
    val autheliaToken: String
        get() = credential()?.second ?: ""

    /** The address the stored bearer belongs to, or "" if there is no
     *  complete credential. Shown in the UI so the configured identity is
     *  visible at a glance. Not a secret — but see [autheliaToken]: without
     *  this, the token does not count as stored. */
    val autheliaEmail: String
        get() = credential()?.first ?: ""

    /** Both halves, or neither. The single definition of "a credential is
     *  present", used by every reader so none of them can disagree. */
    private fun credential(): Pair<String, String>? {
        val auth = runCatching {
            org.json.JSONObject(json.ifBlank { "{}" }).optJSONObject(SECTION_AUTH)
        }.getOrNull() ?: return null
        val email = auth.optString(K_AUTHELIA_EMAIL).trim()
        val token = auth.optString(K_AUTHELIA_TOKEN).trim()
        if (token.isBlank() || !EMAIL_PATTERN.matches(email)) return null
        return email to token
    }

    /**
     * The ONE writer. Refuses partial writes, so the orphan state this rule
     * exists to prevent cannot be created through it.
     *
     * @return null on success, or the reason it was refused — the caller
     *         shows that verbatim rather than failing silently.
     */
    fun setAutheliaCredential(email: String, token: String): String? {
        val address = email.trim()
        val bearer = token.trim()
        if (bearer.isBlank()) return "Paste the bearer token."
        if (address.isBlank()) return "A bearer token must say which account it belongs to."
        if (!EMAIL_PATTERN.matches(address)) return "\"$address\" is not an email address."
        writeAuth(address, bearer)
        return null
    }

    /**
     * A token stored with no usable address — what a pre-pairing install, or a
     * config blob imported without an identity, leaves behind.
     *
     * Deliberately NOT auto-adopted and NOT deleted: guessing the owner
     * defeats the point of recording it, and deleting a working credential to
     * satisfy a new invariant would take away the access needed to fix it. The
     * UI reports this state and offers [adoptOrphanToken].
     */
    fun hasOrphanToken(): Boolean = credential() == null && rawToken().isNotBlank()

    /** Attach an address to an existing unidentified token, making it usable
     *  again. Same validation as [setAutheliaCredential]; same return. */
    fun adoptOrphanToken(email: String): String? =
        setAutheliaCredential(email, rawToken())

    /** Remove BOTH halves. Clearing one and keeping the other would leave
     *  exactly the orphan this class refuses to produce. */
    fun clearAutheliaCredential() {
        val root = runCatching { org.json.JSONObject(json.ifBlank { "{}" }) }
            .getOrDefault(org.json.JSONObject())
        val auth = root.optJSONObject(SECTION_AUTH) ?: org.json.JSONObject()
        auth.remove(K_AUTHELIA_TOKEN)
        auth.remove(K_AUTHELIA_EMAIL)
        if (auth.length() == 0) root.remove(SECTION_AUTH) else root.put(SECTION_AUTH, auth)
        json = root.toString()
    }

    /** The stored token ignoring the pairing rule — only for detecting and
     *  repairing the orphan state above. Private so no consumer can use an
     *  unattributed bearer by accident. */
    private fun rawToken(): String = runCatching {
        org.json.JSONObject(json.ifBlank { "{}" })
            .optJSONObject(SECTION_AUTH)?.optString(K_AUTHELIA_TOKEN).orEmpty()
    }.getOrDefault("")

    /** Read-modify-write of the one blob. A malformed existing blob must not
     *  make the credential unstorable, so an unparseable blob is replaced by a
     *  fresh object rather than throwing out to the UI. */
    private fun writeAuth(email: String, token: String) {
        val root = runCatching { org.json.JSONObject(json.ifBlank { "{}" }) }
            .getOrDefault(org.json.JSONObject())
        val auth = root.optJSONObject(SECTION_AUTH) ?: org.json.JSONObject()
        auth.put(K_AUTHELIA_TOKEN, token)
        auth.put(K_AUTHELIA_EMAIL, email)
        root.put(SECTION_AUTH, auth)
        json = root.toString()
    }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        private const val K_JSON = "configs_json"
        private const val SECTION_AUTH = "auth"
        private const val K_AUTHELIA_TOKEN = "authelia_token"
        private const val K_AUTHELIA_EMAIL = "authelia_email"

        /** Shape check only. "Is this a real account" is Authelia's answer to
         *  give, not this app's — but free text like "asdf" satisfies the
         *  letter of the pairing rule and none of its purpose. */
        private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")
    }
}
