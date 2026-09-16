package com.diegonmarcos.superapp.devtools

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Bridge between the worker-thread HTTP server and the foreground UI.
 *
 * MainActivity registers itself via [register] in onResume and
 * unregisters in onPause. The server thread posts work onto the main
 * Looper via [runOnMain], inside which it can call the registered
 * activity's exposed dispatch helpers.
 *
 * WHAT THIS BRIDGE DOES NOT PROMISE. This paragraph used to read "if no
 * activity is registered, calls become no-ops and the server's reply still
 * completes (the user can re-launch the app to complete the action)". That
 * was an accurate description of the code and a false description of the
 * product: a no-op whose reply still completes is a request reported as
 * done and never run. Five debug routes answered "ok" to a backgrounded
 * phone on the strength of that sentence (#367), and /api/system/update,
 * which reached past this bridge to make the same assumption, left a phone
 * four published releases behind (#280).
 *
 * [host] is a WeakReference the Activity clears in onPause, so it is null
 * exactly when the screen is off — which is when fleet-driven work
 * arrives. A null host is therefore a FAILURE TO BE REPORTED, not a
 * success to be assumed, and this bridge does not decide which. Callers
 * decide, and they are expected to observe rather than hope:
 * DevControlServer.dispatchToHost posts the work, WAITS for the main
 * thread to run it, and answers delivered | no_live_host | host_timeout |
 * host_threw as 200 / 503 / 503 / 500. Nothing here swallows a missing
 * activity quietly, and nothing here may be handed a reply to complete on
 * its behalf.
 */
object DevControlBridge {

    interface ActivityHost {
        fun onTileFromServer(target: String)
        fun onActionFromServer(actionType: String)
        fun firePresetHaptic(preset: String)
        fun stateSnapshot(): Map<String, String>
    }

    @Volatile private var hostRef: WeakReference<ActivityHost>? = null
    private val main = Handler(Looper.getMainLooper())

    fun register(host: ActivityHost) { hostRef = WeakReference(host) }
    fun unregister(host: ActivityHost) {
        if (hostRef?.get() === host) hostRef = null
    }
    fun host(): ActivityHost? = hostRef?.get()

    /**
     * Post [block] onto the main Looper. Returns Handler.post's own verdict:
     * false when the Looper is exiting and the block will therefore never
     * run.
     *
     * The Boolean was always returned — it was merely inferred, so the call
     * read like a Unit and every caller discarded it. It is spelled out here
     * because "posted" and "will run" are different claims, and this file's
     * whole history is of the second being reported when only the first
     * happened. Posting is not delivery: see DevControlServer.dispatchToHost,
     * which waits for the block instead of trusting the post.
     */
    fun runOnMain(block: () -> Unit): Boolean = main.post(block)

    /**
     * What [restartApp] actually did.
     *
     * The same `{ok, reason, message}` triple that /api/system/update
     * established in #280 and the nav routes adopted in #367, so this surface
     * answers in one vocabulary rather than two — a second vocabulary for the
     * same idea doubles what the next reader has to hold.
     *
     * There is deliberately no `delivered` reason. A restart that works never
     * returns: [restartApp] kills its own process. The only outcome this type
     * can carry back to a caller is a refusal, which is exactly the case the
     * old `?: return` discarded.
     */
    data class RestartOutcome(val ok: Boolean, val reason: String, val message: String)

    /**
     * Restart the whole process. Schedules a launch intent via AlarmManager
     * for ~150ms in the future, then kills the current process — the
     * alarm wakes up after the kill and Android cold-starts the app.
     *
     * RETURNS ONLY WHEN IT DID NOT RESTART. The precondition is a launcher
     * intent for this package; without one there is nothing to cold-start the
     * app after the kill, so killing would leave the device with no app rather
     * than with a restarted one. That case used to be `?: return` — a silent
     * Unit that told the caller nothing and left it free to invent an outcome.
     * It did: the debug route replied the literal "restarting…" to a request
     * that had restarted nothing (#367). The refusal is now the return value,
     * so the caller can report what happened instead of what was hoped for.
     */
    fun restartApp(ctx: Context): RestartOutcome {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return RestartOutcome(
                ok = false,
                reason = "no_launch_intent",
                message = "this package resolves no launcher intent, so nothing would " +
                    "cold-start it after the kill; the process was left running rather " +
                    "than killed with no way back",
            )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        val mgr = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 150, pi)
        if (hostRef?.get() is Activity) (hostRef!!.get() as Activity).finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
