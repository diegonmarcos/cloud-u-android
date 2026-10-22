package com.diegonmarcos.superapp.appstore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver

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
            // startService, not startForegroundService: the Store holds its own foreground service for the
            // length of an install, which allows it; the app's service goes foreground itself.
            c.startService(i) != null
        } catch (_: Throwable) { false }
    }

    /** Seam: opens the app's main activity; false when it has none or Android refuses. */
    @Volatile
    var launcher: (Context, String) -> Boolean = { c, pkg ->
        try {
            val i = c.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i == null) false else { c.startActivity(i); true }
        } catch (_: Throwable) { false }
    }

    /** The explicit start; call after the install has landed. Never blocks, never throws. */
    fun run(ctx: Context, pkg: String) {
        if (!applies(pkg)) return
        val app = ctx.applicationContext
        record(app, pkg, UNPACKING)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) =
                record(app, pkg, text(resultCode, data?.getString("error")))
        }
        val intent = Intent(ACTION).setComponent(ComponentName(pkg, SERVICE)).putExtra(EXTRA_RECEIVER, receiver)
        if (starter(app, intent)) return
        // The service is unavailable (an older build, or Android refused a background start): the
        // app's own first start unpacks, so open it. The result stays "opened" until it is seen.
        record(app, pkg, if (launcher(app, pkg)) "opened to unpack" else "unpack failed: could not start $pkg")
    }

    /** What the last unpack of [pkg] said, null when none ran from here. */
    fun resultOf(ctx: Context, pkg: String): String? = runCatching {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pkg, null)
    }.getOrNull()

    private fun record(ctx: Context, pkg: String, text: String) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(pkg, text).commit() }
    }
}
