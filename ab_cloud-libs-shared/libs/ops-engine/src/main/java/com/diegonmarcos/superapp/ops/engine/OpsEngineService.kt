package com.diegonmarcos.superapp.ops.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.superapp.ops.IOpsEngine
import org.json.JSONObject

/**
 * [DaguTransport] behind libs:ops' IOpsEngine, shipped in Cloud-Lib-Ops-Engine.apk (#871). The
 * SuperApp and C3 bind this through the contract module's client and compile nothing of this
 * module, so an edit here ships this APK alone.
 *
 * ONE CALL = ONE DAGU REQUEST. The CLIENT owns the server URL and the bearer and sends both with
 * every call; this process keeps neither, and logs neither.
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a
 * new method name and a higher CONTRACT in the manifest.
 */
class OpsEngineService : Service() {

    fun methodNames(): Array<String> = arrayOf(DAGU_LIST, DAGU_START)

    fun dispatch(method: String, request: String): String = when (method) {
        DAGU_LIST -> DaguTransport.list(JSONObject(request))
        DAGU_START -> DaguTransport.start(JSONObject(request))
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private val binder = object : IOpsEngine.Stub() {
        override fun call(method: String?, request: String?): String =
            runCatching { dispatch(method.orEmpty(), request.orEmpty()) }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown.
                .getOrElse { t -> JSONObject().put("ok", false).put("error", t.message ?: t.toString()).toString() }

        override fun methods(): Array<String> = methodNames()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val DAGU_LIST = "dagu_list"
        const val DAGU_START = "dagu_start"
    }
}
