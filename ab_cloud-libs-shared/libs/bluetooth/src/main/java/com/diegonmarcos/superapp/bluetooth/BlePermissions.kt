package com.diegonmarcos.superapp.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The runtime permissions Bluetooth needs on this API level (#1221). The lib's manifest declares them all; this says which are asked for. */
object BlePermissions {
    /** API 31+: scan and connect. API 26-30: location (a scan needs it; BLUETOOTH and BLUETOOTH_ADMIN are install-time). */
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(ctx: Context, sdk: Int = Build.VERSION.SDK_INT): List<String> =
        required(sdk).filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun granted(ctx: Context): Boolean = missing(ctx).isEmpty()

    /** `BLUETOOTH_SCAN, BLUETOOTH_CONNECT`: the names the page shows next to the Grant button. */
    fun describe(perms: List<String>): String = perms.joinToString(", ") { it.substringAfterLast('.') }
}
