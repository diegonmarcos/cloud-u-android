package app.sterna.core.data.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.sterna.core.data.crypto.KeystoreCrypto
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * #1090 A fleet account's two endpoints as Cloud Account last published them, plus the fleet bearer. Nothing
 * here is typed or built in by Mail: [mesh] / [public] / [bearer] arrive over the accountdata provider; only
 * [policy] can be the owner's own choice ([policyPinned]), and a later sync then leaves it alone.
 * [toString] masks the bearer, so an interpolated log line cannot write it out.
 */
data class AccountEndpoint(
    val email: String,
    val mesh: String,
    val public: String,
    val policy: EndpointPolicy = EndpointPolicy.MESH_FIRST,
    val policyPinned: Boolean = false,
    val bearer: String = "",
    val syncedAt: Long = 0L,
) {
    override fun toString() = "AccountEndpoint(email=$email, mesh=$mesh, public=$public, policy=${policy.wire}, " +
        "pinned=$policyPinned, bearer=${if (bearer.isEmpty()) "" else "***"}, syncedAt=$syncedAt)"
}

/**
 * Per-account endpoints, one sealed JSON blob each (AES-256-GCM, Android Keystore key, like the gateway blobs and
 * the passwords), in the same `gateway_auth` store under key `ep_<id>`. [seal]/[open] are injectable so the logic
 * is JVM-testable.
 */
class FleetEndpointStore(
    private val blobs: Blobs,
    private val seal: (String, String) -> String,
    private val open: (String, String) -> String?,
) {
    /** Where the sealed blobs sit: SharedPreferences on the phone, a map in the JVM suite. */
    interface Blobs {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    fun get(accountId: String): AccountEndpoint? {
        val raw = blobs.get(key(accountId)) ?: return null
        val o = runCatching { Json.parseToJsonElement(open(accountId, raw) ?: return null).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val mesh = s("mesh"); val pub = s("public")
        if (mesh.isEmpty() || pub.isEmpty()) return null
        return AccountEndpoint(s("email"), mesh, pub, EndpointPolicy.parse(s("policy")),
            (o["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false, s("bearer"), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
    }

    fun put(accountId: String, e: AccountEndpoint) {
        val json = JsonObject(mapOf("email" to JsonPrimitive(e.email), "mesh" to JsonPrimitive(e.mesh),
            "public" to JsonPrimitive(e.public), "policy" to JsonPrimitive(e.policy.wire),
            "pinned" to JsonPrimitive(e.policyPinned), "bearer" to JsonPrimitive(e.bearer), "at" to JsonPrimitive(e.syncedAt))).toString()
        blobs.put(key(accountId), seal(accountId, json))
    }

    fun remove(accountId: String) = blobs.remove(key(accountId))

    private fun key(id: String) = "ep_$id"

    companion object {
        private fun over(prefs: SharedPreferences) = object : Blobs {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
        }

        fun of(context: Context): FleetEndpointStore = FleetEndpointStore(
            over(context.applicationContext.getSharedPreferences("gateway_auth", Context.MODE_PRIVATE)),
            seal = { id, plain -> Base64.encodeToString(KeystoreCrypto.encrypt(plain.toByteArray(Charsets.UTF_8), "ep:$id".toByteArray()), Base64.NO_WRAP) },
            open = { id, raw -> runCatching { String(KeystoreCrypto.decrypt(Base64.decode(raw, Base64.NO_WRAP), "ep:$id".toByteArray()), Charsets.UTF_8) }.getOrNull() },
        )
    }
}

/** One published fleet account, as Mail's sync hands it over (the provider's JSON parsed by the app module). */
data class PublishedEndpoint(val email: String, val mesh: String, val public: String, val policy: EndpointPolicy)

/** What a sync did, in words the Configs screen shows. Never a URL's secret, never the bearer. */
data class EndpointSyncReport(val applied: Int, val skipped: List<String>, val bearerSynced: Boolean)

/**
 * The seam between what Account published, what is stored, and what the transport uses ([EndpointRouter],
 * [FleetBearers]). An account is a FLEET account only when Account published an entry for its address AND its
 * server host is the entry's public host; every other account is not registered, so it keeps its one endpoint and
 * never receives the fleet bearer.
 */
class FleetEndpointWiring(
    private val store: FleetEndpointStore,
    private val router: EndpointRouter,
    private val bearers: FleetBearers,
) {
    /** Public hosts this wiring registered, so a host that stops being a fleet endpoint is removed again. */
    private val registered = mutableSetOf<String>()

    /** Account published [entries] (and, when the caller was granted it, [bearer]): store, then wire. */
    fun applySync(accounts: List<StoredAccount>, entries: List<PublishedEndpoint>, bearer: String, now: Long): EndpointSyncReport {
        var applied = 0
        val skipped = mutableListOf<String>()
        for (acc in accounts) {
            val entry = entries.firstOrNull { it.email.equals(acc.username, ignoreCase = true) }
            if (entry == null) { store.remove(acc.id); continue }
            if (!GatewayAuthStore.hostOf(acc.server).equals(GatewayAuthStore.hostOf(entry.public), ignoreCase = true)) {
                // The account was pointed at some other server: it is no longer the fleet's, so it gets neither route nor token.
                store.remove(acc.id); skipped += acc.username; continue
            }
            val old = store.get(acc.id)
            val pinned = old?.policyPinned == true
            store.put(acc.id, AccountEndpoint(acc.username, entry.mesh, entry.public, if (pinned) old!!.policy else entry.policy, pinned, bearer.trim(), now))
            applied++
        }
        wire(accounts)
        return EndpointSyncReport(applied, skipped, bearer.isNotBlank())
    }

    /** Process start: put every stored endpoint back on the transport. */
    fun restore(accounts: List<StoredAccount>) = wire(accounts)

    /** The owner's endpoint policy for [accountId]: kept, and applied to every account on the same endpoint (one host, one route). */
    fun setPolicy(accounts: List<StoredAccount>, accountId: String, policy: EndpointPolicy) {
        val mine = store.get(accountId) ?: return
        val host = GatewayAuthStore.hostOf(mine.public)
        for (a in accounts) {
            val e = store.get(a.id) ?: continue
            if (GatewayAuthStore.hostOf(e.public).equals(host, ignoreCase = true)) store.put(a.id, e.copy(policy = policy, policyPinned = true))
        }
        wire(accounts)
    }

    /** An account was deleted: drop its endpoint and re-wire what remains. */
    fun forget(accounts: List<StoredAccount>, accountId: String) {
        store.remove(accountId)
        wire(accounts.filter { it.id != accountId })
    }

    private fun wire(accounts: List<StoredAccount>) {
        val wanted = LinkedHashMap<String, Pair<AccountEndpoint, String>>()
        for (acc in accounts) {
            val e = store.get(acc.id) ?: continue
            val host = GatewayAuthStore.hostOf(acc.server)
            if (!host.equals(GatewayAuthStore.hostOf(e.public), ignoreCase = true)) continue
            wanted[host] = e to e.bearer
        }
        for (host in registered - wanted.keys) { router.remove(host); bearers.set(host, null) }
        registered.clear()
        for ((host, pair) in wanted) {
            val (e, token) = pair
            if (router.set(e.public, e.mesh, e.policy)) { registered += host; bearers.set(host, token) }
            else { router.remove(host); bearers.set(host, null) }
        }
    }
}
