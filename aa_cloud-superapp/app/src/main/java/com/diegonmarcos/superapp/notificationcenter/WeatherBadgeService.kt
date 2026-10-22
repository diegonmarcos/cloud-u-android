package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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
 * pure [WeatherModel]. The position comes from [WeatherLocation]: the
 * persisted fix when fresh, then the system's cached fixes, then one network
 * (Wi-Fi/cell) request, GPS only as the last resort; every fix is persisted
 * and never cleared. The place name comes from
 * Android's own [Geocoder]; when it has no backend the coordinates are shown
 * instead, because a wrong name is worse than an honest number.
 *
 * NOTHING HERE IS ESTIMATED. A failed fetch keeps the previous forecast (also
 * across a restart: it is persisted), never inventing a number. No position at
 * all → the card names the missing permission or provider and offers the fix.
 */
class WeatherBadgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private val store by lazy { WeatherFixStore(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Foreground within the first few seconds or the platform kills us —
        // post the "reading…" face immediately, fill in when the fetch lands.
        startForeground(NOTIF_ID, build(initialState()))
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
            val state = runCatching { compute() }.getOrElse { initialState() }
            main.post {
                runCatching {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_ID, build(state))
                }
            }
        }
    }

    /** What to show before the first refresh lands (and after a crash in it): the persisted fix and the last good
     *  forecast, so a restart or a reboot never shows an empty card while a position is known. */
    private fun initialState(): WeatherFace.State {
        val fix = store.load()
        if (fix == null) {
            val env = WeatherLocation.env(this)
            val r = if (!env.coarse && !env.fine) WeatherLocation.Reason.PERMISSION
            else if (!env.anyProviderOn) WeatherLocation.Reason.LOCATION_OFF else WeatherLocation.Reason.SEARCHING
            return WeatherFace.State.NoLocation(r)
        }
        return cachedState(fix) ?: WeatherFace.State.Loading(coords(fix.lat, fix.lon), fix)
    }

    private fun cachedState(fix: WeatherLocation.Fix?): WeatherFace.State.Forecast? {
        val c = store.loadForecast() ?: return null
        val f = WeatherModel.parse(c.body, Instant.now(), ZoneId.systemDefault()) ?: return null
        return WeatherFace.State.Forecast(WeatherToday(c.place, c.lat, c.lon, f), fix)
    }

    private fun compute(): WeatherFace.State {
        val res = WeatherLocation.resolve(System.currentTimeMillis(), WeatherLocation.env(this),
            WeatherLocation.AndroidProbe(this), store)
        val fix = (res as? WeatherLocation.Result.Found)?.fix
            ?: return WeatherFace.State.NoLocation((res as WeatherLocation.Result.None).reason)
        val today = runCatching { fetchToday(fix) }.getOrNull()
        // A failed fetch keeps the last good forecast; only a position with no forecast at all is "loading".
        return if (today != null) WeatherFace.State.Forecast(today, fix)
        else cachedState(fix) ?: WeatherFace.State.Loading(coords(fix.lat, fix.lon), fix)
    }

    data class WeatherToday(
        val place: String,
        val lat: Double,
        val lon: Double,
        val forecast: WeatherModel.Forecast,
    )

    private fun fetchToday(loc: WeatherLocation.Fix): WeatherToday? {
        val conn = (URL(WeatherModel.apiUrl(loc.lat, loc.lon)).openConnection() as HttpURLConnection).apply {
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
        val place = placeName(loc)
        store.saveForecast(WeatherFixStore.Cached(place, loc.lat, loc.lon, body, System.currentTimeMillis()))
        return WeatherToday(place, loc.lat, loc.lon, f)
    }

    private fun coords(lat: Double, lon: Double) = String.format(Locale.US, "%.2f, %.2f", lat, lon)

    /** City-ish name via the platform geocoder; honest coordinates when the
     *  device has no geocoder backend. */
    private fun placeName(loc: WeatherLocation.Fix): String = runCatching {
        @Suppress("DEPRECATION")
        Geocoder(this, Locale.getDefault()).getFromLocation(loc.lat, loc.lon, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull() ?: coords(loc.lat, loc.lon)

    private fun build(state: WeatherFace.State): android.app.Notification {
        val b = badge()
        val showHourly = b?.let { BadgeCustomization.bool(this, it, "show_hourly") } ?: true
        val today = (state as? WeatherFace.State.Forecast)?.today
        val face = WeatherFace.of(this, state)
        val title = face.title
        val text = face.text

        val pinned = BadgeServices.pinned(this, BADGE_ID)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Cloud SA - Weather")
            .apply { actionPi(face.action)?.let { addAction(0, face.actionLabel, it) } }
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
                    val pos = (state as WeatherFace.State.Forecast).fix
                        ?.let { "\n" + WeatherFace.position(this@WeatherBadgeService, it, System.currentTimeMillis()) }.orEmpty()
                    setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$rows$pos"))
                }
            }
            // Tap = the SOURCE's page for this position, the way Markets opens its ticker page.
            .setContentIntent(BadgeClick.pi(this, BadgeClick.weatherUrl(today?.lat, today?.lon)))
            .let { NotifyGroups.attach(this, it, BADGE_ID) } // #777 group G1
            .build()
    }

    /** The card's button: where the missing piece is fixed. A runtime permission cannot be asked from a
     *  notification, so the permission button opens this app's settings page. */
    private fun actionPi(a: WeatherFace.Action?): PendingIntent? {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return when (a) {
            null -> null
            WeatherFace.Action.APP_SETTINGS -> PendingIntent.getActivity(this, 1,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            WeatherFace.Action.LOCATION_SETTINGS -> PendingIntent.getActivity(this, 2,
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            WeatherFace.Action.RETRY -> PendingIntent.getService(this, 3,
                Intent(this, WeatherBadgeService::class.java), flags)
        }
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
