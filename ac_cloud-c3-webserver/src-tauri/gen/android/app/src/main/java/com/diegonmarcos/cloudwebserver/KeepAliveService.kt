package com.diegonmarcos.cloudwebserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * The phone's local servers (the drive on 8002, diagnostics on 8003 and the private DNS on udp 5353) live in the
 * Rust library; this foreground service is what keeps that process alive and binds them without the UI. It is
 * started at boot and after an update ([BootReceiver]), by Cloud Browser when a private name's DNS does not
 * answer (explicit intent, guarded by the fleet's CONSTELLATION_DATA signature permission) and by the activity.
 * START_STICKY: the system restarts it if it is killed. The server start is idempotent (Rust side).
 */
class KeepAliveService : Service() {
    private external fun nativeStart(): Boolean

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local servers", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Cloud C3 WebServer")
            .setContentText("Serving emulated.app, cloud.app and their private DNS on this phone")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Off the main thread: loading the library and binding must not hold the 5 s foreground deadline.
        Thread {
            runCatching {
                System.loadLibrary("cloud_c3_webserver_lib")
                nativeStart()
            }
        }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "local-servers"
        const val NOTIFICATION_ID = 8002
    }
}
