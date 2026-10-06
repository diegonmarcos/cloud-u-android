package com.diegonmarcos.superapp.network
import com.diegonmarcos.superapp.BuildConfig

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import org.json.JSONArray
import org.json.JSONObject

/**
 * WireGuard tunnel persistence — Interface + a list of Peers used by
 * libs:net's Config, driven through libs:net's AidlBackend. Plain
 * SharedPreferences (the form ships persisted values back through
 * the engine at Connect time; pre-shared key + private key are
 * sensitive but stored here unencrypted to match the rest of the
 * Configs UX — the threat model for a phone with a granted VPN
 * profile is already root-equivalent).
 *
 * First-run defaults come from BuildConfig.UI_WG_* (Interface fields
 * are individual Strings; peers come baked as a single base64 JSON
 * blob, parsed lazily on first read). Key material is intentionally
 * NOT seeded — the user pastes / imports / generates.
 *
 * Peers are stored as a JSON array under one SharedPref key so add /
 * remove / reorder are atomic. UI calls [peers] for the current list
 * and [savePeers] to commit edits.
 *
 * [toWgConfig] hydrates an upstream [Config] from the stored fields
 * (Interface + every peer); throws on validation so the caller can
 * show the error inline.
 */
class WireGuardPrefs(context: Context) {
    private val app: Context = context.applicationContext
    private val sp: SharedPreferences =
        context.getSharedPreferences("wireguard_prefs", Context.MODE_PRIVATE)

    var tunnelName: String
        get() = sp.getString(K_TUNNEL_NAME, BuildConfig.UI_WG_TUNNEL_NAME)
            ?: BuildConfig.UI_WG_TUNNEL_NAME
        set(value) { sp.edit().putString(K_TUNNEL_NAME, value).apply() }

    var interfacePrivateKey: String
        get() = sp.getString(K_IF_PRIVKEY, "") ?: ""
        set(value) { sp.edit().putString(K_IF_PRIVKEY, value).apply() }

    var interfaceAddress: String
        get() = sp.getString(K_IF_ADDRESS, BuildConfig.UI_WG_INTERFACE_ADDRESS)
            ?: BuildConfig.UI_WG_INTERFACE_ADDRESS
        set(value) { sp.edit().putString(K_IF_ADDRESS, value).apply() }

    var interfaceDns: String
        get() = sp.getString(K_IF_DNS, BuildConfig.UI_WG_INTERFACE_DNS)
            ?: BuildConfig.UI_WG_INTERFACE_DNS
        set(value) { sp.edit().putString(K_IF_DNS, value).apply() }

    var interfaceListenPort: String
        get() = sp.getString(K_IF_LISTEN_PORT, BuildConfig.UI_WG_INTERFACE_LISTEN_PORT)
            ?: BuildConfig.UI_WG_INTERFACE_LISTEN_PORT
        set(value) { sp.edit().putString(K_IF_LISTEN_PORT, value).apply() }

    var interfaceMtu: String
        get() = sp.getString(K_IF_MTU, BuildConfig.UI_WG_INTERFACE_MTU)
            ?: BuildConfig.UI_WG_INTERFACE_MTU
        set(value) { sp.edit().putString(K_IF_MTU, value).apply() }

    /**
     * Where the tunnel's PUBLIC half came from — [PROVIDER_CLOUD] (the fleet
     * preset) or [PROVIDER_CUSTOM] (the user's own values). Cloud is the
     * default because the baked first-run values ARE the preset, so a fresh
     * install is already on it and saying otherwise would be a lie.
     *
     * This records a choice; it does not gate anything. Configs → WireGuard
     * stays editable on either setting — a user who edits while on Cloud has
     * simply drifted from the preset, which [matchesCloudPreset] detects.
     */
    var configProvider: String
        get() = sp.getString(K_PROVIDER, PROVIDER_CLOUD) ?: PROVIDER_CLOUD
        set(value) { sp.edit().putString(K_PROVIDER, value).apply() }

