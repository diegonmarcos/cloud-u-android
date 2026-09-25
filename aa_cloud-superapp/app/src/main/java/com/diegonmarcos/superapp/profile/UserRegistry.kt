package com.diegonmarcos.superapp.profile

import android.content.Context
import org.json.JSONObject

/**
 * User → identities → peers (#573), read off the per-user config artifact
 * (`/pub/superapp/config/<user>`, emitted by cloud-infra from the ONE
 * declaration `superapp-users.json`). Nothing here names a user, an address
 * or a device: [parse] walks what the artifact carries, and the only thing
 * this device stores is WHICH identity and WHICH peer the owner picked.
 *
 * The peer drives the rest of the Profile: its `vault_device` is the id the
 * vault's electronics/apps/peers sections use, so picking a peer here is what
 * selects the cockpit's device ([VaultCockpit.selectDevice]), and
 * [peerProfiles] is the redacted wg-quick set that belongs to it.
 */
object UserRegistry {

    data class Identity(val email: String, val primary: Boolean, val label: String)

    data class Peer(
        val id: String, val label: String, val kind: String, val primary: Boolean,
        val vaultDevice: String, val wgIp: String, val wgIpv6: String, val wgPublicIp: String,
        val profiles: List<String>,
    )

    data class Registry(
        val user: String, val name: String, val identities: List<Identity>, val peers: List<Peer>,
        val authProviders: List<String>,
    ) {
        val primaryIdentity: Identity? get() = identities.firstOrNull { it.primary } ?: identities.firstOrNull()
        val primaryPeer: Peer? get() = peers.firstOrNull { it.primary } ?: peers.firstOrNull()
        fun identity(email: String) = identities.firstOrNull { it.email == email }
        fun peer(id: String) = peers.firstOrNull { it.id == id }
    }

    /** Null when the artifact carries no registry (an older generator): the
     *  screen then says so instead of showing an empty picker. */
    fun parse(root: JSONObject): Registry? {
        val ids = root.optJSONArray("identities")
        val peers = root.optJSONObject("peers")
        if (ids == null && peers == null) return null
        val identities = (0 until (ids?.length() ?: 0)).mapNotNull { i ->
            val o = ids!!.optJSONObject(i) ?: return@mapNotNull null
            val email = o.optString("email"); if (email.isBlank()) return@mapNotNull null
            Identity(email, o.optBoolean("primary", false), o.optString("label", ""))
        }
        val peerList = peers?.keys()?.asSequence()?.mapNotNull { id ->
            val p = peers.optJSONObject(id) ?: return@mapNotNull null
            val wg0 = p.optJSONObject("wg0")
            val pub = p.optJSONObject("wg_public")
            val wg = p.optJSONObject("wireguard")
            Peer(
                id = id,
                label = p.optString("label", id),
                kind = p.optString("kind", ""),
                primary = p.optBoolean("primary", false),
                vaultDevice = p.optString("vault_device", ""),
                wgIp = wg0?.optString("wg_ip").orEmpty(),
                wgIpv6 = wg0?.optString("wg_ipv6").orEmpty(),
                wgPublicIp = pub?.optString("wg_ip").orEmpty(),
                profiles = wg?.keys()?.asSequence()?.sorted()?.toList() ?: emptyList(),
            )
        }?.toList() ?: emptyList()
        val ap = root.optJSONArray("auth_providers")
        return Registry(
            user = root.optJSONObject("_meta")?.optString("user").orEmpty(),
            name = root.optJSONObject("profile")?.optString("name").orEmpty(),
            identities = identities,
            peers = peerList,
            authProviders = (0 until (ap?.length() ?: 0)).map { ap!!.getString(it) },
        )
    }

