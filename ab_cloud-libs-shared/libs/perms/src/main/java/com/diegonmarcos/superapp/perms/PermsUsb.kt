package com.diegonmarcos.superapp.perms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * #1375 the catalogue's `usb_host` entry: USB host access to a plugged-in drive (USB mass storage, class 8).
 *
 * Android has no manifest permission for this: the grant is per DEVICE, asked by UsbManager.requestPermission when the
 * drive is attached (or given up front when the app was picked in the "Choose an app for the USB device" chooser, which
 * the USB_DEVICE_ATTACHED filter puts it in). It lives here, with every other grant, so an app never calls
 * requestPermission itself (the libs:perms contract) and the Perms page shows the live state:
 *   no USB host on this device          -> not applicable
 *   no drive plugged in                 -> not applicable ("Android asks when a drive is plugged in")
 *   every plugged-in drive granted      -> granted, else denied
 */
object PermsUsb {
    const val ID = "usb_host"
    const val USB_CLASS_MASS_STORAGE = 8
    private const val ACTION = "com.diegonmarcos.superapp.perms.USB_PERMISSION"

    fun hasUsbHost(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun isMassStorage(d: UsbDevice): Boolean =
        d.deviceClass == USB_CLASS_MASS_STORAGE || (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == USB_CLASS_MASS_STORAGE }

    /** The plugged-in USB drives (mass storage only: a keyboard or a hub is not a drive). */
    fun drives(ctx: Context): List<UsbDevice> = runCatching {
        ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.filter(::isMassStorage).orEmpty()
    }.getOrDefault(emptyList())

    fun granted(ctx: Context, d: UsbDevice): Boolean = runCatching { ctx.getSystemService(UsbManager::class.java)?.hasPermission(d) == true }.getOrDefault(false)

    fun status(ctx: Context): PermStatus {
        if (!hasUsbHost(ctx)) return PermStatus(PermState.NOT_APPLICABLE, "this device has no USB host")
        val ds = drives(ctx)
        if (ds.isEmpty()) return PermStatus(PermState.NOT_APPLICABLE, "no USB drive plugged in: Android asks when one is")
        val ok = ds.count { granted(ctx, it) }
        return if (ok == ds.size) PermStatus(PermState.GRANTED) else PermStatus(PermState.DENIED, "${ds.size - ok} of ${ds.size} drive(s) not allowed yet")
    }

    /**
     * Asks Android for [device] (the system dialog "Allow <app> to access <drive>?"); [onResult] runs once on the main
     * thread with the answer. Already granted answers true at once without a dialog.
     */
    fun request(ctx: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val um = app.getSystemService(UsbManager::class.java) ?: return onResult(false)
        if (um.hasPermission(device)) return onResult(true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { app.unregisterReceiver(this) }
                onResult(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || um.hasPermission(device))
            }
        }
        val filter = IntentFilter(ACTION)
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The system fills in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED, so the PendingIntent is mutable; it is explicit
        // (package-scoped), as Android 14 requires of a mutable one.
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, device.deviceId, Intent(ACTION).setPackage(app.packageName), flags or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { um.requestPermission(device, pi) }.onFailure { runCatching { app.unregisterReceiver(receiver) }; onResult(false) }
    }
}
