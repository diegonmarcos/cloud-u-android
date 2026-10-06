package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.fleetconfig.FleetPolicy
import com.diegonmarcos.superapp.fleetconfig.SetupContract
import com.diegonmarcos.superapp.settings.AccountVault
import org.json.JSONObject

/**
 * #874 Taking in what the fleet already holds, through the #873 setup contract, as Cloud Account:
 *
 *  - [pull]: every Connections path a `bundle` route marks `pull` (DaguPrefs' bearer and server, the DNS
 *    preset, the WireGuard tunnel, the mail login - the stores SuperApp kept) is read from that app's
 *    `export` and filed in the vault, ONLY where the vault holds nothing yet. Nothing is deleted from the app:
 *    the next Fleet Setup pushes the vault's value back, and from then on the vault is the source.
 *  - [capture]: every installed app's export, filed under Configs (`app > store > file > key`).
 *
 * Values travel through the contract and the vault only: never logged, never in a result line.
 */
object AccountMigrate {

    data class Filled(val path: String, val from: String)

    fun pull(vault: AccountVault, m: FleetPolicy.Manifest, installed: (String) -> Boolean, t: FleetSetup.Transport): List<Filled> {
        val out = ArrayList<Filled>()
        for ((path, routes) in SetupPlan.routes(m)) {
            if (vault.connection(path) != null) continue
            for (r in routes) {
                val app = r.pull?.let { m.apps[it] } ?: continue
                if (!installed(app.pkg)) continue
                val reply = t.call(app.pkg, SetupContract.METHOD_EXPORT, r.store, null) as? SetupContract.Reply.Ok ?: continue
                val files = reply.json.optJSONObject("stores")?.optJSONObject(r.store) ?: continue
                val v = files.keys().asSequence().mapNotNull { f -> files.optJSONObject(f)?.opt(r.key) }.firstOrNull() ?: continue
                if (v is String && v.isBlank()) continue
                vault.putConnection(path, v)
                out += Filled(path, app.id)
                break
            }
        }
        return out
    }

    fun capture(vault: AccountVault, m: FleetPolicy.Manifest, installed: (String) -> Boolean, t: FleetSetup.Transport): List<String> {
        val out = ArrayList<String>()
        for (app in m.apps.values) {
            if (!installed(app.pkg)) continue
            val r = t.call(app.pkg, SetupContract.METHOD_EXPORT, null, null) as? SetupContract.Reply.Ok ?: continue
            vault.captureConfigs(app.id, r.json)
            out += app.id
        }
        return out
    }

    /** The one-shot on Cloud Account's first start with SuperApp installed; a no-op once the vault holds the values. BLOCKS. */
    fun run(ctx: android.content.Context): List<Filled> {
        val m = runCatching { AccountFleet.manifest(ctx) }.getOrNull() ?: return emptyList()
        return pull(AccountVault(ctx), m, { FleetSetup.installed(ctx, it) }, FleetSetup.transport(ctx))
    }
}
