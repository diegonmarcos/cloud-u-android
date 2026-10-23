package com.diegonmarcos.superapp.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam
import java.net.HttpURLConnection
import java.net.URL

/**
 * Constellation-wide telemetry DEFAULT — every app that links libs:core gets
 * this for free, with zero per-app wiring. POSTs to c3-infra-api's public
 * events ingest:
 *
 *   POST <mesh c3-infra-api.mesh.diegonmarcos.com, then the public c3-pvt path>/apk/{app}/logs   (see [TelemetryRoute])
 *   { kind: "log"|"debug"|"action"|"probe"|"crash", title?, message?, log?, meta? }
 *
 * 2 MB cap, unauthenticated (same "public path" reasoning as [LogUpload] —
 * the moments you most need telemetry are the ones where auth broke), and
 * the server fans a summary out to ntfy topic infra-{app}.
 *
 * WHY A DERIVED ENDPOINT, UNLIKE [LogUpload]: LogUpload's endpoint is a bare
 * parameter the caller must supply (superapp reads its own
 * BuildConfig.LOG_INGEST_URL, empty unless wired), so most apps silently ship
 * no telemetry at all. Telemetry instead derives a sensible default from the
 * app's own package name — "add libs:core" is enough on its own. An app opts
 * OUT by overriding TELEMETRY_INGEST_URL to "", not by doing nothing.
 */
object Telemetry {
    private const val TAG = "Telemetry"

    /** Newest logcat bytes attached to a crash report. Deliberately far below
     *  [LogUpload]'s 1.5 MB ceiling: this is read and uploaded on the dying
     *  thread, so the budget is "enough lead-up to explain the throw", not
     *  "everything the app ever logged". */
    private const val CRASH_LOGCAT_BYTES = 256_000

    /** Crash-path HTTP budget, tighter than ordinary telemetry's 5 s/8 s: the
     *  app is frozen in front of the user until the POST returns or gives up. */
    private const val CRASH_CONNECT_TIMEOUT_MS = 3_000
    private const val CRASH_READ_TIMEOUT_MS = 4_000

    /**
     * Derive the app id the events ingest and the ntfy topic key on:
     * last segment(s) of the package name after "com.diegonmarcos.", with
     * any remaining dots turned into '-'.
     *
     *   com.diegonmarcos.comms.mail -> "comms-mail"
     *
     * A package outside that prefix (should not happen in this constellation,
     * but must never throw) falls back to the whole package name, dots
     * turned into '-'.
     */
    @JvmStatic
    fun appId(context: Context): String {
        val pkg = context.packageName
        val prefix = "com.diegonmarcos."
        val tail = if (pkg.startsWith(prefix)) pkg.removePrefix(prefix) else pkg
        return tail.replace('.', '-')
    }

