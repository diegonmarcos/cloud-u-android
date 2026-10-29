package com.diegonmarcos.superapp.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

/**
 * What a Wi-Fi scan needs on this API level (#1263). Every level: fine location (scan results, SSID / BSSID of the connected
 * network and RTT ranging are location data to Android) and location services switched on. API 33+: NEARBY_WIFI_DEVICES as well.
 * Read-only (#1274): the lib checks; asking and granting belong to the app's Permissions page (core perms contract).
 */
object WifiPermissions {
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun missing(ctx: Context, sdk: Int = Build.VERSION.SDK_INT): List<String> =
        required(sdk).filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun granted(ctx: Context): Boolean = missing(ctx).isEmpty()

    fun locationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else @Suppress("DEPRECATION") (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    }

    fun describe(perms: List<String>): String = perms.joinToString(", ") { it.substringAfterLast('.') }
}
