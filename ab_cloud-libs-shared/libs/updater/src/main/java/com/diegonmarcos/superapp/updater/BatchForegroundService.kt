package com.diegonmarcos.superapp.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.diegonmarcos.superapp.core.StoreNotifyGate
import java.util.concurrent.atomic.AtomicInteger

/**
 * #903 A running batch holds a `dataSync` foreground service.
 *
 * MEASURED (Android 14, One UI): "Download all → Install all" (23 apps,
 * ~1.1 GB) ran on a plain thread with no foreground service. The moment the
 * user left the Store, Android cached the process and Samsung's
 * FreecessController froze it (logcat FreecessHandler/handleLcdOnFreeze);
 * netpolicy blocked its network as well. The batch died mid-download
 * ("failed at downloading · DNS: cannot resolve …") and the loopback API
 * stopped answering. A foreground service is what Android AND Samsung exempt
 * from both the freeze and the background network restriction.
 *
 * [begin]/[end] are refcounted: every batch (downloadAll, updateAll, the auto
 * chain) and every single verb (a row's Install, `/api/store/<op>`) holds the
 * service for its duration; a verb inside a batch neither starts nor stops it.
 * The notification is the Store bar line — [text] is wired by StoreStages to
 * `StoreStages.progress()?.text`, exactly what `/api/store/progress` returns —
 * and is re-posted on progress no more than once a second ([THROTTLE_MS]).
 * [foregroundInfo] is the same notification for the CoroutineWorkers that
 * download or install, so an expedited/long pass is not killed either.
 *
 * Starting a foreground service from the background is refused on 12+
 * (ForegroundServiceStartNotAllowedException): that refusal is caught, the
 * batch goes on as before, and a worker's own setForeground covers that case.
 * POST_NOTIFICATIONS not granted only hides the notification; the service runs.
 */
object BatchForeground {
    private const val TAG = "BatchForeground"
    const val CHANNEL = "store_batch"
    const val ID = 0xB47C
    const val THROTTLE_MS = 1_000L

    /** The progress line. Null = no batch line to show; a generic one is drawn. */
    @Volatile var text: () -> String? = { null }
    /** The host's small icon (AppStoreHost.notificationIcon via StoreStages). */
    @Volatile var icon: () -> Int = { android.R.drawable.stat_sys_download }
    /** What a tap opens. Null = no content intent. */
    @Volatile var launch: (Context) -> Intent? = { null }

    private val held = AtomicInteger(0)
    @Volatile private var lastAt = 0L
    @Volatile private var appCtx: Context? = null

    private val observer: (UpdateProgress.State) -> Unit = {
        val ctx = appCtx
        val now = System.currentTimeMillis()
        if (ctx != null && held.get() > 0 && now - lastAt >= THROTTLE_MS) {
            lastAt = now
            runCatching { nm(ctx).notify(ID, notification(ctx)) }
        }
    }

    /** #894 Only Cloud Store (and the other fleet apps) hold this service: a foreground service
     *  cannot run without its notification, and the SuperApp posts no Store notification. */
    fun allowed(ctx: Context): Boolean = StoreNotifyGate.mayPost(ctx)

    fun begin(ctx: Context) {
        if (!allowed(ctx)) return
        if (held.incrementAndGet() != 1) return
        val app = ctx.applicationContext
        appCtx = app
        lastAt = 0L
        UpdateProgress.addObserver(observer)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, BatchForegroundService::class.java)) }
            .onFailure { Log.w(TAG, "foreground service not started: ${it.message}") }
    }

    fun end(ctx: Context) {
        if (held.get() <= 0 || held.decrementAndGet() != 0) return
        UpdateProgress.removeObserver(observer)
        val app = ctx.applicationContext
        runCatching { app.stopService(Intent(app, BatchForegroundService::class.java)) }
        runCatching { nm(app).cancel(ID) }
        appCtx = null
    }

    /** [begin] for [body]'s duration (when [on]); ends on every exit. */
    inline fun <T> hold(ctx: Context, on: Boolean = true, body: () -> T): T {
        if (on) begin(ctx)
        try { return body() } finally { if (on) end(ctx) }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun notification(ctx: Context): Notification {
        val nm = nm(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Store downloads", NotificationManager.IMPORTANCE_LOW))
        val line = runCatching { text() }.getOrNull() ?: "working…"
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(icon())
            .setContentTitle(ctx.applicationInfo.loadLabel(ctx.packageManager))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        runCatching { launch(ctx) }.getOrNull()?.let {
            b.setContentIntent(PendingIntent.getActivity(ctx, ID, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    /** For a CoroutineWorker's setForeground / getForegroundInfo. */
    fun foregroundInfo(ctx: Context): ForegroundInfo =
        if (!allowed(ctx)) throw IllegalStateException("Store notifications are Cloud Store's; this package posts none")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(ID, notification(ctx), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ID, notification(ctx))
}

/** The service [BatchForeground] holds. Declared by every host (type dataSync). */
class BatchForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BatchForeground.allowed(this)) { stopSelf(); return START_NOT_STICKY }
        runCatching {
            ServiceCompat.startForeground(this, BatchForeground.ID, BatchForeground.notification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure {
            Log.w("BatchForeground", "startForeground refused: ${it.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