    /**
     * An app opts into a non-default endpoint (or opts out with "") by
     * declaring `buildConfigField "String", "TELEMETRY_INGEST_URL", "\"…\""`
     * in its OWN app/build.gradle. libs:core has no compile-time visibility
     * into the consuming app's generated BuildConfig class — the same reason
     * LogUpload's caller reads BuildConfig.LOG_INGEST_URL from its own app
     * module rather than from inside LogUpload — so this reads it reflectively
     * instead. Missing class or missing field (including apps like
     * ac_cloud-mail, whose namespace `eu.faircode.email` differs from its
     * applicationId/packageName, so no `<packageName>.BuildConfig` class
     * exists at all) is not an error: it just means the app gets the derived
     * default, which is the whole point.
     */
    private fun overrideEndpoint(context: Context): String? = runCatching {
        val cls = Class.forName("${context.packageName}.BuildConfig")
        val field = cls.getField("TELEMETRY_INGEST_URL")
        (field.get(null) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Every URL this app tries, in order: its declared override alone, else the per-app route on the mesh origin
     * (c3-infra-api.mesh.diegonmarcos.com, no auth) and then on the public one (c3-pvt, fleet bearer). Both origins are baked from the
     * fleet declaration. An override of "" turns telemetry off.
     */
    @JvmStatic
    fun endpoints(context: Context): List<String> {
        val override = overrideEndpoint(context)
        if (override != null) return listOf(override)
        if (overrideDisabled(context)) return emptyList()
        return TelemetryRoute.urls(BuildConfig.INFRA_API_MESH, BuildConfig.INFRA_API_PUBLIC, appId(context))
    }

    /** The public URL of this app's route (what the fleet transport keys on), or "". */
    @JvmStatic
    fun endpoint(context: Context): String = endpoints(context).lastOrNull().orEmpty()

    private fun overrideDisabled(context: Context): Boolean = runCatching {
        val cls = Class.forName("${context.packageName}.BuildConfig")
        (cls.getField("TELEMETRY_INGEST_URL").get(null) as? String)?.isBlank() == true
    }.getOrDefault(false)

    private fun queueDir(context: Context) = java.io.File(context.filesDir, "telemetry-queue")

    /**
     * Sends the crashes that could not leave last time (no network, mesh down). Called at every start from
     * [CoreInitProvider]; runs on its own thread, oldest first, and stops at the first failure.
     */
    @JvmStatic
    fun flushQueue(context: Context) {
        val ctx = context.applicationContext
        Thread({
            runCatching {
                val urls = endpoints(ctx)
                if (urls.isNotEmpty()) {
                    val n = TelemetryQueue.drain(queueDir(ctx)) { runCatching { send(urls, it, 5_000, 8_000); true }.getOrDefault(false) }
                    if (n > 0) Log.i(TAG, "flushed $n queued event(s)")
                }
            }.onFailure { Log.w(TAG, "flush failed: ${it.javaClass.simpleName}: ${it.message}") }
        }, "telemetry-flush").start()
    }

    /** What a crash report needs besides the stack: app version, device and ABI. */
    private fun deviceMeta(context: Context): Map<String, String> = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        mapOf(
            "version" to (pi.versionName ?: ""),
            "versionCode" to (if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()).toString(),
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "abi" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
            "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
            "package" to context.packageName,
        )
    }.getOrDefault(emptyMap())

    /** One request per leg, first 2xx wins. The fleet transport (apps that run it) takes the public URL and does mesh-first plus bearer itself. */
    private fun send(urls: List<String>, payload: String, connectTimeoutMs: Int, readTimeoutMs: Int) {
        val headers = mapOf("Content-Type" to "application/json; charset=utf-8")
        // #1093 an app that runs the fleet endpoints sends this over them (mesh first for the declared c3-infra-api pair).
        FleetHttpSeam.exchange("POST", urls.last(), headers, payload, connectTimeoutMs, readTimeoutMs)?.let {
            if (it.code in 200..299) { Log.i(TAG, "POST ${urls.last()} -> ${it.code} (${payload.length} bytes, fleet transport)"); return }
            throw java.io.IOException("HTTP ${it.code} (fleet transport)")
        }
        var last: Exception? = null
        for (url in urls) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                Log.i(TAG, "POST $url -> $code (${payload.length} bytes)")
                if (code in 200..299) return
                last = java.io.IOException("HTTP $code from $url")
            } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException("no telemetry endpoint")
    }

    /**
     * Fire-and-forget: runs on its own background thread, never throws into
     * the caller, never blocks it. [kind] is one of "log" | "debug" |
     * "action" | "probe" | "crash" — the server does not validate it, so an
     * unrecognized value is still sent, not rejected client-side.
     *
     * [log] is not part of the primary signature apps call (title/message
     * cover the common case) but is exposed as a trailing optional so
     * [postLogcat] and [installCrashHandler] can reuse this same code path.
     */
    @JvmStatic
    @JvmOverloads
    fun post(
        context: Context,
        kind: String,
        title: String? = null,
        message: String? = null,
        meta: Map<String, String> = emptyMap(),
        log: String? = null,
    ) {
        val ctx = context.applicationContext
        Thread({
            runCatching { postBlocking(ctx, kind, title, message, log, meta) }
                .onFailure { Log.w(TAG, "post failed: ${it.javaClass.simpleName}: ${it.message}") }
        }, "telemetry-post").start()
    }

