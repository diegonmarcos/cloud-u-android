package com.diegonmarcos.cloudwebserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts [KeepAliveService] at boot and after this app is updated, so the local servers never wait for the UI. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("C3BootReceiver", "start keep-alive service on " + intent.action)
        context.startForegroundService(Intent(context, KeepAliveService::class.java))
    }
}
