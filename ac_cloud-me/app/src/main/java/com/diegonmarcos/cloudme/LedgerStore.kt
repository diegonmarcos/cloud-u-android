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

    fun load(ctx: Context, store: String): LedgerData =
        LedgerData.fromJson(ctx.getSharedPreferences(store, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(ctx: Context, store: String, d: LedgerData) {
        ctx.getSharedPreferences(store, Context.MODE_PRIVATE).edit().putString(KEY, d.toJson()).apply()
    }
}
