package com.diegonmarcos.superapp.analytics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The client of the analytics SINK ENGINE (#871): the HTTP POST to Umami and Matomo runs in
 * Cloud-Lib-Analytics-Sink.apk, so an edit to how an event is sent republishes that APK and no
 * app. This module keeps what is per app and must survive the engine's absence: the bounded
 * per-sink queue, the consent flag, the visitor id and the site ids.
 *
 * ABSENT, OLD OR BROKEN ENGINE = A BACKEND THAT IS DOWN. [deliver] answers false and the event
 * stays in its sink's queue, exactly as it does for an unreachable server (the queue's own
 * bound keeps that from growing). Nothing here throws and nothing blocks the caller: the
 * drain already runs on the analytics worker thread.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] resolves the declared action in the
 * declared package (engine-client.json, resolved from the fleet manifest at build time and
 * queried in this module's manifest) and reads the CONTRACT it declares.
 *
 * THE ENGINE IS BOUND ONLY WHILE A DRAIN RUNS ([release] after it): a bound engine keeps its
 * process alive, and seventeen apps holding it for their whole lifetime for a ping per screen
 * is the wrong price.
 */
internal class SinkLink(context: Context) {

    private val ctx = context.applicationContext

    @Volatile private var remote: IAnalyticsSink? = null
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var bound = false
    @Volatile private var lastWhy: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { IAnalyticsSink.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
    }

    /** Null when the engine is ready (and bound), else the sentence that says what to do. */
    @Synchronized
    fun check(): String? {
        if (remote != null) return null
        val pm = ctx.packageManager
        val pkg = BuildConfig.ANALYTICS_ENGINE_PACKAGE
        val needed = BuildConfig.ANALYTICS_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.ANALYTICS_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) "$pkg has no analytics sink service — update it from Store ▸ Cloud Constellation ▸ Libs"
                   else "the analytics sink engine $pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
        return if (connect(service.packageName, service.name)) null else "$pkg did not accept the bind"
    }

    private fun connect(pkg: String, cls: String): Boolean {
        val l = CountDownLatch(1)
        latch = l
        bound = runCatching {
            ctx.bindService(Intent().setClassName(pkg, cls), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) return false
        runCatching { l.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return remote != null
    }

    /**
     * Hand one event to the engine. True = the backend took it (drop it from the queue);
     * false = keep it queued and try again on the next flush.
     */
    fun umami(request: JSONObject): Boolean = delivered(UMAMI) { ask(UMAMI, request.toString()) }

    fun matomo(request: JSONObject): Boolean = delivered(MATOMO) { ask(MATOMO, request.toString()) }

    private fun delivered(sink: String, call: () -> String): Boolean {
        val answer = try {
            call()
        } catch (e: Exception) {
            note(e.message ?: e.javaClass.simpleName)
            return false
        }
        val ok = runCatching { JSONObject(answer) }.getOrNull()
        if (ok == null) { note("the analytics sink answered $sink with something that is not JSON"); return false }
        if (!ok.optBoolean("ok")) { note("$sink: ${ok.optString("error", "not delivered")}"); return false }
        lastWhy = null
        return true
    }

    private fun ask(method: String, request: String): String {
        val why = check()
        val r = remote
        if (why != null || r == null) throw IllegalStateException(why ?: "the analytics sink engine is not ready")
        return r.send(method, request) ?: throw IllegalStateException("the analytics sink did not answer $method")
    }

    /** Let go of the engine once a drain is done, so it can stop when nobody needs it. */
    @Synchronized
    fun release() {
        if (bound) runCatching { ctx.unbindService(connection) }
        bound = false
        remote = null
    }

    /** One line per distinct reason, not one per event: an absent engine must not flood the log. */
    private fun note(why: String) {
        if (why == lastWhy) return
        lastWhy = why
        Log.w("cloud-analytics", "not delivered, kept queued: $why")
    }

    private companion object {
        /** The engine CONTRACT meta-data key (libs/analytics-sink's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        // The engine's method names (AnalyticsSinkService). Strings, not an import: no consumer
        // compiles the engine, and that is the point.
        const val UMAMI = "umami"
        const val MATOMO = "matomo"

        /** Long enough for a cold engine process to start; the drain is on the worker thread. */
        const val BIND_TIMEOUT_MS = 4000L
    }
}
