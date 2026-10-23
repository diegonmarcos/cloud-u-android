package com.diegonmarcos.superapp.appstore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import com.diegonmarcos.superapp.core.StoreNotifyGate

/**
 * #1121 After the Store installs the Termux terminal or Nix-on-Droid, those apps must unpack their
 * bundled files without anybody opening them. Each one does it itself on MY_PACKAGE_REPLACED
 * (UnpackReceiver, a short foreground service, no UI); this is the Store's explicit half, so the
 * unpack also runs when the system broadcast is held back:
 *  1. start the app's UnpackService (action [ACTION], guarded by the fleet's signature-level
 *     TERMINAL_SESSION permission, declared in the IPC mesh registry) with a [ResultReceiver];
 *  2. if the service cannot be started, launch the app's main activity, whose first start unpacks;
 *  3. keep the outcome ([resultOf]) so the row shows "unpacked" or the error.
 */
object UnpackAfterInstall {

    const val ACTION = "com.diegonmarcos.cloud.action.UNPACK"
    const val EXTRA_RECEIVER = "result_receiver"
    const val SERVICE = "com.termux.app.UnpackService"
    const val UNPACKING = "unpacking…"
    const val UNPACKED = "unpacked"

    /** The two terminals that carry bundled files. */
    val PACKAGES = setOf("cld.termux", "cld.termux.nix")

    private const val PREFS = "unpack_after_install"

    fun applies(pkg: String): Boolean = pkg in PACKAGES

    /** The sentence for a result code (0 = ok) and the app's error, if any. */
    fun text(code: Int, error: String?): String =
        if (code == 0) UNPACKED else "unpack failed: ${error?.takeIf { it.isNotBlank() } ?: "unknown error"}"

    /** Seam: starts the service; false when Android refuses it or the app has no such service. */
    @Volatile
    var starter: (Context, Intent) -> Boolean = { c, i ->
        try {
            // startForegroundService, not startService: a plain start is refused by the TARGET's own background
            // restriction ("Background start not allowed", startFg?=false) whatever state the Store is in, because
            // the freshly installed terminal is idle. A foreground start is checked against the Store's state instead
            // (it holds a foreground service for the length of an install), and the terminal's UnpackService calls
            // startForeground at once in onStartCommand, as its own MY_PACKAGE_REPLACED receiver relies on.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) != null else c.startService(i) != null
        } catch (_: Throwable) { false }
    }

    /** Seam: how the reply channel is made safe to send. A JVM test has no real binder, so it swaps this; the phone never does. */
    @Volatile
    var sender: (ResultReceiver) -> ResultReceiver = { receiverForSending(it) }

    /** Seam: opens the app's main activity; false when it has none or Android refuses. */
    @Volatile
    var launcher: (Context, String) -> Boolean = { c, pkg ->
        try {
            val i = c.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i == null) false else { c.startActivity(i); true }
        } catch (_: Throwable) { false }
    }

    /**
     * A plain [ResultReceiver] that forwards to [local]. Sending a subclass across processes makes the
     * other app unmarshal that class by name, which its classloader does not have (BadParcelableException,
     * #1121 on the phone). Written to a Parcel and read back, only the binder is left, so the receiving
     * app sees the framework class alone.
     */
    fun receiverForSending(local: ResultReceiver): ResultReceiver {
        val p = Parcel.obtain()
        try {
            local.writeToParcel(p, 0)
            p.setDataPosition(0)
            return ResultReceiver.CREATOR.createFromParcel(p)
        } finally {
            p.recycle()
        }
    }

    /** The explicit start; call after the install has landed. Never blocks, never throws. */
    fun run(ctx: Context, pkg: String) {
        if (!applies(pkg)) return
        // #894 the foreground start below is a posting primitive: only Cloud Store owns installs, so only it runs the hand-off.
        if (!StoreNotifyGate.mayPost(ctx)) return
        val app = ctx.applicationContext
        record(app, pkg, UNPACKING)
        val receiver = sender(object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) =
                record(app, pkg, text(resultCode, data?.getString("error")))
        })
        val intent = Intent(ACTION).setComponent(ComponentName(pkg, SERVICE)).putExtra(EXTRA_RECEIVER, receiver)
        attempt(app, pkg, intent, 0)
    }

    /**
     * Android refuses a background service start while the Store is not yet in the foreground state that allows it
     * (its install foreground service or the update worker's is still coming up: a Store process started cold for this
     * hand-off was refused 1.3 s in, 0.5 s before its own foreground service began, #1121 emulator probe). The refusal is
     * transient, so the start is retried for [startRetries] x [retryDelayMs] before the app is opened instead.
     */
    private fun attempt(app: Context, pkg: String, intent: Intent, n: Int) {
        if (starter(app, intent)) return
        if (n < startRetries) {
            Handler(Looper.getMainLooper()).postDelayed({ attempt(app, pkg, intent, n + 1) }, retryDelayMs)
            return
        }
        // The service is unavailable (an older build, or Android keeps refusing the background start): the
        // app's own first start unpacks, so open it. The result stays "opened" until it is seen.
        record(app, pkg, if (launcher(app, pkg)) "opened to unpack" else "unpack failed: could not start $pkg")
    }

    /** Seam: how many times a refused service start is retried before the app is opened (a JVM test sets 0). */
    @Volatile
    var startRetries: Int = 15

    /** Seam: the pause between those retries. */
    @Volatile
    var retryDelayMs: Long = 1_000L

    /** What the last unpack of [pkg] said, null when none ran from here. */
    fun resultOf(ctx: Context, pkg: String): String? = runCatching {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pkg, null)
    }.getOrNull()

    private fun record(ctx: Context, pkg: String, text: String) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(pkg, text).commit() }
    }
}
