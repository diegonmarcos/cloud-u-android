package com.diegonmarcos.superapp.recovery

import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.updater.Advisory

/**
 * The SECONDARY advisory surface: an alert, for the user who never opens the
 * app — which is exactly the user whose device has quietly stopped updating.
 * [RecoveryBanner] is primary because it cannot be suppressed; this is the
 * only one that reaches someone who is not looking.
 *
 * #777: raised through [FleetAlerts] into the Alerts group like every other
 * fleet alert, instead of on a channel of its own. What the old channel stood
 * for is kept: STUCK is raised as ERROR, and the Alerts group posts ERROR on
 * its own HIGH-importance channel, so "this device can no longer update
 * itself" still does not share a switch with routine chatter.
 */
object RecoveryNotifier {

    private fun key(item: Advisory.Item) = "advisory:${item.id}"

    /**
     * Raise every advisory that is currently due, respecting the rate limit.
     * Cheap and idempotent — call it from the same places that refresh the
     * banner.
     */
    fun post(ctx: Context, items: List<Advisory.Item>) {
        // A POSTED NOTE IS NOT AN EMERGENCY: INFO items are the message board
        // and live on the banner only; WARN and STUCK are the machine saying
        // something is wrong, and still raise an alert.
        items.filter { it.severity != Advisory.Severity.INFO }
            .filter { Advisory.shouldNotify(ctx, it) }.forEach { item ->
                FleetAlerts.raise(ctx, FleetAlerts.Alert(
                    title = item.title,
                    // The reason travels with a tap target that fixes it.
                    text = item.detail,
                    severity = if (item.severity == Advisory.Severity.STUCK) FleetAlerts.ERROR else FleetAlerts.WARN,
                    deepLink = RecoveryActivity.intent(ctx, item.appId).toUri(Intent.URI_INTENT_SCHEME),
                    dedupeKey = key(item),
                ))
            }
    }

    /** Drop a resolved advisory. A warning that outlives the problem is how
     *  the next real one gets ignored. */
    fun cancel(ctx: Context, item: Advisory.Item) = FleetAlerts.withdraw(ctx, key(item))

    /** Called when the user reaches the recovery screen: they are now looking
     *  at the fix, so the pointer to it has done its job. */
    fun cancelAll(ctx: Context) {
        val fleet = runCatching {
            com.diegonmarcos.superapp.updater.Fleet.parse(
                com.diegonmarcos.superapp.updater.BuildConfig.CONSTELLATION_FLEET_B64)
        }.getOrDefault(emptyList())
        Advisory.current(ctx, fleet).forEach { cancel(ctx, it) }
    }
}
