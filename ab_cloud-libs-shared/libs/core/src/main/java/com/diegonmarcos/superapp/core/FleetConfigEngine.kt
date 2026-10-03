package com.diegonmarcos.superapp.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #825 the client of [IFleetConfigEngine]: binds Cloud-Lib-Fleetconfig, checks its
 * [CONTRACT_VERSION] (the handshake), makes one call and unbinds. Provider calls are rare (a
 * fleet export or import), so nothing is held between them. Answers a JSON error object, never
 * throws, when the engine is missing or speaks another version.
 *
 * BLOCKS: never call it on the main thread (the bind completes there).
 */
object FleetConfigEngine {

    /** The engine wire's version. Bump it only together with the engine's (both sides refuse a mismatch). */
    const val CONTRACT_VERSION = 1
    const val PACKAGE = "com.diegonmarcos.cloudlib.fleetconfig"
    const val SERVICE = "com.diegonmarcos.superapp.fleetconfig.FleetConfigEngineService"

    const val PLAN = "plan"
    const val EXPORT = "export"
    const val IMPORT = "import"

    fun call(ctx: Context, method: String, vararg args: String): JSONObject {
        val app = ctx.applicationContext
        var remote: IFleetConfigEngine? = null
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = binder?.let { IFleetConfigEngine.Stub.asInterface(it) }; latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { remote = null }
        }
        val bound = runCatching {
            app.bindService(Intent().setClassName(PACKAGE, SERVICE), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) return error("Cloud-Lib-Fleetconfig is not installed — install it from Configs → Constellation → Libs")
        try {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val r = remote ?: return error("Cloud-Lib-Fleetconfig did not bind")
            val v = r.contractVersion()
            if (v != CONTRACT_VERSION) return error("Cloud-Lib-Fleetconfig speaks engine contract $v, this app $CONTRACT_VERSION — update the older one")
            if (method !in r.methods()) return error("Cloud-Lib-Fleetconfig does not answer $method")
            return JSONObject(r.call(method, arrayOf(*args)) ?: return error("Cloud-Lib-Fleetconfig answered nothing"))
        } catch (t: Throwable) {
            return error("Cloud-Lib-Fleetconfig: ${t.javaClass.simpleName}")
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }

    private fun error(why: String) = JSONObject().put(FleetConfig.KEY_ERROR, why)

    private const val BIND_TIMEOUT_MS = 5000L
}
