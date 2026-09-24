package com.diegonmarcos.cloudwebserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/** Keeps the server process alive while the app is in the background. */
class WebServerService : Service() {
    @Volatile private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = WebServerRuntime(this)
        val channel = NotificationChannel("server", getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, channel.id)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text, runtime.serveRoot, runtime.url))
            .build())
        if (process?.isAlive != true) {
            // ponytail: first start unpacks ~160 MB; off the main thread, no progress UI.
            Thread { process = runtime.start(File(filesDir, "webserver.log")) }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        process?.destroy()
        super.onDestroy()
    }
}
