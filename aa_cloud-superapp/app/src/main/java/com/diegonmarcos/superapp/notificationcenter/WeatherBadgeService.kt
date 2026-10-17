package com.diegonmarcos.superapp.notificationcenter

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The "Weather" badge: current temperature, the day's max/min and the
 * hour-by-hour forecast for today, titled with the place name.
 *
 * A foreground service for the same reason [HealthBadgeService] is one: this
 * badge is declared persistent, and an ongoing notify from a dead process is
 * droppable. Started ONLY by [BadgeServices.ensureAll] from the declaration —
 * no bespoke start call anywhere, which is the whole point of #515.
 *
 * DATA ROUTE, and why. MET Norway (api.met.no, the model behind yr.no): no
 * API key, only an identifying User-Agent, and ONE `locationforecast/complete`
 * request answers everything — current temp, the day's hourly temps (max/min
 * derived from them) and the probability of precipitation the badge shows as
 * "💧40%". It replaced Open-Meteo, whose free tier is rate-shared and whose
 * page was a developer docs form rather than a forecast. Parsing lives in the
 * pure [WeatherModel]. The position is
 * the device's LAST KNOWN location — passive, no fix is ever requested, so
 * this badge costs zero battery for location. The place name comes from
 * Android's own [Geocoder]; when it has no backend the coordinates are shown
 * instead, because a wrong name is worse than an honest number.
 *
 * NOTHING HERE IS ESTIMATED. No location yet → the badge says so and waits
 * for the next refresh; a failed fetch keeps the previous face and says when
 * it last succeeded, never inventing a number.
 */
class WeatherBadgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Foreground within the first few seconds or the platform kills us —
        // post the "reading…" face immediately, fill in when the fetch lands.
        startForeground(NOTIF_ID, build(null))
        schedule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        tick?.let(main::removeCallbacks)
        scope.cancel()
        NotifyGroups.release(this, BADGE_ID)
        super.onDestroy()
    }

    /** Re-fetch on the declared cadence — `refresh_minutes` is a
     *  customization option, so the owner sets how often api.met.no is hit. */
    private fun schedule() {
        tick?.let(main::removeCallbacks)
        val r = object : Runnable {
            override fun run() {
                refresh()
                main.postDelayed(this, refreshMs())
            }
        }
        tick = r
        main.postDelayed(r, refreshMs())
    }

    private fun badge(): BadgeDeclaration.Badge? =
        BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

    private fun refreshMs(): Long {
        val m = badge()?.let { BadgeCustomization.text(this, it, "refresh_minutes") }
            ?.toLongOrNull() ?: DEFAULT_REFRESH_MIN
        return m.coerceIn(15L, 360L) * 60_000L
    }

    private fun refresh() {
        scope.launch {
            val today = runCatching { fetchToday() }.getOrNull()
            main.post {
                runCatching {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_ID, build(today))
                }
            }
        }
    }

    data class WeatherToday(
        val place: String,
        val lat: Double,
        val lon: Double,
        val forecast: WeatherModel.Forecast,
    )

    /** The badge's declaration gates on the location grant, so by the time
     *  this runs the permission is held — the lint suppress states that. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        // Passive first: the position someone else already paid for.
        for (p in listOf(LocationManager.PASSIVE_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun fetchToday(): WeatherToday? {
        val loc = lastKnownLocation() ?: return null
        val conn = (URL(WeatherModel.apiUrl(loc.latitude, loc.longitude)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", WeatherModel.USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val f = WeatherModel.parse(body, Instant.now(), ZoneId.systemDefault()) ?: return null
        return WeatherToday(placeName(loc), loc.latitude, loc.longitude, f)
    }

    /** City-ish name via the platform geocoder; honest coordinates when the
     *  device has no geocoder backend. */
    private fun placeName(loc: Location): String = runCatching {
        @Suppress("DEPRECATION")
        Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull() ?: String.format(Locale.US, "%.2f, %.2f", loc.latitude, loc.longitude)

    private fun build(today: WeatherToday?): android.app.Notification {
        val b = badge()
        val showHourly = b?.let { BadgeCustomization.bool(this, it, "show_hourly") } ?: true

        val title = if (today == null) getString(R.string.badge_weather_title)
        else getString(R.string.badge_weather_title_fmt,
            WeatherModel.headline(today.forecast), today.place)
        val text = if (today == null) getString(R.string.badge_weather_no_data)
        else getString(R.string.badge_weather_range_fmt,
            String.format(Locale.US, "%.0f", today.forecast.maxC),
            String.format(Locale.US, "%.0f", today.forecast.minC))

        val pinned = BadgeServices.pinned(this, BADGE_ID)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Cloud SA - Weather")
            .setOnlyAlertOnce(true)
            .setOngoing(pinned)
            .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(this@WeatherBadgeService, NOTIF_ID)) }
            // Weather is not sensitive — unlike the Health badge this one may
            // sit on the lockscreen, which is exactly where a forecast helps.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (today != null && showHourly && today.forecast.hours.isNotEmpty()) {
                    // Every 3rd hour: 8 readings span the day and still fit.
                    val rows = today.forecast.hours.filter { it.first % 3 == 0 }
                        .joinToString("   ") { (h, t) ->
                            String.format(Locale.US, "%02dh %.0f°", h, t)
                        }
                    setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$rows"))
                }
            }
            // Tap = the SOURCE's page for this position, the way Markets opens its ticker page.
            .setContentIntent(BadgeClick.pi(this, BadgeClick.weatherUrl(today?.lat, today?.lon)))
            .let { NotifyGroups.attach(this, it, BADGE_ID) } // #777 group G1
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Weather", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Today's forecast where the device is."
                setShowBadge(false)
            },
        )
    }

    companion object {
        // 7714 is Markets'. Sharing it meant each badge's post REPLACED the
        // other one in the shade (#535).
        const val NOTIF_ID = 7715
        private const val CHANNEL_ID = "weather_today"
        private const val DEFAULT_REFRESH_MIN = 60L

        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "weather_today"
    }
}
