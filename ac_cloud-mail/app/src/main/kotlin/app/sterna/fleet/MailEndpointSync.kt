package app.sterna.fleet

import android.content.Context
import android.net.Uri
import app.sterna.core.data.account.EndpointSyncReport
import app.sterna.core.data.account.FleetEndpointWiring
import app.sterna.core.data.account.PublishedEndpoint
import app.sterna.core.data.account.StoredAccount
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract

/**
 * #1090 The pull half of the endpoint contract: ask Cloud Account (the source of truth) for each fleet mail
 * account's mesh URL, public URL and the fleet bearer, and hand them to [FleetEndpointWiring]. BLOCKS (a
 * provider call): run it off the main thread. Nothing is typed in or built into Mail; an unreachable Account
 * leaves what was synced before in place.
 */
object MailEndpointSync {
    sealed class Outcome {
        data class Synced(val report: EndpointSyncReport) : Outcome()
        /** Cloud Account is not installed: the accounts keep what they have. */
        data object AccountMissing : Outcome()
        /** Account answered no (no grant, nothing declared, an unknown version) or did not answer. [why] is a fixed word, never a value. */
        data class Refused(val why: String) : Outcome()
    }

    fun accountInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(MailEndpointsContract.ACCOUNT_PACKAGE, 0).enabled
    }.getOrDefault(false)

    fun pull(ctx: Context, wiring: FleetEndpointWiring, accounts: List<StoredAccount>, now: Long = System.currentTimeMillis()): Outcome {
        if (!accountInstalled(ctx)) return Outcome.AccountMissing
        val reply = runCatching {
            ctx.contentResolver.call(Uri.parse("content://${MailEndpointsContract.AUTHORITY}"), MailEndpointsContract.METHOD, null, null)
        }.getOrNull() ?: return Outcome.Refused("no answer")
        if (!reply.getBoolean(MailEndpointsContract.KEY_OK)) return Outcome.Refused("not granted")
        val published = MailEndpointsContract.parse(reply.getString(MailEndpointsContract.KEY_JSON).orEmpty())
            ?: return Outcome.Refused("unreadable")
        return Outcome.Synced(wiring.applySync(accounts, published.entries.map(::toPublished), published.bearer, now))
    }

    fun toPublished(e: MailEndpointsContract.Entry) = PublishedEndpoint(e.email, e.mesh, e.public, EndpointPolicy.parse(e.policy))
}