    /**
     * Overwrite the tunnel's PUBLIC half with the fleet preset — tunnel name,
     * addresses, DNS, listen port, MTU and the whole peer list (hub public
     * key, endpoint, allowed IPs, keepalive).
     *
     * The values come from BuildConfig.UI_WG_*, which app/build.gradle bakes
     * from build.json::ui.wireguard_default. Nothing here is a literal, so the
     * preset follows the fleet instead of rotting the next time the hub moves.
     *
     * [interfacePrivateKey] IS DELIBERATELY NOT TOUCHED, and no preset may
     * ever carry one. A WireGuard private key names one device: two devices
     * sharing a key are one peer to the hub, and they knock each other off it.
     */
    fun applyCloudPreset() {
        tunnelName          = BuildConfig.UI_WG_TUNNEL_NAME
        interfaceAddress    = BuildConfig.UI_WG_INTERFACE_ADDRESS
        interfaceDns        = BuildConfig.UI_WG_INTERFACE_DNS
        interfaceListenPort = BuildConfig.UI_WG_INTERFACE_LISTEN_PORT
        interfaceMtu        = BuildConfig.UI_WG_INTERFACE_MTU
        savePeers(presetPeers())
    }

    /** The peer list [applyCloudPreset] would write. Public so the confirm
     *  dialog can NAME what it is about to change to instead of asking the
     *  user to accept an unspecified replacement. */
    fun presetPeers(): List<PeerData> = parsePeers(defaultPeersJson())

    /** True when the stored public half already equals what [applyCloudPreset]
     *  would write, so the caller can skip asking before overwriting nothing. */
    fun matchesCloudPreset(): Boolean =
        tunnelName          == BuildConfig.UI_WG_TUNNEL_NAME &&
        interfaceAddress    == BuildConfig.UI_WG_INTERFACE_ADDRESS &&
        interfaceDns        == BuildConfig.UI_WG_INTERFACE_DNS &&
        interfaceListenPort == BuildConfig.UI_WG_INTERFACE_LISTEN_PORT &&
        interfaceMtu        == BuildConfig.UI_WG_INTERFACE_MTU &&
        peers()             == presetPeers()

    /**
     * Generate a Curve25519 key pair ON THIS DEVICE, store the private half as
     * the interface key and return the PUBLIC half for the user to hand to
     * whoever administers the hub.
     *
     * This is the honest alternative to seeding a key: the private half is
     * created here and never leaves, and the tunnel stays down until the
     * returned public half is added to the hub as a peer.
     */
    fun generateInterfaceKeyPair(): String {
        val pair = KeyPair()
        interfacePrivateKey = pair.privateKey.toBase64()
        return pair.publicKey.toBase64()
    }

    // ── the named profile set (#573 samsung-a37: four profiles, ONE active tunnel) ──

