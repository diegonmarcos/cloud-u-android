package com.diegonmarcos.superapp.kdeconnect

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * kdeconnect.lock — report + control the phone lock state. Reading the state
 * (KeyguardManager) needs no permission; LOCKING needs the device-admin policy
 * ([KdeDeviceAdminReceiver]) which the user enrols once on the Permissions page —
 * until then a lock request posts a note and opens that page (#1274).
 */
object LockDevicePlugin : KdePlugin {
    private const val REQUEST = "kdeconnect.lock.request"
    private const val STATE   = "kdeconnect.lock"

    override val id = "lockdevice"
    override val incoming = setOf(REQUEST, STATE)
    override val outgoing = setOf(STATE, REQUEST)

    /** Sender — ask the desktop to lock (or unlock) itself. */
    fun lockRemote(locked: Boolean = true) =
        NetworkPacket.of(REQUEST) { put("setLocked", locked) }
    fun requestState() = NetworkPacket.of(REQUEST) { put("requestLocked", true) }

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        when (packet.type) {
            REQUEST -> if (packet.getBoolean("requestLocked")) sendState(ctx, link)
            STATE   -> if (packet.has("setLocked")) {
                if (packet.getBoolean("setLocked")) lock(ctx)
                sendState(ctx, link)
            }
        }
        return true
    }

    private fun sendState(ctx: Context, link: KdeLink) {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        link.send(NetworkPacket.of(STATE) { put("isLocked", km.isDeviceLocked) })
    }

    private fun lock(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        // lockNow() needs an active force-lock admin of THIS package: this lib's receiver or the host's own (the catalogue's
        // device_admin entry enables the first one the package declares).
        val active = dpm.activeAdmins.orEmpty().any { it.packageName == ctx.packageName }
        if (active) {
            runCatching { dpm.lockNow() }
        } else {
            // #1274 the Permissions page is the one place that enables it; this plugin never opens the admin page itself.
            KdeNotifications.post(ctx, "Remote lock",
                "Permissions needed: Device admin. Open Permissions to allow locking from the desktop.")
            com.diegonmarcos.superapp.perms.PermsLink.open(ctx, PERMS_FEATURE, listOf("device_admin"))
        }
    }

    /** The catalogue feature (perms-catalogue.json) whose entries this plugin needs. */
    const val PERMS_FEATURE = "kde-connect"
}
