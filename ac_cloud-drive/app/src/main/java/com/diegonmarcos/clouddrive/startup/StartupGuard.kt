package com.diegonmarcos.clouddrive.startup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * #1106 CRASH-SAFE STARTUP. The app crashed on open on a phone no emulator reproduces and no log
 * could be read from, so a startup failure is now data the phone itself shows: every startup phase
 * runs through [step], an uncaught exception anywhere is caught by [install]'s handler, and both
 * write a report to `files/crashes` and open [CrashReportActivity] (own process, plain Views, no
 * Compose / theme / prefs dependency) with the full stack trace, Copy, Share and "Retry in safe
 * mode". A report is also surfaced on the NEXT launch if it was never shown ([unseen]).
 *
 * The file handling is plain java.io (pure, unit-tested); only [install]/[launchReport] touch Android.
 */
object StartupGuard {

    const val DIR = "crashes"
    const val EXTRA_SAFE_MODE = "com.diegonmarcos.clouddrive.SAFE_MODE"
    private const val PREFIX = "crash-"
    private const val SUFFIX = ".txt"
    private const val SEEN = ".seen"
    private const val KEEP = 20
    private const val MAX_SHARE_CHARS = 150_000

    /** Safe mode: skip migration, network, telemetry and background scheduling; open the file list. */
    @Volatile var safeMode = false

    private val failures = AtomicInteger(0)
    private val seq = AtomicInteger(0)
    @Volatile private var inHandler = false

    /** How many [step]s failed in THIS process since launch. */
    fun failureCount(): Int = failures.get()

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    // ── pure: files ─────────────────────────────────────────────────────────

    /** The report text: a header naming the phase and the device, then the full stack trace. */
    fun format(phase: String, t: Throwable, env: String, nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
        val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
        return "Drive hit a startup error\nphase: $phase\ntime: $stamp\n$env\n\n$trace"
    }

    /** Writes a report; never throws (a crash reporter that crashes is the one failure nobody can see). */
    fun record(dir: File, phase: String, t: Throwable, env: String, nowMs: Long = System.currentTimeMillis()): File? = try {
        dir.mkdirs()
        val f = File(dir, "$PREFIX$nowMs-${seq.incrementAndGet()}$SUFFIX")
        f.writeText(format(phase, t, env, nowMs))
        prune(dir)
        f
    } catch (_: Throwable) { null }

    private fun reports(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && (f.name.endsWith(SUFFIX) || f.name.endsWith(SUFFIX + SEEN)) }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Reports not yet shown, newest first. */
    fun unseen(dir: File): List<File> = reports(dir).filter { it.name.endsWith(SUFFIX) }

    /** The newest report, shown or not; null when there is none. */
    fun latest(dir: File): File? = reports(dir).firstOrNull()

    /** Marks every report shown, so a launch does not redirect to the same stack twice. */
    fun markAllSeen(dir: File) {
        unseen(dir).forEach { f -> runCatching { f.renameTo(File(f.path + SEEN)) } }
    }

    private fun prune(dir: File) {
        reports(dir).drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** What the Share button sends: the newest report, capped so the intent stays inside the binder limit. */
    fun shareText(dir: File): String {
        val f = latest(dir) ?: return "No startup error has been recorded."
        val text = runCatching { f.readText() }.getOrDefault("(report unreadable)")
        return if (text.length <= MAX_SHARE_CHARS) text else text.take(MAX_SHARE_CHARS) + "\n…(truncated)"
    }

    // ── Android ─────────────────────────────────────────────────────────────

    private fun env(ctx: Context?): String {
        val version = runCatching { ctx!!.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        return "app: ${ctx?.packageName} $version safeMode=$safeMode\n" +
            "device: ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n" +
            "uid: ${Process.myUid()} pid: ${Process.myPid()} thread: ${Thread.currentThread().name}"
    }

    /**
     * Runs one startup phase. A throw is recorded, counted and swallowed so the next phase still
     * runs; returns whether the phase completed. In [safeMode] a phase marked [optional] is skipped.
     */
    fun step(ctx: Context, phase: String, optional: Boolean = false, block: () -> Unit): Boolean {
        if (optional && safeMode) return true
        return try {
            block(); true
        } catch (t: Throwable) {
            failures.incrementAndGet()
            record(dir(ctx), phase, t, env(ctx))
            false
        }
    }

    /** Opens the report screen. [clearTask] when the app behind it is unusable. Never throws. */
    fun launchReport(ctx: Context, clearTask: Boolean) {
        runCatching {
            val i = Intent(ctx, CrashReportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (clearTask) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(i)
        }
    }

    /**
     * The default UncaughtExceptionHandler: record, open the report screen (it lives in :crash, so
     * it outlives this process), then end the process. A crash inside the report process falls
     * through to the previous handler so a broken report screen cannot loop.
     */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val reportProcess = isReportProcess(app)
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            if (reportProcess || inHandler) { previous?.uncaughtException(thread, t); return@setDefaultUncaughtExceptionHandler }
            inHandler = true
            try {
                record(dir(app), "uncaught exception on ${thread.name}", t, env(app))
                launchReport(app, clearTask = true)
            } catch (_: Throwable) {
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun isReportProcess(ctx: Context): Boolean =
        (if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null)?.endsWith(":crash") == true

    /** True on the UI thread: a socket opened there throws NetworkOnMainThreadException (and on the phone resolves `*.app` on the public internet). */
    fun onMainThread(): Boolean = runCatching { Looper.getMainLooper().thread === Thread.currentThread() }.getOrDefault(false)
}