    /**
     * Every mesh profile the vault declared for THIS device, by name, as wg-quick
     * text WITHOUT its PrivateKey line (the value is [PROVIDED_BY_DEVICE]). The
     * device's private key lives in exactly one place, [interfacePrivateKey], and
     * [activateProfile] splices it in at parse time — so storing four profiles
     * never makes four copies of the credential, and a profile text can be shown
     * or exported without ever carrying it. Android runs ONE tunnel, so a set is
     * stored and one of its names is [activeProfile]; activating another name
     * re-hydrates the single tunnel through [hydrateFromConfig], the ONE import path.
     */
    fun profiles(): Map<String, String> {
        val raw = sp.getString(K_PROFILES_JSON, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }.toSortedMap()
        }.getOrDefault(emptyMap())
    }

    fun saveProfiles(profiles: Map<String, String>) {
        val o = JSONObject()
        for ((name, conf) in profiles) o.put(name, stripPrivateKey(conf))
        sp.edit().putString(K_PROFILES_JSON, o.toString()).apply()
    }

    /** The name of the stored profile the single tunnel currently is; "" before any Apply all. */
    var activeProfile: String
        get() = sp.getString(K_ACTIVE_PROFILE, "") ?: ""
        set(value) { sp.edit().putString(K_ACTIVE_PROFILE, value).apply() }

    /**
     * Make the stored profile [name] THE tunnel: its text with this device's
     * [interfacePrivateKey] spliced for [PROVIDED_BY_DEVICE], through the upstream
     * parser and [hydrateFromConfig]. Throws when the name is not stored, when no
     * key is held, or when the parser rejects the text — nothing is written then.
     */
    fun activateProfile(name: String): Config {
        val conf = profiles()[name] ?: throw IllegalArgumentException("no stored profile named $name")
        val key = interfacePrivateKey
        if (key.isBlank()) throw IllegalStateException("no private key on this device — generate or import one first")
        val cfg = Config.parse(java.io.BufferedReader(java.io.StringReader(conf.replace(PROVIDED_BY_DEVICE, key))))
        tunnelName = name.substringAfterLast('/').take(15)
        hydrateFromConfig(cfg)
        configProvider = PROVIDER_CUSTOM
        activeProfile = name
        return cfg
    }

    /** Last user-driven Connect/Disconnect state — restored on app
     *  restart so the toggle reflects the actual tunnel state. */
    var tunnelEnabled: Boolean
        get() = sp.getBoolean(K_TUNNEL_ENABLED, false)
        set(value) { sp.edit().putBoolean(K_TUNNEL_ENABLED, value).apply() }

    /** Current peer list. Falls back to the build-time default
     *  (UI_WG_PEERS_JSON_B64) on first read. */
    fun peers(): MutableList<PeerData> {
        val stored = sp.getString(K_PEERS_JSON, null)
        val raw = stored ?: defaultPeersJson()
        return parsePeers(raw).toMutableList()
    }

    fun savePeers(peers: List<PeerData>) {
        val arr = JSONArray()
        for (p in peers) arr.put(p.toJson())
        sp.edit().putString(K_PEERS_JSON, arr.toString()).apply()
    }

    /**
     * Derive the interface public key from the stored private key.
     * Returns empty string if no private key is set or the value
     * isn't a valid base64-encoded 32-byte Curve25519 key. UI surfaces
     * this as a read-only field so the user can verify their key
     * material.
     */
    fun derivedInterfacePublicKey(): String = try {
        if (interfacePrivateKey.isBlank()) ""
        else KeyPair(Key.fromBase64(interfacePrivateKey)).publicKey.toBase64()
    } catch (_: Throwable) {
        ""
    }

    /**
     * Build the upstream Config from the stored fields. Throws on any
     * validation failure so the caller can show the error inline.
     */
    fun toWgConfig(): Config = buildConfig(interfaceDns)

    /**
     * #740 The config a CONNECT brings up: the same tunnel, but its DNS is the
     * fleet's choice from Configs ▸ Mesh ▸ DNS ([FleetDns.vpnServers]), with
     * this page's DNS field as the fleet resolver the private presets name.
     * Every connect path calls this, so the VPN — and with it every fleet app
     * on the system resolver — carries the one choice. [toWgConfig] stays the
     * literal form contents, which is what an exported .conf must hold.
     * Throws (like any validation failure) when a private preset has no fleet
     * resolver to point at.
     */
    fun toTunnelConfig(): Config =
        buildConfig(FleetDns.vpnServers(app, interfaceDns).joinToString(", "))

    private fun buildConfig(dns: String): Config {
        val ifBuilder = Interface.Builder()
            .parsePrivateKey(interfacePrivateKey)
            .parseAddresses(interfaceAddress)
        if (dns.isNotBlank())                   ifBuilder.parseDnsServers(dns)
        if (interfaceListenPort.isNotBlank())   ifBuilder.parseListenPort(interfaceListenPort)
        if (interfaceMtu.isNotBlank())          ifBuilder.parseMtu(interfaceMtu)

        val cfg = Config.Builder().setInterface(ifBuilder.build())
        for (p in peers()) {
            val pb = Peer.Builder()
                .parsePublicKey(p.publicKey)
                .parseAllowedIPs(p.allowedIps)
            if (p.presharedKey.isNotBlank())          pb.parsePreSharedKey(p.presharedKey)
            if (p.endpoint.isNotBlank())              pb.parseEndpoint(p.endpoint)
            if (p.persistentKeepalive.isNotBlank())   pb.parsePersistentKeepalive(p.persistentKeepalive)
            cfg.addPeer(pb.build())
        }
        return cfg.build()
    }

    /**
     * Write an upstream [Config] into these prefs — the ONE import path.
     *
     * Every importer funnels through here after producing a [Config] with
     * `com.wireguard.config.Config.parse` (the official upstream parser):
     * the .conf file picker in WireGuardFragment, and the Authelia auto-
     * import in [com.diegonmarcos.superapp.profile.ConfigAutoImport]. There
     * is deliberately no second parser, so an auto-imported tunnel behaves
     * byte-for-byte like a manually imported one.
     */
    fun hydrateFromConfig(cfg: Config) {
        val iface = cfg.getInterface()
        interfacePrivateKey = iface.keyPair.privateKey.toBase64()
        interfaceAddress    = iface.addresses.joinToString(", ")
        interfaceDns        = iface.dnsServers.joinToString(", ") { it.hostAddress ?: "" }
        interfaceListenPort = iface.listenPort.map { it.toString() }.orElse("")
        interfaceMtu        = iface.mtu.map { it.toString() }.orElse("")

        savePeers(
            cfg.peers.mapIndexed { i, p ->
                PeerData(
                    name                = "peer-${i + 1}",
                    publicKey           = p.publicKey.toBase64(),
                    presharedKey        = p.preSharedKey.map { it.toBase64() }.orElse(""),
                    endpoint            = p.endpoint.map { it.toString() }.orElse(""),
                    allowedIps          = p.allowedIps.joinToString(", "),
                    persistentKeepalive = p.persistentKeepalive.map { it.toString() }.orElse(""),
                )
            }
        )
    }

    private fun defaultPeersJson(): String {
        val b64 = BuildConfig.UI_WG_PEERS_JSON_B64
        if (b64.isBlank()) return "[]"
        return try {
            String(Base64.decode(b64, Base64.DEFAULT))
        } catch (_: Throwable) {
            "[]"
        }
    }

    private fun parsePeers(raw: String): List<PeerData> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PeerData.fromJson(arr.getJSONObject(it)) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * One WireGuard peer. `name` is a UI-only label so the user can
     * tell "gcp-proxy" apart from "oci-apps" in the list; it's stripped
     * before the upstream [Peer.Builder] is built.
     */
    data class PeerData(
        val name: String,
        val publicKey: String,
        val presharedKey: String,
        val endpoint: String,
        val allowedIps: String,
        val persistentKeepalive: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("public_key", publicKey)
            put("preshared_key", presharedKey)
            put("endpoint", endpoint)
            put("allowed_ips", allowedIps)
            put("persistent_keepalive", persistentKeepalive)
        }

        companion object {
            val EMPTY = PeerData("", "", "", "", "", "")

            fun fromJson(o: JSONObject): PeerData = PeerData(
                name                = o.optString("name", ""),
                publicKey           = o.optString("public_key", ""),
                presharedKey        = o.optString("preshared_key", ""),
                endpoint            = o.optString("endpoint", ""),
                allowedIps          = o.optString("allowed_ips", ""),
                persistentKeepalive = o.optString("persistent_keepalive", ""),
            )
        }
    }

    companion object {
        private const val K_TUNNEL_NAME    = "tunnel_name"
        private const val K_IF_PRIVKEY     = "if_privkey"
        private const val K_IF_ADDRESS     = "if_address"
        private const val K_IF_DNS         = "if_dns"
        private const val K_IF_LISTEN_PORT = "if_listen_port"
        private const val K_IF_MTU         = "if_mtu"
        private const val K_PEERS_JSON     = "peers_json"
        private const val K_TUNNEL_ENABLED = "tunnel_enabled"
        private const val K_PROVIDER       = "config_provider"
        private const val K_PROFILES_JSON  = "profiles_json"
        private const val K_ACTIVE_PROFILE = "active_profile"

        /** The PrivateKey value a stored or exported profile carries in place of the key. */
        const val PROVIDED_BY_DEVICE = "<PROVIDED_BY_DEVICE>"

        /** [conf] with its PrivateKey value replaced by [PROVIDED_BY_DEVICE] (a text with none is unchanged). */
        fun stripPrivateKey(conf: String): String =
            conf.replace(Regex("(?m)^(\\s*PrivateKey\\s*=\\s*).*$"), "$1$PROVIDED_BY_DEVICE")

        /** The PrivateKey value [conf] carries, or null when it carries none or a [PROVIDED_BY_DEVICE] marker. */
        fun privateKeyOf(conf: String): String? =
            Regex("(?m)^\\s*PrivateKey\\s*=\\s*(\\S+)").find(conf)?.groupValues?.get(1)?.takeIf { !it.startsWith("<") }

        /** Public half comes from the fleet preset baked out of build.json. */
        const val PROVIDER_CLOUD  = "cloud"
        /** Public half is whatever the user set in Configs → WireGuard. */
        const val PROVIDER_CUSTOM = "custom"
    }
}
