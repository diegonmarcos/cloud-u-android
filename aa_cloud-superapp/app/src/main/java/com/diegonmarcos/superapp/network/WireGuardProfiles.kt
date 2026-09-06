package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * The four exportable tunnel profiles, rendered as wg-quick text.
 *
 * WHY FOUR FILES AND NOT EIGHT. This phone is a single-tunnel mesh spoke —
 * Android runs one WireGuard profile at a time — so each file is ONE merged
 * config carrying BOTH meshes as two peers: gcp-proxy for wg0 and
 * oci-analytics for wg-public. The matrix is the two axes that actually change
 * on a phone: which address family the wifi gives you (v4 / v6) and how much of
 * your traffic you want inside the tunnel (split / full).
 *
 * WHY THIS IS NOT [WireGuardPrefs]. Nothing here is ever written to prefs.
 * These values come from `build.json::ui.wireguard_profiles`, a block kept
 * deliberately separate from `ui.wireguard_default`: the default seeds the
 * in-app form and is what [WireGuardPrefs.matchesCloudPreset] compares
 * against, so folding a richer config into it would make every existing
 * install read as drifted and offer to replace a tunnel the user may have
 * hand-built. This object only ever produces TEXT.
 *
 * WHAT MUST NOT BE "TIDIED". The two IPv6 prefixes are not interchangeable and
 * not redundant: `fd0c:1d00::9` is this phone's wg0 identity and
 * `fd0c:1d01::9` is its wg-public identity, and both belong on every Address
 * line. Handing `fd0c:1d00::` traffic to oci-analytics gets it cryptokey-
 * dropped — its allowed source for this phone is `fd0c:1d01::9` — which is the
 * 14-30s happy-eyeballs stall, not a hang. Likewise the gcp-proxy endpoint is
 * udp/443 (redirected to 51820) because 51820 is filtered on the networks this
 * matrix exists for, the MTU is 1380, and DNS is mesh-only. None of those are
 * defaults to be normalised towards `ui.wireguard_default`, whose values are
 * the in-app form's and differ.
 *
 * NO PRIVATE KEY. [render] never emits one and there is nowhere in the source
 * block to put one. The file says so where the key would have gone.
 */
object WireGuardProfiles {

    /** One exportable profile. Everything is a String — this is a renderer,
     *  not a validator; the fleet's own configs are the source of truth. */
    data class Profile(
        val id: String,
        val label: String,
        val comment: String,
        val dns: String,
        val peers: List<Peer>,
    ) {
        /** The name the exported file gets, matching the vault's own. */
        val fileName: String get() = "config-$id.conf"
    }

    data class Peer(
        val name: String,
        val comment: String,
        val publicKey: String,
        val endpoint: String,
        val allowedIps: String,
        val persistentKeepalive: String,
    )

    /** Decoded once — the blob is baked into the APK and cannot change under
     *  us, so re-parsing it per tab draw would be pure waste. */
    private val root: JSONObject by lazy {
        runCatching {
            JSONObject(String(android.util.Base64.decode(
                BuildConfig.UI_WG_PROFILES_JSON_B64, android.util.Base64.DEFAULT)))
        }.getOrDefault(JSONObject())
    }

    val interfaceAddress: String get() = root.optString("interface_address")
    val interfaceMtu: String get() = root.optString("interface_mtu")

    /** The four profiles, in build.json order. Empty if the blob is missing or
     *  malformed — the caller shows "no profiles in this build" rather than
     *  exporting something half-formed. */
    val all: List<Profile> by lazy {
        val array = root.optJSONArray("profiles") ?: return@lazy emptyList()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val peers = o.optJSONArray("peers")
            Profile(
                id = o.optString("id"),
                label = o.optString("label"),
                comment = o.optString("comment"),
                dns = o.optString("dns"),
                peers = (0 until (peers?.length() ?: 0)).mapNotNull { j ->
                    val p = peers?.optJSONObject(j) ?: return@mapNotNull null
                    Peer(
                        name = p.optString("name"),
                        comment = p.optString("comment"),
                        publicKey = p.optString("public_key"),
                        endpoint = p.optString("endpoint"),
                        allowedIps = p.optString("allowed_ips"),
                        persistentKeepalive = p.optString("persistent_keepalive"),
                    )
                },
            )
        }
    }

    fun byId(id: String): Profile? = all.firstOrNull { it.id == id }

    /**
     * Render [profile] as a wg-quick `.conf`.
     *
     * The PrivateKey line is present but EMPTY, with the reason beside it.
     * Omitting the line entirely would produce a file that looks complete and
     * fails at import with a parser error; leaving it blank and named is the
     * version that tells the reader what to do. The key is not written because
     * this file leaves the app for a location the app does not control — the
     * whole point of holding it in [WireGuardPrefs] and never displaying it is
     * undone by writing it to shared storage.
     */
    fun render(profile: Profile): String = buildString {
        appendLine("# ${profile.comment}")
        appendLine("# Exported by SuperApp from build.json::ui.wireguard_profiles.")
        appendLine("# Both v6 prefixes below are required and are NOT interchangeable:")
        appendLine("#   fd0c:1d00::9 = wg0 identity      (peer gcp-proxy)")
        appendLine("#   fd0c:1d01::9 = wg-public identity (peer oci-analytics)")
        appendLine("# Sending fd0c:1d00:: traffic to oci-analytics is cryptokey-dropped there.")
        appendLine()
        appendLine("[Interface]")
        appendLine("# NOT EXPORTED. This device's private key stays in the app and is never")
        appendLine("# written to shared storage. Paste it after the = below, or generate a")
        appendLine("# new pair in the app and register its public half on the hub.")
        appendLine("#")
        appendLine("# THE WIREGUARD APP WILL REFUSE THIS FILE UNTIL YOU FILL THAT IN. That is")
        appendLine("# expected, not a broken export: a config is not importable without a key,")
        appendLine("# and a placeholder that parsed would build a tunnel that silently never")
        appendLine("# handshakes.")
        appendLine("PrivateKey = ")
        appendLine("Address = $interfaceAddress")
        if (interfaceMtu.isNotBlank()) appendLine("MTU = $interfaceMtu")
        appendLine("DNS = ${profile.dns}")
        profile.peers.forEach { peer ->
            appendLine()
            appendLine("[Peer]")
            peer.comment.lines().forEach { appendLine(if (it.startsWith("#")) it else "# $it") }
            appendLine("PublicKey = ${peer.publicKey}")
            appendLine("Endpoint = ${peer.endpoint}")
            appendLine("AllowedIPs = ${peer.allowedIps}")
            appendLine("PersistentKeepalive = ${peer.persistentKeepalive}")
        }
    }
}
