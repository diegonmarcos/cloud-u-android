package com.diegonmarcos.cloudwebserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts [KeepAliveService] at boot and after this app is updated, so the local servers never wait for the UI. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(Intent(context, KeepAliveService::class.java))
    }
}
