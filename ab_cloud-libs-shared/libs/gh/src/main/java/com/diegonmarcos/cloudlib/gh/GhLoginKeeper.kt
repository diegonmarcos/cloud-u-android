package com.diegonmarcos.cloudlib.gh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * #729 WHY A SIGN-IN NEEDS THE ENGINE TO STAY "IN USE". gh's device flow has two halves: it asks
 * GitHub for a code while the app that asked is on screen, then POLLS GitHub for the approval
 * while the user is away in the browser typing that code. This engine is bound only by that app,
 * so the moment the browser comes up the app falls to the background and this process with it.
 * Android 15 cuts the network of a process outside a valid lifecycle (NetworkPolicyManager:
 * allowed only below PROCESS_STATE_TOP_SLEEPING), and the cut shows up as a failed DNS lookup
 * in the gh-net tunnel: "Post https://github.com/login/oauth/access_token ... DNS: no address
 * for github.com". The first request had resolved fine; only the poll died.
 *
 * So for exactly the life of one `gh auth login` the engine runs this foreground service
 * (Android's documented answer for "continue a user-visible task"). It is started with a plain
 * startService while the asking app is still on screen, which leaves this process BOUND_TOP: that
 * is what lets startForeground pass the background-start check (ActiveServices allows a calling
 * uid at or above BOUND_TOP), and a plain start, unlike startForegroundService, cannot crash the
 * engine when gh ends before onStartCommand runs. Every outcome is logged under GhRunner.TAG.
 */
class GhLoginKeeper : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "GitHub sign-in", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Signing in to GitHub")
            .setContentText("gh is waiting for the one-time code to be approved")
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.onSuccess { Log.i(GhRunner.TAG, "gh-net: engine held in the foreground while gh signs in") }
            .onFailure {
                Log.w(GhRunner.TAG, "gh-net: Android refused to hold the engine in the foreground (${it.message}); " +
                    "gh's poll may lose the network once the browser is up")
                stopSelf()
            }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "gh-login"
        private const val ID = 729

        /** Hold the engine in the foreground; the answer releases it. Never throws: a refusal is a log line. */
        fun hold(ctx: Context): () -> Unit {
            val intent = Intent(ctx, GhLoginKeeper::class.java)
            runCatching { ctx.startService(intent) }
                .onFailure { Log.w(GhRunner.TAG, "gh-net: could not start the sign-in keeper (${it.message})") }
            return { runCatching { ctx.stopService(intent) } }
        }
    }
}
