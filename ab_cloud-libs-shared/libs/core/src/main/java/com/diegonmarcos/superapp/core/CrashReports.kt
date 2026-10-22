package com.diegonmarcos.superapp.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1122 CRASH-SAFE BY DEFAULT, for every fleet app. [CoreInitProvider] installs [installHandler] before
 * Application.onCreate, so an app gets all of this with no code of its own:
 *
 *  1. PERSIST FIRST. The report is written to `files/crashes/crash-<ms>-<n>.txt` before anything that can
 *     block or fail (network, another handler).
 *  2. LOCAL SINK. Then it is POSTed to the phone's own C3 WebServer on loopback (`emulated.app`, port from
 *     ac_cloud-c3-webserver/build.json::runtime.port baked as BuildConfig.CRASH_SINK_PORT, path
 *     [SINK_PATH]): a plain socket to 127.0.0.1, so no DNS and no cleartext policy, and it works with the
 *     network down. A report the sink could not take stays unmarked and is retried at the next start
 *     ([flushLocal]).
 *  3. REMOTE. Then the handler this one wrapped runs: [Telemetry]'s mesh-first, c3-pvt-second, queued upload.
 *  4. SCREEN. An app that declares an activity answering [ACTION_SHOW] gets it launched with the report
 *     (Drive's "hit a startup error" screen with Copy / Share / Retry in safe mode).
 *
 * The file handling is plain java.io and unit-tested; only [installHandler] touches Android.
 */
object CrashReports {

    const val DIR = "crashes"
    /** The local C3 WebServer route (POST a report, GET the list); declared in ac_cloud-c3-webserver. */
    const val SINK_PATH = "/__app__/api/crashes"
    /** An app's crash screen answers this action (explicit package, never exported to other apps' use). */
    const val ACTION_SHOW = "com.diegonmarcos.cloud.action.SHOW_CRASH_REPORT"
    /** Extra on the launcher intent that "Retry in safe mode" sends; apps may skip optional startup work on it. */
    const val EXTRA_SAFE_MODE = "com.diegonmarcos.cloud.extra.SAFE_MODE"
    private const val SUFFIX = ".txt"
    private const val SUNK = ".sunk"
    private const val SEEN = ".seen"
    private const val KEEP = 20

    private val seq = AtomicInteger(0)
    @Volatile private var inHandler = false

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    // ── pure ────────────────────────────────────────────────────────────────

    fun trace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** The text a person reads (and Copy / Share send). */
    fun text(phase: String, trace: String, meta: Map<String, String>, ms: Long): String =
        "App hit a startup error\nphase: $phase\ntime: ${stamp(ms)}\n" + meta.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" + trace

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** What the local sink stores: {app, version, device, sdk, time_ms, time, phase, trace}. */
    fun json(app: String, phase: String, trace: String, meta: Map<String, String>, ms: Long): String {
        val fields = linkedMapOf("app" to app, "phase" to phase, "time" to stamp(ms), "trace" to trace) + meta.filterKeys { it !in setOf("app", "phase", "time", "trace", "time_ms") }
        return "{" + fields.entries.joinToString(",") { "${jsonString(it.key)}:${jsonString(it.value)}" } + ",\"time_ms\":$ms}"
    }

    /** Persist-first: writes the report; never throws. */
    fun persist(dir: File, phase: String, trace: String, meta: Map<String, String>, ms: Long = System.currentTimeMillis()): File? = try {
        dir.mkdirs()
        val f = File(dir, "crash-$ms-${seq.incrementAndGet()}$SUFFIX")
        f.writeText(text(phase, trace, meta, ms))
        reports(dir).drop(KEEP).forEach { runCatching { it.delete(); File(it.path + SUNK).delete() } }
        f
    } catch (_: Throwable) { null }

    private fun reports(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && (f.name.endsWith(SUFFIX) || f.name.endsWith(SUFFIX + SEEN)) }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun unseen(dir: File): List<File> = reports(dir).filter { it.name.endsWith(SUFFIX) }
    fun latest(dir: File): File? = reports(dir).firstOrNull()
    fun markAllSeen(dir: File) { unseen(dir).forEach { f -> runCatching { f.renameTo(File(f.path + SEEN)); File(f.path + SUNK).renameTo(File(f.path + SEEN + SUNK)) } } }

    /** Reports the local sink has not taken yet. */
    fun unsunk(dir: File): List<File> = reports(dir).filter { !File(it.path + SUNK).exists() && !File(it.path.removeSuffix(SEEN) + SUNK).exists() }
    fun markSunk(f: File) { runCatching { File(f.path.removeSuffix(SEEN) + SUNK).writeText("") } }

    /**
     * POST [json] to 127.0.0.1:[port][path] over a plain socket (no DNS, no cleartext policy). True on a 200.
     * The x-c3-write header and JSON content type are what the server's POST guard demands.
     */
    fun postLocal(port: Int, path: String, json: String, timeoutMs: Int = 700): Boolean {
        if (port <= 0) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                s.soTimeout = timeoutMs
                val body = json.toByteArray(Charsets.UTF_8)
                val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nx-c3-write: 1\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII) + body); flush() }
                val status = s.getInputStream().bufferedReader().readLine().orEmpty()
                status.split(' ').getOrNull(1) == "200"
            }
        } catch (_: Throwable) { false }
    }

    // ── Android ─────────────────────────────────────────────────────────────

    private fun meta(ctx: Context): Map<String, String> {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        return linkedMapOf(
            "version" to version,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "uid" to android.os.Process.myUid().toString(),
            "thread" to Thread.currentThread().name,
        )
    }

    /** Persist, hand to the local sink, mark it sunk on success. Used by the handler and by apps' own guards. */
    fun report(ctx: Context, phase: String, t: Throwable): File? {
        val ms = System.currentTimeMillis()
        val m = meta(ctx)
        val tr = trace(t)
        val f = persist(dir(ctx), phase, tr, m, ms) ?: return null
        if (postLocal(BuildConfig.CRASH_SINK_PORT, SINK_PATH, json(ctx.packageName, phase, tr, m, ms))) markSunk(f)
        return f
    }

    /** The crash screen, if this app declares one. Never throws. */
    fun showScreen(ctx: Context, clearTask: Boolean) {
        runCatching {
            val i = Intent(ACTION_SHOW).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (clearTask) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (ctx.packageManager.resolveActivity(i, 0) != null) ctx.startActivity(i)
        }
    }

    /**
     * The default uncaught-exception handler. Installed by [CoreInitProvider] AFTER Telemetry's so it is the
     * outermost: persist, local sink, screen, then the wrapped handlers (remote upload, the app's own, the
     * system's) run exactly as before. A throw in here never replaces the original crash.
     */
    @JvmStatic
    fun installHandler(context: Context) {
        val ctx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            if (!inHandler) {
                inHandler = true
                runCatching { report(ctx, "uncaught exception on ${thread.name}", t) }
                runCatching { showScreen(ctx, clearTask = true) }
            }
            if (previous != null) previous.uncaughtException(thread, t)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Retries, off the main thread, every stored report the local sink has not taken. */
    @JvmStatic
    fun flushLocal(context: Context) {
        val ctx = context.applicationContext
        if (BuildConfig.CRASH_SINK_PORT <= 0) return
        Thread({
            runCatching {
                val dir = dir(ctx)
                val app = ctx.packageName
                for (f in unsunk(dir).reversed()) {
                    val ms = f.name.removePrefix("crash-").substringBefore('-').toLongOrNull() ?: continue
                    val body = json(app, "stored report", f.readText(), emptyMap(), ms)
                    if (postLocal(BuildConfig.CRASH_SINK_PORT, SINK_PATH, body)) markSunk(f) else break
                }
            }.onFailure { Log.w("CrashReports", "flushLocal: ${it.message}") }
        }, "crash-flush-local").start()
    }
}
