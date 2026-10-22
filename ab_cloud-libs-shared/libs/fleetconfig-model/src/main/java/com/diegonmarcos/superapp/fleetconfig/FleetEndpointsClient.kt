package com.diegonmarcos.superapp.fleetconfig

import android.content.Context
import android.net.Uri

/**
 * #1093 The pull half of the fleet endpoint contract for an app that authenticates to the fleet but is not Cloud Account
 * (Cloud Vault): ask Cloud Account (the source of truth) for every declared service's mesh and public endpoint and, if the
 * caller was granted `fleet.bearer`, the bearer. BLOCKS (a provider call): run it off the main thread. Nothing is typed in
 * or built into the caller; an unreachable Account leaves what an earlier pull put on the transport in place.
 */
object FleetEndpointsClient {
    sealed class Outcome {
        data class Pulled(val published: FleetEndpointsContract.Published) : Outcome()
        /** Cloud Account is not installed. */
        data object AccountMissing : Outcome()
        /** Account answered no (no grant, an unknown version) or did not answer. [why] is a fixed word, never a value. */
        data class Refused(val why: String) : Outcome()
    }

    fun accountInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(FleetEndpointsContract.ACCOUNT_PACKAGE, 0).enabled
    }.getOrDefault(false)

    fun pull(ctx: Context): Outcome {
        if (!accountInstalled(ctx)) return Outcome.AccountMissing
        val reply = runCatching {
            ctx.contentResolver.call(Uri.parse("content://${FleetEndpointsContract.AUTHORITY}"), FleetEndpointsContract.METHOD, null, null)
        }.getOrNull() ?: return Outcome.Refused("no answer")
        if (!reply.getBoolean(FleetEndpointsContract.KEY_OK)) return Outcome.Refused("not granted")
        val published = FleetEndpointsContract.parse(reply.getString(FleetEndpointsContract.KEY_JSON).orEmpty())
            ?: return Outcome.Refused("unreadable")
        return Outcome.Pulled(published)
    }
}
