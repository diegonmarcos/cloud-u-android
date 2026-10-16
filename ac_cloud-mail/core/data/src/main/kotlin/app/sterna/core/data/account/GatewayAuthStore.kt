package app.sterna.core.data.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.sterna.core.data.crypto.KeystoreCrypto
import app.sterna.core.jmap.GatewayCookies
import app.sterna.core.jmap.GatewayFleetTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * An account's Authelia sign-in: whether the account goes through it, the captured
 * `authelia_session` cookie and when, or the fleet token of Bearer mode. The fleet token is sent as
 * `X-Fleet-Authorization: Bearer`; the account's own secret (password, or [AuthType.API_TOKEN]) stays in
 * `Authorization` for the mail server behind the gate.
 */
data class GatewayAuth(
    val authelia: Boolean = false,
    val cookie: String = "",
    val signedInAt: Long = 0L,
    val bearer: Boolean = false,
    val fleetToken: String = "",
) {
    override fun toString(): String = "GatewayAuth(authelia=$authelia, cookie=${if (cookie.isEmpty()) "" else "***"}, signedInAt=$signedInAt, bearer=$bearer, fleetToken=${if (fleetToken.isEmpty()) "" else "***"})"
}

/** How an account reaches its server through the edge gate. */
enum class GatewayMode { NONE, BEARER, AUTHELIA }

/**
 * Per-account gateway credentials, one sealed JSON blob each (AES-256-GCM, Android Keystore key, like
 * the account passwords). [seal]/[open] are injectable so the logic is JVM-testable.
 */
class GatewayAuthStore internal constructor(
    private val prefs: SharedPreferences,
    private val seal: (String, String) -> String,
    private val open: (String, String) -> String?,
) {
    fun get(accountId: String): GatewayAuth {
        val raw = prefs.getString(key(accountId), null) ?: return GatewayAuth()
        val o = runCatching { Json.parseToJsonElement(open(accountId, raw) ?: return GatewayAuth()).jsonObject }.getOrNull()
            ?: return GatewayAuth()
        return GatewayAuth(
            (o["authelia"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["cookie"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            (o["bearer"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["fleet"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    private fun put(accountId: String, a: GatewayAuth) {
        val json = JsonObject(mapOf("authelia" to JsonPrimitive(a.authelia), "cookie" to JsonPrimitive(a.cookie),
            "at" to JsonPrimitive(a.signedInAt), "bearer" to JsonPrimitive(a.bearer),
            "fleet" to JsonPrimitive(a.fleetToken))).toString()
        prefs.edit().putString(key(accountId), seal(accountId, json)).apply()
    }

    /** Route the account through Authelia (or not). The cookie, if any, is kept. */
    fun setAuthelia(accountId: String, server: String, on: Boolean) {
        val a = get(accountId).copy(authelia = on, bearer = if (on) false else get(accountId).bearer)
        put(accountId, a)
        GatewayCookies.set(hostOf(server), if (on) a.cookie else null)
        wireFleet(server, a)
    }

    /** Route the account through the fleet Bearer gate (or not). The token, if any, is kept. */
    fun setBearer(accountId: String, server: String, on: Boolean) {
        val a = get(accountId).copy(bearer = on, authelia = if (on) false else get(accountId).authelia)
        put(accountId, a)
        wireFleet(server, a)
        if (on) GatewayCookies.set(hostOf(server), null)
    }

    /** The fleet token typed in the editor: stored sealed and put on the wire when Bearer mode is on. */
    fun setFleetToken(accountId: String, server: String, token: String) {
        val a = get(accountId).copy(fleetToken = token.trim())
        put(accountId, a)
        wireFleet(server, a)
    }

    private fun wireFleet(server: String, a: GatewayAuth) =
        GatewayFleetTokens.set(hostOf(server), if (a.bearer && !a.authelia) a.fleetToken else null)

    /** The web sign-in captured [cookie]: store it and put it on the wire. */
    fun signIn(accountId: String, server: String, cookie: String, now: Long) {
        val a = get(accountId).copy(authelia = true, cookie = cookie, signedInAt = now, bearer = false)
        put(accountId, a)
        GatewayCookies.set(hostOf(server), cookie)
        wireFleet(server, a)
    }

    fun signOut(accountId: String, server: String) {
        put(accountId, get(accountId).copy(cookie = "", signedInAt = 0L))
        GatewayCookies.set(hostOf(server), null)
    }

    fun forget(accountId: String, server: String) {
        prefs.edit().remove(key(accountId)).apply()
        GatewayCookies.set(hostOf(server), null)
        GatewayFleetTokens.set(hostOf(server), null)
    }

    /** At app start: put every Authelia account's cookie back on the wire. */
    fun restore(accounts: List<StoredAccount>) {
        for (acc in accounts) {
            val a = get(acc.id)
            if (a.authelia && a.cookie.isNotEmpty()) GatewayCookies.set(hostOf(acc.server), a.cookie)
            wireFleet(acc.server, a)
        }
    }

    private fun key(id: String) = "gw_$id"

    companion object {
        fun hostOf(server: String): String =
            server.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')

        fun of(context: Context): GatewayAuthStore = GatewayAuthStore(
            context.applicationContext.getSharedPreferences("gateway_auth", Context.MODE_PRIVATE),
            seal = { id, plain -> Base64.encodeToString(KeystoreCrypto.encrypt(plain.toByteArray(Charsets.UTF_8), "gw:$id".toByteArray()), Base64.NO_WRAP) },
            open = { id, raw -> runCatching { String(KeystoreCrypto.decrypt(Base64.decode(raw, Base64.NO_WRAP), "gw:$id".toByteArray()), Charsets.UTF_8) }.getOrNull() },
        )

        /** The mode an account is in: Authelia wins, then Bearer (the fleet-token flag; an API-token account is NOT Bearer by itself), else none. */
        fun modeOf(account: StoredAccount, auth: GatewayAuth): GatewayMode = when {
            auth.authelia -> GatewayMode.AUTHELIA
            auth.bearer -> GatewayMode.BEARER
            else -> GatewayMode.NONE
        }
    }
}

/** The chip an account wears in the list and the editor. */
enum class GatewayStatus { CONNECTED, TOKEN, SIGNED_IN, NEEDS_SIGN_IN, ERROR }

object GatewayStatuses {
    fun resolve(mode: GatewayMode, auth: GatewayAuth, rejected: Boolean, failed: Boolean): GatewayStatus = when {
        mode == GatewayMode.AUTHELIA && (auth.cookie.isEmpty() || rejected) -> GatewayStatus.NEEDS_SIGN_IN
        mode == GatewayMode.BEARER && (auth.fleetToken.isEmpty() || rejected) -> GatewayStatus.NEEDS_SIGN_IN
        failed -> GatewayStatus.ERROR
        mode == GatewayMode.AUTHELIA -> GatewayStatus.SIGNED_IN
        mode == GatewayMode.BEARER -> GatewayStatus.TOKEN
        else -> GatewayStatus.CONNECTED
    }
}
