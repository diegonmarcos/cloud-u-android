package com.diegonmarcos.superapp.network

import android.content.Context
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.VaultCockpit
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * #867 The mesh tunnel as libs:account sees it: this app's WireGuard prefs behind [AccountHost.Mesh].
 * Account compares a vault device's declared profiles against [state] and lands one through [apply].
 */
object AccountMesh : AccountHost.Mesh {

    override fun interfaceAddress(ctx: Context): String = WgState.prefs(ctx).interfaceAddress

    override fun state(ctx: Context): VaultCockpit.TunnelState = tunnelState(WgState.prefs(ctx))

    override fun apply(ctx: Context, name: String, conf: String): String = applyMesh(WgState.prefs(ctx), name, conf)

    fun tunnelState(prefs: WireGuardPrefs) = VaultCockpit.TunnelState(
        prefs.tunnelName, prefs.interfaceAddress, prefs.peers().map { it.publicKey }.toSet())

    /** The vault's conf goes through the SAME parser the .conf import uses; a
     *  text the WireGuard parser rejects writes nothing. Returns the report line. */
    fun applyMesh(prefs: WireGuardPrefs, name: String, conf: String): String {
        val cfg = try {
            Config.parse(BufferedReader(StringReader(conf)))
        } catch (t: Throwable) {
            return "✗ $name rejected by the WireGuard parser: ${t.message}"
        }
        prefs.tunnelName = name.take(15)
        prefs.hydrateFromConfig(cfg)
        prefs.configProvider = WireGuardPrefs.PROVIDER_CUSTOM
        return "✓ $name applied: ${cfg.peers.size} peers, key from the vault"
    }
}
