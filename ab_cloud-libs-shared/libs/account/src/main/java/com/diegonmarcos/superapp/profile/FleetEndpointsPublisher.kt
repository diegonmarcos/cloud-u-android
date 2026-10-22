package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.fleetconfig.FleetEndpointsContract
import com.diegonmarcos.superapp.settings.AccountVault

/**
 * #1093 What Cloud Account publishes to the other apps over its accountdata provider: the endpoint pair of every
 * fleet service in the host's ONE declaration ([AccountHost.fleetEndpointsDecl]), and the fleet bearer for a caller
 * also granted `fleet.bearer`. The caller was checked for `fleet.endpoints` before this runs. Cloud Mail's own
 * (per-account) view of the same declaration is [MailEndpointsPublisher].
 */
object FleetEndpointsPublisher {
    fun publish(ctx: Context, caller: String, vault: AccountVault = AccountVault(ctx)): Bundle {
        val entries = FleetEndpointsContract.entries(FleetEndpointsContract.parseDecl(AccountHost.fleetEndpointsDecl()))
        // The bearer is for the public path, so it is only worth sending when some service has one.
        val mayHaveBearer = entries.any { it.public.isNotEmpty() } &&
            (caller == ctx.packageName || vault.grants.allow(caller, FleetEndpointsContract.GRANT_BEARER))
        val bearer = if (mayHaveBearer) vault.connection(FleetEndpointsContract.GRANT_BEARER)?.toString().orEmpty() else ""
        return Bundle().apply {
            putBoolean(FleetEndpointsContract.KEY_OK, true)
            putString(FleetEndpointsContract.KEY_JSON, FleetEndpointsContract.toJson(entries, bearer))
        }
    }
}