    /** `peers.<id>.wireguard.<profile>.config_text` — the redacted wg-quick texts of one peer. */
    fun peerProfiles(root: JSONObject, peerId: String): Map<String, String> {
        val wg = root.optJSONObject("peers")?.optJSONObject(peerId)?.optJSONObject("wireguard") ?: return emptyMap()
        return wg.keys().asSequence().sorted()
            .mapNotNull { k -> wg.optJSONObject(k)?.optString("config_text")?.takeIf { it.isNotBlank() }?.let { k to it } }
            .toMap()
    }

    /** The last artifact fetched, in memory only; any import route that fetches
     *  the artifact refreshes it, so all four ways in yield the registry. */
    object Current {
        @Volatile var registry: Registry? = null
        @Volatile var artifact: JSONObject? = null
    }

    fun remember(root: JSONObject) {
        Current.artifact = root
        Current.registry = parse(root)
    }

    /**
     * Remember AND cache the registry (#573): the identities and peers are
     * public data out of a public repo, and keeping them lets steps 2 and 3 of
     * the journey stay answered across a process restart. The cache is the
     * artifact with everything but the registry cut away — no profile texts,
     * no mesh, no services — so it is small and carries nothing to protect.
     * The artifact itself stays in memory only; step 4 asks for a re-fetch.
     */
    fun remember(ctx: Context, root: JSONObject) {
        remember(root)
        prefs(ctx).edit().putString(K_REGISTRY, registryOnly(root).toString()).apply()
    }

    /** The registry: this process's, else the cached one, else null. */
    fun current(ctx: Context): Registry? =
        Current.registry ?: prefs(ctx).getString(K_REGISTRY, null)
            ?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            ?.also { Current.registry = it }

    /** `_meta.user`, `profile.name`, `identities`, `auth_providers`, and each
     *  peer without its profile texts (the profile ids are kept, as the
     *  parser only counts them). */
    fun registryOnly(root: JSONObject): JSONObject {
        val out = JSONObject()
        root.optJSONObject("_meta")?.optString("user")?.let { out.put("_meta", JSONObject().put("user", it)) }
        root.optJSONObject("profile")?.optString("name")?.let { out.put("profile", JSONObject().put("name", it)) }
        root.optJSONArray("identities")?.let { out.put("identities", it) }
        root.optJSONArray("auth_providers")?.let { out.put("auth_providers", it) }
        root.optJSONObject("peers")?.let { peers ->
            val slim = JSONObject()
            peers.keys().forEach { id ->
                val p = peers.optJSONObject(id) ?: return@forEach
                val copy = JSONObject()
                p.keys().forEach { k -> if (k != "wireguard") copy.put(k, p.get(k)) }
                p.optJSONObject("wireguard")?.let { wg ->
                    val ids = JSONObject()
                    wg.keys().forEach { prof -> ids.put(prof, JSONObject().put("name", wg.optJSONObject(prof)?.optString("name") ?: prof)) }
                    copy.put("wireguard", ids)
                }
                slim.put(id, copy)
            }
            out.put("peers", slim)
        }
        return out
    }

    /** When the artifact was last applied on this device (step 4), or "". */
    fun appliedAt(ctx: Context): String = prefs(ctx).getString(K_APPLIED_AT, "") ?: ""
    fun markApplied(ctx: Context, stamp: String) = prefs(ctx).edit().putString(K_APPLIED_AT, stamp).apply()

    fun selectedIdentity(ctx: Context): String = prefs(ctx).getString(K_IDENTITY, "") ?: ""
    fun selectIdentity(ctx: Context, email: String) = prefs(ctx).edit().putString(K_IDENTITY, email).apply()
    fun selectedPeer(ctx: Context): String = prefs(ctx).getString(K_PEER, "") ?: ""
    fun selectPeer(ctx: Context, id: String) = prefs(ctx).edit().putString(K_PEER, id).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private const val PREFS = "user_registry"
    private const val K_IDENTITY = "identity_email"
    private const val K_PEER = "peer_id"
    private const val K_REGISTRY = "registry_json"
    private const val K_APPLIED_AT = "applied_at"
}
