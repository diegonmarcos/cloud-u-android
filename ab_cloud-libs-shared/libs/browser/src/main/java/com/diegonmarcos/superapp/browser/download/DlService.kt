package com.diegonmarcos.superapp.browser.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The ONE download foreground service (type dataSync, Android 14+ rules): it keeps the process
 * alive while [DlQueue] has active HTTP downloads or torrents, shows an ongoing notification with
 * progress, speed, ETA and Pause / Resume / Cancel, and holds a partial WakeLock and a WifiLock
 * only while a transfer is actually running. It owns no transfer logic: that is [DlQueue].
 *
 * Declared by the host app's manifest (cloud-browser); a host that does not declare it keeps
 * libs:browser's old DownloadManager path ([available] is false there).
 */
class DlService : Service() {
    private lateinit var q: DlQueue
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastPost = 0L
    private var pending = false
    private val listener: () -> Unit = { main.post { refresh() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        q = DlQueue.get(this)
        running = true
        channel(this)
        q.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground first: a foreground-service start must reach it within seconds.
        val n = build()
        runCatching {
            ServiceCompat.startForeground(this, NOTE_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { q.pause(it) } ?: q.pauseAll()
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { q.resume(it) } ?: q.resumeAll()
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { q.cancel(it) } ?: q.all().filter { it.state.active }.forEach { q.cancel(it.id) }
        }
        q.pump()
        refresh()
        return START_STICKY
    }

    /** Android 15+: the dataSync time budget ran out. Park everything and let the resume job pick it up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        q.suspendAll()
        DlResumeJob.schedule(this, q.policy, 60_000)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        q.removeListener(listener)
        locks(false)
        if (q.hasWork()) DlResumeJob.schedule(this, q.policy, 30_000)
        super.onDestroy()
    }

    private fun refresh() {
        val transferring = q.running().isNotEmpty()
        locks(transferring)
        if (!q.hasWork()) {
            // Nothing active (finished, failed or paused rows only): no reason to stay in the foreground.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        val now = System.currentTimeMillis()
        if (now - lastPost < 1_000) {
            if (!pending) { pending = true; main.postDelayed({ pending = false; refresh() }, 1_000) }
            return
        }
        lastPost = now
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTE_ID, build()) }
    }

    @SuppressLint("WakelockTimeout")
    private fun locks(on: Boolean) {
        if (on) {
            if (wake == null) wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloudbrowser:downloads").apply { setReferenceCounted(false); acquire() }
            if (wifi == null) wifi = runCatching {
                @Suppress("DEPRECATION")
                applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cloudbrowser:downloads").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        } else {
            wake?.let { if (it.isHeld) it.release() }; wake = null
            wifi?.let { if (it.isHeld) it.release() }; wifi = null
        }
    }

    private fun build(): Notification {
        val active = q.all().filter { it.state.active }
        val running = active.filter { it.state == DlState.RUNNING || it.state == DlState.RETRYING }
        val total = running.sumOf { it.total.coerceAtLeast(0) }
        val done = running.sumOf { it.downloaded }
        val bps = running.sumOf { q.live(it.id).bytesPerSec }
        val eta = if (bps > 0 && total > 0) (total - done) / bps else -1
        val one = active.singleOrNull()
        val title = when {
            one != null -> one.fileName
            active.isEmpty() -> "Downloads"
            else -> "${active.size} downloads"
        }
        val text = when {
            running.isEmpty() && active.any { it.state == DlState.WAITING_NETWORK } -> "Waiting for an allowed network"
            running.isEmpty() -> "Queued"
            else -> "${DlFormat.bytes(done)} of ${if (total > 0) DlFormat.bytes(total) else "?"} · ${DlFormat.speed(bps)} · ${DlFormat.eta(eta)} left"
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(this))
        if (total > 0) b.setProgress(1000, ((done * 1000) / total).toInt(), false) else if (running.isNotEmpty()) b.setProgress(0, 0, true)
        val id = one?.id
        if (running.isNotEmpty()) b.addAction(0, "Pause", action(ACTION_PAUSE, id))
        else b.addAction(0, "Resume", action(ACTION_RESUME, id))
        b.addAction(0, "Cancel", action(ACTION_CANCEL, id))
        return b.build()
    }

    private fun action(a: String, id: String?): PendingIntent {
        val i = Intent(this, DlService::class.java).setAction(a).apply { id?.let { putExtra(EXTRA_ID, it) } }
        return PendingIntent.getService(this, (a + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTE_ID = 0xD10AD
        const val ACTION_PAUSE = "com.diegonmarcos.superapp.browser.download.PAUSE"
        const val ACTION_RESUME = "com.diegonmarcos.superapp.browser.download.RESUME"
        const val ACTION_CANCEL = "com.diegonmarcos.superapp.browser.download.CANCEL"
        const val EXTRA_ID = "id"
        @Volatile var running = false; private set
        /** The launcher intent carries this so the browser opens its Downloads page. */
        const val EXTRA_OPEN_DOWNLOADS = "com.diegonmarcos.superapp.browser.OPEN_DOWNLOADS"

        /** Does the host app declare this service? (cloud-browser does; other libs:browser hosts may not.) */
        fun available(ctx: Context): Boolean = runCatching {
            ctx.packageManager.getServiceInfo(ComponentName(ctx, DlService::class.java), PackageManager.MATCH_DISABLED_COMPONENTS); true
        }.getOrDefault(false)

        /** Start the service when there is work; a background start Android refuses falls back to [DlResumeJob]. */
        fun sync(ctx: Context, q: DlQueue) {
            if (running || !q.hasWork() || !available(ctx)) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DlService::class.java)) }
                .onFailure { DlResumeJob.schedule(ctx, q.policy, 0) }
        }

        fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }

        fun openApp(ctx: Context): PendingIntent? {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.putExtra(EXTRA_OPEN_DOWNLOADS, true) ?: return null
            return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** "Download complete" / "Download failed": one notification per file; tapping a finished one opens it. */
        fun notifyDone(ctx: Context, j: DlJob) {
            channel(ctx)
            val ok = j.state == DlState.COMPLETED
            val open = if (ok) DlPublisher.openIntent(ctx, j)?.let {
                PendingIntent.getActivity(ctx, j.id.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else openApp(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "Download complete" else "Download failed")
                .setContentText(if (ok) j.fileName else "${j.fileName}: ${j.error.orEmpty()}")
                .setAutoCancel(true).setContentIntent(open ?: openApp(ctx))
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(j.id.hashCode(), n) }
        }
    }
}
