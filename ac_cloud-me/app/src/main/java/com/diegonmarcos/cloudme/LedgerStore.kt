package com.diegonmarcos.cloudme

import android.content.Context

/**
 * The one place a section's user data touches disk: one JSON string under `data` in the SharedPreferences file named
 * after the store. `buro_user` and `llc_user` are declared in ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json
 * (config, migrates), so the backup and the new-phone migration carry them.
 */
object LedgerStore {
    const val BURO = "buro_user"
    const val LLC = "llc_user"
    private const val KEY = "data"

    /** Spelled with literal names so the fleet-config guard sees both stores (a name built at runtime cannot be checked). */
    private fun prefs(ctx: Context, store: String) = when (store) {
        BURO -> ctx.getSharedPreferences("buro_user", Context.MODE_PRIVATE)
        LLC -> ctx.getSharedPreferences("llc_user", Context.MODE_PRIVATE)
        else -> throw IllegalArgumentException("not a ledger store: $store")
    }

    fun load(ctx: Context, store: String): LedgerData =
        LedgerData.fromJson(prefs(ctx, store).getString(KEY, null))

    fun save(ctx: Context, store: String, d: LedgerData) {
        prefs(ctx, store).edit().putString(KEY, d.toJson()).apply()
    }
}
