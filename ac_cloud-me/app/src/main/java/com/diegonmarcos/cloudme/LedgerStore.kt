package com.diegonmarcos.cloudme

import android.content.Context
import android.util.Base64
import org.json.JSONArray

/**
 * The one place a section's user data touches disk: one JSON string under `data` in the SharedPreferences file named
 * after the store. `buro_user`, `llc_user`, `experiences_user` and `inventory_user` (#1367) are declared in ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json
 * (config, migrates), so the backup and the new-phone migration carry them.
 */
object LedgerStore {
    const val BURO = "buro_user"
    const val LLC = "llc_user"
    const val EXP = "experiences_user"
    const val INV = "inventory_user"
    private const val KEY = "data"

    /** Spelled with literal names so the fleet-config guard sees both stores (a name built at runtime cannot be checked). */
    private fun prefs(ctx: Context, store: String) = when (store) {
        BURO -> ctx.getSharedPreferences("buro_user", Context.MODE_PRIVATE)
        LLC -> ctx.getSharedPreferences("llc_user", Context.MODE_PRIVATE)
        EXP -> ctx.getSharedPreferences("experiences_user", Context.MODE_PRIVATE)
        INV -> ctx.getSharedPreferences("inventory_user", Context.MODE_PRIVATE)
        else -> throw IllegalArgumentException("not a ledger store: $store")
    }

    fun load(ctx: Context, store: String): LedgerData {
        val d = LedgerData.fromJson(prefs(ctx, store).getString(KEY, null))
        if (store != EXP) return d
        // #1350 the old Experiences records move to their Achieves tab once; saved at once so the move is not redone.
        val spec = runCatching {
            AchievesMigration.declared(JSONArray(String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT))), store)
        }.getOrNull() ?: return d
        if (spec.legacy.keys.none { it in d.collections() }) return d
        return AchievesMigration.migrate(d, spec).also { save(ctx, store, it) }
    }

    fun save(ctx: Context, store: String, d: LedgerData) {
        prefs(ctx, store).edit().putString(KEY, d.toJson()).apply()
    }
}