    /**
     * Convenience: capture this process's own logcat (redacted, capped —
     * reuses [LogUpload.captureAndRedactLogcat]) and post it as `log`.
     */
    @JvmStatic
    @JvmOverloads
    fun postLogcat(context: Context, kind: String = "log", title: String? = null) {
        val ctx = context.applicationContext
        Thread({
            runCatching {
                val log = LogUpload.captureAndRedactLogcat()
                postBlocking(ctx, kind, title, message = null, log = log, meta = emptyMap())
            }.onFailure { Log.w(TAG, "postLogcat failed: ${it.javaClass.simpleName}: ${it.message}") }
        }, "telemetry-postlogcat").start()
    }

    /** Guards [installCrashHandler] against being wired twice (e.g. a second
     *  Application.onCreate on process restart within the same JVM instance,
     *  or a careless duplicate call) — a second install would chain the first
     *  install's handler as "previous", not the OS default. */
    private val crashHandlerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Default crash telemetry: wraps the current
     * Thread.defaultUncaughtExceptionHandler, POSTs kind="crash" with the
     * redacted stack trace as `log`, then always delegates to the previous
     * handler (so crash reporting never suppresses the system crash dialog,
     * process-death cleanup, or another crash reporter already installed).
     *
     * Posts BLOCKING, not fire-and-forget: the process is about to die, so a
     * background thread's own POST would very likely never get to run.
     * Connect/read timeouts keep a dead network from hanging the crash for
     * more than a couple of seconds.
     */
    @JvmStatic
    fun installCrashHandler(context: Context) {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val trace = LogUpload.redact(sw.toString())
                // The trace alone says WHERE it threw, never WHY. The lead-up
                // is in logcat, and this handler runs BEFORE the runtime logs
                // the fatal exception, so the two do not overlap — ship both,
                // trace first so the report is readable without scrolling.
                // Own-process logcat is all an unprivileged uid may read, which
                // is exactly the process that is dying, so it is the right dump.
                val logcat = runCatching {
                    LogUpload.captureAndRedactLogcat(CRASH_LOGCAT_BYTES)
                }.getOrElse { "logcat capture failed: ${it.javaClass.simpleName}: ${it.message}" }
                postBlocking(
                    ctx,
                    kind = "crash",
                    title = throwable.javaClass.name,
                    message = throwable.message,
                    log = trace + "\n\n--- logcat (own process, newest ${CRASH_LOGCAT_BYTES}B) ---\n" + logcat,
                    meta = deviceMeta(ctx),
                    // The process is already dead on its feet: the user is
                    // looking at a frozen app until this returns, so the crash
                    // path gets its own tighter budget than ordinary telemetry.
                    connectTimeoutMs = CRASH_CONNECT_TIMEOUT_MS,
                    readTimeoutMs = CRASH_READ_TIMEOUT_MS,
                )
            } catch (t: Throwable) {
                // Telemetry must never be the reason a crash goes unreported
                // upstream — swallow and fall through to the previous handler.
                Log.w(TAG, "crash telemetry failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                previous?.uncaughtException(thread, throwable)
                    ?: run {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
            }
        }
    }

    private fun postBlocking(
        context: Context,
        kind: String,
        title: String?,
        message: String?,
        log: String?,
        meta: Map<String, String>,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 8_000,
    ) {
        val urls = endpoints(context)
        if (urls.isEmpty()) {
            Log.i(TAG, "telemetry disabled (blank endpoint)")
            return
        }
        val payload = JSONObject().apply {
            put("kind", kind)
            title?.let { put("title", it) }
            message?.let { put("message", it) }
            log?.let { put("log", it) }
            if (meta.isNotEmpty()) put("meta", JSONObject(meta))
        }.toString()
        // A crash is written to this app's own files dir BEFORE any network attempt, so it survives the process dying mid-send:
        // it is removed once delivered, else flushQueue sends it at the next start.
        val kept = if (kind == "crash") runCatching { TelemetryQueue.enqueue(queueDir(context), payload) }.getOrNull() else null
        send(urls, payload, connectTimeoutMs, readTimeoutMs)
        kept?.delete()
    }
}
