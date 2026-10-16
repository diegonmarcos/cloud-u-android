package com.diegonmarcos.superapp.mail

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * How an account proves itself to the gateway in FRONT of its JMAP server (Authelia forward-auth on
 * jmap.diegonmarcos.com and the other public mail APIs). This is separate from the JMAP login
 * (email + password, Basic) the server itself checks:
 *  - [NONE]     nothing extra; the JMAP login alone (a LAN / unprotected server).
 *  - [BEARER]   the fleet token as `X-Fleet-Authorization: Bearer <token>` on every request to the account's
 *               endpoints; `Authorization` stays the mailbox login (the gate strips the fleet header).
 *  - [AUTHELIA] the `authelia_session` cookie the in-app Authelia web sign-in captured, replayed
 *               from the account's cookie jar (the JMAP Basic login rides along).
 */
enum class MailAuthMode { NONE, BEARER, AUTHELIA;
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: NONE }
}

/**
 * One account's gateway credentials. Secrets are masked in [toString] so an interpolated log line
 * can never write the token or the session cookie out.
 */
data class MailAuthCreds(
    val mode: MailAuthMode = MailAuthMode.NONE,
    /** The pasted fleet token (mode BEARER), sent as X-Fleet-Authorization. */
    val bearer: String = "",
    /** The `authelia_session` cookie VALUE (mode AUTHELIA). */
    val sessionCookie: String = "",
    /** Cookie expiry in epoch millis; 0 = unknown (a browser-session cookie). */
    val cookieExpiresAt: Long = 0L,
    /** When the web sign-in captured the cookie; 0 = never. */
    val signedInAt: Long = 0L,
    /** The last request was refused for want of a (valid) credential: the 401 / redirect-to-auth. */
    val needsSignIn: Boolean = false,
    /** The last failure, in words (never a secret); blank = none. */
    val lastError: String = "",
    /** The last Test connection / sync succeeded. */
    val lastOk: Boolean = false,
) {
    override fun toString(): String =
        "MailAuthCreds(mode=$mode, bearer=${if (bearer.isEmpty()) "" else "***"}, " +
            "sessionCookie=${if (sessionCookie.isEmpty()) "" else "***"}, cookieExpiresAt=$cookieExpiresAt, " +
            "signedInAt=$signedInAt, needsSignIn=$needsSignIn, lastOk=$lastOk)"

    fun cookieExpired(now: Long): Boolean = cookieExpiresAt in 1..now
}

/** Where the secrets live. Production is [EncryptedMailSecretStore]; tests hand in a map. */
interface MailSecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** AndroidX security-crypto: AES-256 GCM at rest, the key in the Android Keystore (same as JmapPrefs). */
class EncryptedMailSecretStore(context: Context) : MailSecretStore {
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "mail_auth_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/** Per-account gateway credentials, one encrypted JSON blob per account key. */
class MailAuthStore(private val secrets: MailSecretStore) {

    fun load(account: String): MailAuthCreds {
        val o = runCatching { Json.parseToJsonElement(secrets.get(key(account)) ?: return MailAuthCreds()).jsonObject }
            .getOrNull() ?: return MailAuthCreds()
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
        return MailAuthCreds(MailAuthMode.parse(s("mode")), s("bearer"), s("cookie"), l("cookie_exp"),
            l("signed_in_at"), b("needs_sign_in"), s("last_error"), b("last_ok"))
    }

    fun save(account: String, c: MailAuthCreds) {
        val o: JsonObject = buildJsonObject {
            put("mode", c.mode.name); put("bearer", c.bearer); put("cookie", c.sessionCookie)
            put("cookie_exp", c.cookieExpiresAt); put("signed_in_at", c.signedInAt)
            put("needs_sign_in", c.needsSignIn); put("last_error", c.lastError); put("last_ok", c.lastOk)
        }
        secrets.put(key(account), o.toString())
    }

    fun update(account: String, f: (MailAuthCreds) -> MailAuthCreds) = save(account, f(load(account)))

    /** The cookie jar refreshed (Authelia rotates `authelia_session`) or the web sign-in captured one. */
    fun saveCookie(account: String, value: String, expiresAt: Long, now: Long) = update(account) {
        it.copy(sessionCookie = value, cookieExpiresAt = expiresAt, signedInAt = if (it.sessionCookie == value) it.signedInAt else now,
            needsSignIn = false, lastError = "")
    }

    /** Sign out: forget the cookie (the token stays, it is a separate choice). */
    fun signOut(account: String) = update(account) {
        it.copy(sessionCookie = "", cookieExpiresAt = 0L, signedInAt = 0L, needsSignIn = false, lastOk = false)
    }

    fun markNeedsSignIn(account: String) = update(account) { it.copy(needsSignIn = true, lastOk = false) }
    fun markOk(account: String) = update(account) { it.copy(needsSignIn = false, lastError = "", lastOk = true) }
    fun markError(account: String, why: String) = update(account) { it.copy(lastError = why, lastOk = false) }

    fun forget(account: String) = secrets.remove(key(account))

    private fun key(account: String) = "auth:" + account.trim().lowercase()

    companion object {
        fun of(ctx: Context) = MailAuthStore(EncryptedMailSecretStore(ctx.applicationContext))

        /** The account key: the email, else the server's host. */
        fun accountKey(server: String, email: String): String =
            email.trim().ifEmpty { MailAuthScope.hostOf(server) }
    }
}

/** The JMAP client for the active login, every request through that account's auth interceptor + cookie jar. */
fun JmapPrefs.jmapClient(ctx: Context): JmapClient = JmapClient(server, email, password, MailHttp.forAccount(ctx, server, email))

/** Whether the active login can talk to its server: a password login, or a gateway credential instead. */
fun JmapPrefs.signedIn(ctx: Context): Boolean {
    // The mailbox login is needed in every mode: the gate credential (token / cookie) rides next to it.
    return email.isNotEmpty() && password.isNotEmpty() && server.isNotBlank()
}
