package com.diegonmarcos.superapp.devtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * #733 The mesh-membership marker. Every app linking libs:devtools exports
 * this receiver for `com.diegonmarcos.cloud.action.MESH_MEMBER`, and the same
 * manifest queries that action — which is what makes every member visible to
 * every other one on Android 11+ (see the manifest note). It is matched, never
 * meaningfully called, so receiving does nothing.
 */
class FleetMemberReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
