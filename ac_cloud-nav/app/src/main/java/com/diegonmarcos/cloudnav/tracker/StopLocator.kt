package com.diegonmarcos.cloudnav.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where a stop is, found the cheapest way (#1114). Same order as the SuperApp's Weather badge (#1108, WeatherLocation):
 * (1) a fix we already hold when it is fresh, (2) the system's cached fixes - network (Wi-Fi + cell), then passive, then
 * GPS - when fresh, (3) ONE active request to the network provider (no GPS radio), (4) an active GPS request only as the
 * last resort; if all of that fails the newest stale fix beats nothing. Pure: the platform sits behind [Probe].
 */
object StopLocator {
    const val NETWORK = "network"
    const val PASSIVE = "passive"
    const val GPS = "gps"

    const val FRESH_MS = 30 * 60_000L
    const val NETWORK_TIMEOUT_MS = 10_000L
    const val GPS_TIMEOUT_MS = 25_000L

    /** Cached fixes the system already holds, cheapest first. */
    val PEEK_ORDER = listOf(NETWORK, PASSIVE, GPS)

    /** Providers asked for a NEW fix, cheapest first; GPS is the last resort. */
    val REQUEST_ORDER = listOf(NETWORK, GPS)

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long, val source: String) {
        val valid: Boolean
            get() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0) && timeMs > 0
    }

    data class Env(val coarse: Boolean, val fine: Boolean)

    interface Probe {
        fun enabled(provider: String): Boolean
        /** The system's cached fix; costs nothing. */
        fun lastKnown(provider: String): Fix?
        /** A new fix, blocking up to [timeoutMs]; costs radio. */
        fun request(provider: String, timeoutMs: Long): Fix?
    }

    private fun allowed(env: Env, provider: String) = if (provider == GPS) env.fine else env.coarse || env.fine
    private fun fresh(now: Long, f: Fix) = now - f.timeMs in 0..FRESH_MS

    /** [held] = a fix the engine already stored (its network/cell fixes). Null when nothing at all can be had. */
    fun resolve(now: Long, env: Env, probe: Probe, held: Fix? = null): Fix? {
        if (held != null && held.valid && fresh(now, held)) return held
        if (!env.coarse && !env.fine) return held?.takeIf { it.valid }
        val usable = PEEK_ORDER.filter { allowed(env, it) && probe.enabled(it) }
        for (p in usable) probe.lastKnown(p)?.takeIf { it.valid && fresh(now, it) }?.let { return it }
        for (p in REQUEST_ORDER.filter { it in usable })
            probe.request(p, if (p == GPS) GPS_TIMEOUT_MS else NETWORK_TIMEOUT_MS)?.takeIf { it.valid }?.let { return it }
        return (listOfNotNull(held) + usable.mapNotNull { runCatching { probe.lastKnown(it) }.getOrNull() }).filter { it.valid }.maxByOrNull { it.timeMs }
    }

    fun env(ctx: Context): Env {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return Env(granted(android.Manifest.permission.ACCESS_COARSE_LOCATION), granted(android.Manifest.permission.ACCESS_FINE_LOCATION))
    }

    /** Platform side of [Probe]. */
    class AndroidProbe(private val ctx: Context) : Probe {
        private val lm get() = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        override fun enabled(provider: String): Boolean = provider == PASSIVE || runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)

        @SuppressLint("MissingPermission")
        override fun lastKnown(provider: String): Fix? = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.toFix(provider)

        @SuppressLint("MissingPermission")
        override fun request(provider: String, timeoutMs: Long): Fix? = runCatching {
            val got = arrayOfNulls<Location>(1)
            val latch = CountDownLatch(1)
            val cancel = androidx.core.os.CancellationSignal()
            LocationManagerCompat.getCurrentLocation(lm, provider, cancel, { it.run() }, Consumer<Location?> { got[0] = it; latch.countDown() })
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
            got[0]?.toFix(provider)
        }.getOrNull()

        private fun Location.toFix(provider: String) =
            Fix(latitude, longitude, if (hasAccuracy()) accuracy else -1f, if (time > 0) time else System.currentTimeMillis(), provider)
    }
}
