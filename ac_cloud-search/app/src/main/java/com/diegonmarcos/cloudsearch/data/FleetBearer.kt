package com.diegonmarcos.cloudsearch.data

import android.content.Context
import android.net.Uri

/**
 * #903 The fleet bearer the price service (scrappers-api behind api.diegonmarcos.com) wants, read
 * from the two places the fleet keeps it and never stored, logged or echoed here: Cloud Account's
 * `fleet.bearer` secret (behind the grant the owner gives this package in Account ▸ Secrets), then
 * SuperApp's read-only bearer provider. Both providers sit behind CONSTELLATION_DATA (signature).
 * "" means no sign-in: the Things page then says the store prices need it and shows no price.
 */
object FleetBearer {
    private const val ACCOUNT_AUTHORITY = "com.diegonmarcos.cloudaccount.accountdata"
    private const val SUPERAPP_AUTHORITY = "com.diegonmarcos.superapp.fleetbearer"

    /** Tests replace the provider reads. */
    @Volatile var reader: (Context) -> String = { read(it) }

    fun token(ctx: Context): String? = reader(ctx).takeIf { it.isNotBlank() }

    private fun read(ctx: Context): String {
        val fromAccount = runCatching {
            ctx.contentResolver.call(Uri.parse("content://$ACCOUNT_AUTHORITY"), "secret", "fleet.bearer", null)
        }.getOrNull()?.takeIf { it.getBoolean("ok") }?.getString("value").orEmpty().trim()
        if (fromAccount.isNotEmpty()) return fromAccount
        return runCatching {
            ctx.contentResolver.call(Uri.parse("content://$SUPERAPP_AUTHORITY"), "bearer", null, null)
        }.getOrNull()?.takeIf { it.getBoolean("ok") }?.getString("token").orEmpty().trim()
    }
}
