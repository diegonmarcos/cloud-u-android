package com.diegonmarcos.superapp.onehand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the edge handles back after a reboot or an app update — only when the
 *  switch is ON and the overlay permission is held (#775: OFF stays off). */
class OneHandBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        OneHandController.startIfEnabled(ctx)
    }
}
