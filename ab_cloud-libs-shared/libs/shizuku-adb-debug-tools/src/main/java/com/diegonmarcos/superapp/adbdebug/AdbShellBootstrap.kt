package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * Bootstrap state for the self-contained app_process shell server
 * ([AdbShellServer]).
 *
 * The server runs in the SHELL domain only because it's launched by
 * `adb` (the one unavoidable privilege source on stock+non-rooted). We
 * own everything else: the server class is in our APK, it binds loopback,
 * and it authenticates with a per-install random token both sides share
 * (the app persists it here; the launch command embeds it).
 *
 * Port + class + nice-name are DATA-DRIVEN from
 * build.json::shizuku_diagnostics.local_server (baked into BuildConfig).
 */
object AdbShellBootstrap {

    fun port(): Int = BuildConfig.ADB_SHELL_SERVER_PORT
    fun serverClass(): String = BuildConfig.ADB_SHELL_SERVER_CLASS
    private fun niceName(): String = BuildConfig.ADB_SHELL_SERVER_NICE

    /** Stable per-install token shared with the server via its launch
     *  args. Generated + persisted on first read. */
    fun token(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(K_TOKEN, null)?.let { if (it.isNotBlank()) return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    /**
     * The exact one-liner to run from any machine with `adb` (Wireless
     * Debugging counts) ONCE per boot. It puts our APK on the classpath
     * and launches [AdbShellServer] via app_process, which therefore runs
     * in the SHELL domain. `nohup … &` + `</dev/null` detaches it so it
     * survives the adb session.
     *
     * The token is embedded so only this app can talk to the server.
     */
    fun serverCommand(ctx: Context): String = "adb shell \"${shellCommand(ctx)}\""

    /** The same launch, as the shell line itself — what [AdbPairingService]
     *  runs through the embedded adb stream once connected (our `start.sh`). */
    fun shellCommand(ctx: Context): String {
        val pkg = ctx.packageName
        val tok = token(ctx)
        // `export …;` not `CLASSPATH=… nohup …`: every channel runs this through a
        // plain `sh -c`, and a leading assignment breaks the moment anything
        // (`timeout`, a wrapper) is put in front of it (rc 127).
        return "export CLASSPATH=\$(pm path $pkg | cut -d: -f2); " +
            "nohup app_process /system/bin --nice-name=${niceName()} " +
            "${serverClass()} $tok ${port()} </dev/null >/dev/null 2>&1 &"
    }

    /**
     * Self-bootstrap of the PRIMARY through the fallback. Only a shell-domain
     * process can launch our server, and today that was adb alone — so when
     * the server is down but ANY other channel of the ladder is up (Shizuku,
     * or the embedded adb once paired), run the same launch line through it.
     * One attempt, then again only after the server has stayed down ≥
     * [RETRY_MS]; the stamp is elapsedRealtime, which restarts at boot, so a
     * stamp from the future is a previous boot and the attempt is due again.
     * Logs one line per attempt; the result is kept for [bootstrapState].
     */
    fun ensureServer(ctx: Context, ladder: List<ShellChannel>): Boolean {
        val listening = LocalShellChannel.isReady(ctx)
        if (listening && LocalShellChannel.probe(ctx)) return true
        val via = ladder.firstOrNull { it !== LocalShellChannel && it.isReady(ctx) } ?: return false
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val last = sp.getLong(K_BOOT_AT, -1L)
        if (last in 0..now && now - last < RETRY_MS) return false
        sp.edit().putLong(K_BOOT_AT, now).putString(K_BOOT_RESULT, "attempted via ${via.name()}: launching").apply()
        // A listening server that does not answer is the previous APK's process (shell uid, it
        // outlives the app): kill it first, by its nice-name, through the channel that still works.
        val launch = (if (listening) "pkill -f 'superapp-ad[b]'; sleep 1; " else "") + shellCommand(ctx)
        val out = via.exec(ctx, launch)?.trim().orEmpty()
        var up = false
        repeat(6) { if (!up) { Thread.sleep(500); up = LocalShellChannel.isReady(ctx) } }
        val result = "attempted via ${via.name()}: " +
            if (up) "server up" else "server did not come up" + (if (out.isBlank()) "" else " (${out.take(80)})")
        sp.edit().putString(K_BOOT_RESULT, result).apply()
        Log.i(TAG, "self-bootstrap $result")
        return up
    }

    /** One short string for /api/adb/status + the Permissions row: was the
     *  self-bootstrap attempted this boot, through which channel, and how
     *  did it end. No network — safe on the main thread. */
    fun bootstrapState(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = sp.getLong(K_BOOT_AT, -1L)
        if (last < 0 || last > SystemClock.elapsedRealtime()) return "not attempted this boot (no other channel was ready)"
        return sp.getString(K_BOOT_RESULT, null) ?: "not attempted this boot (no other channel was ready)"
    }

    private const val TAG     = "AdbShellBootstrap"
    private const val PREFS   = "adb_shell"
    private const val K_TOKEN = "token"
    private const val K_BOOT_AT     = "bootstrap_at"      // elapsedRealtime ms of the last attempt
    private const val K_BOOT_RESULT = "bootstrap_result"
    private const val RETRY_MS = 60_000L
}
