package com.diegonmarcos.cloudsearch.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #903 The person's place for the Things search: ACCESS_COARSE_LOCATION only (a city-block fix from
 * the network provider), asked once ([Prefs.locationAsked]), used to centre the search and never
 * stored, logged or sent finer than ~1 km (Things.Area.coarse). Null = no fix: no permission, location
 * off, or nothing within [WAIT_MS]; the caller falls back to the city typed in settings.
 */
object Locator {
    data class Fix(val lat: Double, val lon: Double)

    private const val WAIT_MS = 8_000L
    private const val MAX_AGE_MS = 6 * 3_600_000L

    /** Tests replace the platform read. */
    @Volatile var reader: (Context) -> Fix? = { read(it) }

    fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun fix(ctx: Context): Fix? = if (granted(ctx)) reader(ctx) else null

    @Suppress("MissingPermission")
    private fun read(ctx: Context): Fix? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        val ageMs = last?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
        if (last != null && ageMs != null && ageMs < MAX_AGE_MS) return Fix(last.latitude, last.longitude)
        val latch = CountDownLatch(1)
        var got: Location? = null
        val exec = Executors.newSingleThreadExecutor()
        try {
            val consumer = androidx.core.util.Consumer<Location?> { loc -> got = loc; latch.countDown() }
            runCatching { LocationManagerCompat.getCurrentLocation(lm, LocationManager.NETWORK_PROVIDER, null as android.os.CancellationSignal?, exec, consumer) }
                .onFailure { return last?.let { Fix(it.latitude, it.longitude) } }
            latch.await(WAIT_MS, TimeUnit.MILLISECONDS)
        } finally {
            exec.shutdown()
        }
        return (got ?: last)?.let { Fix(it.latitude, it.longitude) }
    }
}
