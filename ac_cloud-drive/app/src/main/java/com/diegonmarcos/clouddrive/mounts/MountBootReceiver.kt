package com.diegonmarcos.clouddrive.mounts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the mount keeper after a reboot or an app update, but only if mounts were on when it last
 * ran (persisted ids) and the declaration allows it (mount_keeper.restore_on_boot / restore_on_update).
 * specialUse is a foreground-service type Android 15 still lets BOOT_COMPLETED start.
 */
class MountBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = MountKeeper.policy
        val allowed = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT -> p.restoreOnBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> p.restoreOnUpdate
            else -> false
        }
        if (allowed && MountKeeper.wanted(context)) MountKeeper.start(context)
    }

    private companion object { const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON" }
}
