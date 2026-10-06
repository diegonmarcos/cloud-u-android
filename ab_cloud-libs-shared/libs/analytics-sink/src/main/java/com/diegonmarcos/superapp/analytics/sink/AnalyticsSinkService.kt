package com.diegonmarcos.superapp.analytics.sink

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.superapp.analytics.IAnalyticsSink
import org.json.JSONObject

/**
 * [SinkTransport] behind libs:analytics' IAnalyticsSink, shipped in Cloud-Lib-Analytics-Sink.apk
 * (#871). Every app that reports analytics binds this through the contract module's client and
 * compiles nothing of this module, so an edit here ships this APK alone.
 *
 * ONE CALL = ONE EVENT TO ONE BACKEND, so the caller's per-sink queue keeps its two outcomes
 * apart ("delivered to Umami" and "delivered to Matomo" are separately true or false). The
 * CLIENT owns the queue, the consent flag and the visitor id; this process owns the POST.
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a
 * new method name and a higher CONTRACT in the manifest. Not a DataBackendService because the
 * client must stay dependency-free (upstream forks compile it with no shared-module graph), so
 * it carries its own one-method wire.
 */
class AnalyticsSinkService : Service() {

    fun methodNames(): Array<String> = arrayOf(UMAMI, MATOMO)

    fun dispatch(method: String, request: String): String = when (method) {
        UMAMI -> SinkTransport.send(SinkTransport.umami(JSONObject(request)))
        MATOMO -> SinkTransport.send(SinkTransport.matomo(JSONObject(request)))
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private val binder = object : IAnalyticsSink.Stub() {
        override fun send(method: String?, request: String?): String =
            runCatching { dispatch(method.orEmpty(), request.orEmpty()) }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown.
                .getOrElse { t -> JSONObject().put("ok", false).put("error", t.message ?: t.toString()).toString() }

        override fun methods(): Array<String> = methodNames()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val UMAMI = "umami"
        const val MATOMO = "matomo"
    }
}
