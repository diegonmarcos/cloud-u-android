package com.diegonmarcos.superapp.ops

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The client of the OPS ENGINE (#871): the HTTPS calls to Dagu run in Cloud-Lib-Ops-Engine.apk, so
 * an edit to how Dagu is called republishes that APK and no app. This module keeps what is the
 * caller's: the Dagu page, the server URL and the bearer storage (DaguPrefs); the bearer travels in
 * each request and the engine keeps nothing.
 *
 * ABSENT, OLD OR BROKEN ENGINE = A JSON ERROR THE CALLER SHOWS. both calls always answers a JSON
 * object: {"ok":true,...} from the engine, or {"ok":false,"error":...} with the sentence that says
 * what to install. Nothing here throws.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] resolves the declared action in the declared
 * package (engine-client.json, resolved from the fleet manifest at build time and queried in this
 * module's manifest) and reads the CONTRACT it declares. THE ENGINE IS BOUND ONLY FOR THE CALL.
 *
 * Blocking: call from a background thread, as the page already does.
 */
internal object OpsLink {

    /** GET /api/v1/dags through the engine. [request] carries the server and the bearer. */
    fun daguList(context: Context, request: JSONObject): JSONObject = ask("dagu_list", context, request)

    /** POST /api/v1/dags/{fileName}/start through the engine. */
    fun daguStart(context: Context, request: JSONObject): JSONObject = ask("dagu_start", context, request)

    private fun ask(method: String, context: Context, request: JSONObject): JSONObject {
        val ctx = context.applicationContext
        val link = Conn()
        return try {
            val why = link.check(ctx)
            val r = link.remote
            if (why != null || r == null) return fail(why ?: "the ops engine is not ready")
            val answer = r.call(method, request.toString()) ?: return fail("the ops engine did not answer $method")
            runCatching { JSONObject(answer) }.getOrNull() ?: fail("the ops engine answered $method with something that is not JSON")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            link.release(ctx)
        }
    }

    private fun fail(why: String) = JSONObject().put("ok", false).put("error", why)

    private class Conn : ServiceConnection {
        @Volatile var remote: IOpsEngine? = null
        @Volatile private var latch: CountDownLatch? = null
        @Volatile private var bound = false

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { IOpsEngine.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }

        /** Null when the engine is ready (and bound), else the sentence that says what to do. */
        fun check(ctx: Context): String? {
            val pm = ctx.packageManager
            val pkg = BuildConfig.OPS_ENGINE_PACKAGE
            val needed = BuildConfig.OPS_ENGINE_MIN_CONTRACT
            val service = pm.resolveService(Intent(BuildConfig.OPS_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
                ?.serviceInfo
            if (service == null) {
                val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
                return if (installed) "$pkg has no ops service — update it from Store ▸ Cloud Constellation ▸ Libs"
                       else "the ops engine $pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
            }
            val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
            if (found < needed) return "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
            val l = CountDownLatch(1)
            latch = l
            bound = runCatching {
                ctx.bindService(Intent().setClassName(service.packageName, service.name), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) return "$pkg did not accept the bind"
            runCatching { l.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            return if (remote != null) null else "$pkg did not accept the bind"
        }

        fun release(ctx: Context) {
            if (bound) runCatching { ctx.unbindService(this) }
            bound = false
            remote = null
        }
    }

    /** The engine CONTRACT meta-data key (libs/ops-engine's manifest). */
    private const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

    /** Long enough for a cold engine process to start; the caller is on a worker thread. */
    private const val BIND_TIMEOUT_MS = 4000L
}
