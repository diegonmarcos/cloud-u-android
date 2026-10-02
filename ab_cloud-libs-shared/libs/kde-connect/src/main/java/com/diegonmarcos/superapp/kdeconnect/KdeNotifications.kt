package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import com.diegonmarcos.superapp.core.FleetAlerts

/** KDE-Connect alerts — pings, shares, file transfers and mirrored
 *  notifications from paired devices. #777: raised as fleet alerts, so they
 *  land in the SuperApp's Alerts group instead of a "kdeconnect" channel of
 *  their own; `key` (else the title) is the dedupe key, so repeated updates
 *  of the same remote notification replace rather than stack. */
object KdeNotifications {

    fun post(ctx: Context, title: String, text: String, key: String? = null) {
        val failed = title.contains("fail", ignoreCase = true) || text.contains("fail", ignoreCase = true)
        FleetAlerts.raise(ctx, FleetAlerts.Alert(
            title = title, text = text,
            severity = if (failed) FleetAlerts.WARN else FleetAlerts.INFO,
            // No key = keyed by title: the battery plugin re-posts on every
            // packet, and one line per peer is the news, not one per packet.
            dedupeKey = "kde:" + (key ?: title),
        ))
    }
}
