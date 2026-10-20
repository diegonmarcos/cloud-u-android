package com.diegonmarcos.cloudlib.mounts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The client of the MOUNTS ENGINE (#871): ftp / sftp / ssh / webdav run in Cloud-Lib-Mounts-Engine.apk, so an
 * edit to a protocol client or a library bump republishes that APK and no app. This module keeps what is the
 * caller's: the screen, the mount list, the credentials and the models.
 *
 * ABSENT, OLD OR BROKEN ENGINE = AN IOException THE CALLER SHOWS, naming what to install. Nothing here
 * crashes.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [unavailable] resolves the declared action in the declared
 * package (engine-client.json, resolved from the fleet manifest at build time and queried in this module's
 * manifest) and reads the CONTRACT it declares. THE ENGINE IS BOUND ONLY WHILE A SESSION IS OPEN: one step on
 * a connection and, if its answer is long, its chunks. The connection itself lives in the engine between
 * steps, by handle.
 *
 * Blocking: call from a background thread, as the screens already do.
 */
class MountLink(context: Context) : MountTransport {
    private val ctx: Context = context.applicationContext

    override fun unavailable(): String? = resolve(ctx).second

    private companion object {
        /** The engine CONTRACT meta-data key (libs/mounts-engine's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        /** Long enough for a cold engine process to start; the caller is on a worker thread. */
        const val BIND_TIMEOUT_MS = 4000L

        /** THE HANDSHAKE: the service, or null and the sentence that says what to do. Resolves before any bind. */
        fun resolve(ctx: Context): Pair<ServiceInfo?, String?> {
            val pm = ctx.packageManager
            val pkg = BuildConfig.MOUNTS_ENGINE_PACKAGE
            val needed = BuildConfig.MOUNTS_ENGINE_MIN_CONTRACT
            val service = pm.resolveService(Intent(BuildConfig.MOUNTS_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
                ?.serviceInfo
            if (service == null) {
                val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
                return null to if (installed) "$pkg has no mounts service — update it from Store ▸ Cloud Constellation ▸ Libs"
                               else "the mounts engine $pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
            }
            val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
            if (found < needed) return null to "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
            return service to null
        }
    }

    override fun session(): MountTransport.Session {
        val (service, why) = resolve(ctx)
        if (service == null) throw IOException(why ?: "the mounts engine is not ready")
        val conn = Conn()
        val l = CountDownLatch(1)
        conn.latch = l
        conn.bound = runCatching {
            ctx.bindService(Intent().setClassName(service.packageName, service.name), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!conn.bound) throw IOException("${service.packageName} did not accept the bind")
        runCatching { l.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        if (conn.remote == null) { conn.release(ctx); throw IOException("${service.packageName} did not accept the bind") }
        return object : MountTransport.Session {
            override fun open(request: JSONObject) = ask("open", request)
            override fun fs(request: JSONObject) = ask("fs", request)
            override fun chunk(request: JSONObject) = ask("chunk", request)
            override fun closeHandle(request: JSONObject) = ask("close", request)
            override fun close() = conn.release(ctx)

            private fun ask(method: String, request: JSONObject): JSONObject {
                val r = conn.remote ?: throw IOException("the mounts engine went away")
                val answer = try { r.call(method, request.toString()) } catch (e: Exception) { throw IOException(e.message ?: e.javaClass.simpleName) }
                    ?: throw IOException("the mounts engine did not answer $method")
                return runCatching { JSONObject(answer) }.getOrNull() ?: throw IOException("the mounts engine answered $method with something that is not JSON")
            }
        }
    }

    private class Conn : ServiceConnection {
        @Volatile var remote: IMountsEngine? = null
        @Volatile var latch: CountDownLatch? = null
        @Volatile var bound = false

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { IMountsEngine.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }

        fun release(ctx: Context) {
            if (bound) runCatching { ctx.unbindService(this) }
            bound = false
            remote = null
        }
    }
}
