package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * #517 — the "Markets" badge: live USD/BRL, EUR/USD, WTI crude and gold.
 *
 * A foreground service for the same reason `KdeStatusService` and
 * `HealthBadgeService` are: an ongoing notify from a dead process is
 * droppable, and this badge is declared persistent. It is started by
 * [BadgeServices.ensureAll] from the declaration — there is no bespoke start
 * call for it anywhere, which is the whole point of #515.
 *
 * WHAT IT QUOTES IS NOT IN HERE. The four instruments, their captions, their
 * precision and the ticker page each one opens are
 * `build.json::ui.notification_center.producers[markets_prices].instruments`.
 * This class walks whatever that list says, so a fifth instrument — or Brent
 * (`BZ=F`) instead of WTI — is a data edit and no Kotlin.
 *
 * TAP GOES NOWHERE OUT OF THE APP. #156 removed six ad-hoc
 * `startActivity(ACTION_VIEW)` sites that leaked out of cloud-sa; this is not
 * a seventh. Every button carries the `shortcut_action` extra into
 * [MainActivity], exactly as `KdeStatusNotifier` does, and ShellActivity's
 * existing router hands an http(s) target to the app's own embedded
 * [com.diegonmarcos.superapp.launcher.WebPageFragment]. No new navigation
 * code, and no way for this badge to open a browser app.
 *
 * POLLING IS DECLARED, NOT HARDCODED. `refresh_minutes` is a customization
 * option (15/30/60/120, default 30) with a 15-minute floor, and the cadence is
 * re-read every tick so a change applies without a restart. In system
 * power-save mode the interval is quadrupled and the network fetch is skipped
 * — but the badge is still REDRAWN, so the age suffixes keep advancing and a
 * price frozen by power saving reads as old instead of reading as live.
 */
class MarketsBadgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Last answer per symbol, kept only so a power-save tick has something to
     *  re-date. Never rendered without its age: see [MarketsQuotes.row]. */
    private val quotes = mutableMapOf<String, MarketsQuotes.Quote>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Foreground within the first few seconds or the platform kills us, so
        // post the all-dashes face immediately and fill it in when the first
        // fetch returns. Dashes are the honest thing to show before any quote
        // exists.
        startForeground(NOTIF_ID, build())
        schedule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        tick?.let(main::removeCallbacks)
        scope.cancel()
        super.onDestroy()
    }

    /** refreshMs() is called again on every tick rather than captured once, so
     *  a changed option and a change in power-save state both take effect on
     *  the next cycle instead of on the next reboot. */
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

    private fun instruments(): List<BadgeDeclaration.Instrument> =
        badge()?.instruments ?: emptyList()

    private fun powerSaving(): Boolean = runCatching {
        (getSystemService(POWER_SERVICE) as PowerManager).isPowerSaveMode
    }.getOrDefault(false)

    private fun refreshMs(): Long {
        val declared = badge()?.let { BadgeCustomization.text(this, it, "refresh_minutes") }
            ?.toLongOrNull() ?: DEFAULT_REFRESH_MIN
        val minutes = declared.coerceIn(MIN_REFRESH_MIN, MAX_REFRESH_MIN)
        return minutes * 60_000L * if (powerSaving()) POWER_SAVE_FACTOR else 1L
    }

    /** A quote older than a few cycles is stale. Derived from the declared
     *  cadence so the two cannot drift apart.
     *  ponytail: a flat multiple of the interval, not a market calendar — a
     *  weekend close correctly reads "2d old"; per-instrument trading hours
     *  only matter if the owner wants "closed" spelled out instead of an age. */
    private fun staleAfterMs(): Long = refreshMs() * STALE_CYCLES

    private fun refresh() {
        val list = instruments()
        if (list.isEmpty()) {
            renotify()
            return
        }
        // Power saving: redraw (so the ages advance) but do not hit the network.
        if (powerSaving()) {
            renotify()
            return
        }
        scope.launch {
            val fetched = list.map { MarketsQuotes.fetch(it) }
            main.post {
                // Replaced wholesale, never merged: a symbol that failed this
                // cycle must lose its old price rather than keep it.
                quotes.clear()
                fetched.forEach { quotes[it.symbol] = it }
                renotify()
            }
        }
    }

    private fun renotify() = runCatching {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build())
    }

    private fun build(): android.app.Notification {
        val list = instruments()
        val now = System.currentTimeMillis()
        val stale = staleAfterMs()
        val b = badge()
        val showActions = b?.let { BadgeCustomization.bool(this, it, "actions") } ?: true

        val text = if (list.isEmpty()) getString(R.string.badge_markets_no_instruments)
        else MarketsQuotes.summary(list, quotes, now, stale)

        // The WHY, where the owner can read it. Reasons are only present for
        // rows that failed, so a healthy badge expands to just its rows.
        val reasons = list.mapNotNull { quotes[it.symbol]?.reason?.takeIf(String::isNotBlank) }
            .distinct()
        val big = (list.map { MarketsQuotes.row(it, quotes[it.symbol], now, stale) } +
            (if (powerSaving()) listOf(getString(R.string.badge_markets_power_saving)) else emptyList()) +
            reasons)
            .joinToString("\n")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(getString(R.string.badge_markets_title))
            .setContentText(text)
            .setSubText(getString(R.string.badge_markets_source))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setGroup("nc_markets")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big.ifBlank { text }))

        // Body tap opens the first declared instrument's ticker page; each
        // button opens its own. Four instruments cannot share one content
        // intent, and Yahoo has no multi-symbol page that answers 200 —
        // /quotes/<csv> is a 404 — so the buttons are the per-instrument route.
        list.firstOrNull()?.let { builder.setContentIntent(tickerPi(it.url)) }
        if (showActions) {
            // Notification actions cap at five and the compact view shows
            // three; take(4) keeps every declared instrument reachable while
            // leaving the cap alone.
            for (i in list.take(4)) builder.addAction(R.drawable.ic_p_markets_prices, i.label, tickerPi(i.url))
        }
        return builder.build()
    }

    /**
     * The in-app route, and the only one this badge has. `shortcut_action` is
     * the extra ShellActivity's own router consumes on both a cold start
     * (onCreate) and a warm one (onNewIntent); an http(s) target reaches
     * `launchUri`, which renders it in the embedded browser.
     */
    private fun tickerPi(url: String): PendingIntent = PendingIntent.getActivity(
        this, url.hashCode(),
        Intent(this, MainActivity::class.java)
            .putExtra("shortcut_action", url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Market prices", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live FX, crude and gold prices from Yahoo Finance."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val NOTIF_ID = 7714
        private const val CHANNEL_ID = "markets_prices"
        private const val DEFAULT_REFRESH_MIN = 30L

        /** Floor. The declaration offers 15 as its tightest option; this is the
         *  guard that keeps a hand-edited value from becoming a tight loop on
         *  somebody else's phone. */
        private const val MIN_REFRESH_MIN = 15L
        private const val MAX_REFRESH_MIN = 240L
        private const val POWER_SAVE_FACTOR = 4L
        private const val STALE_CYCLES = 3L

        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "markets_prices"
    }
}
