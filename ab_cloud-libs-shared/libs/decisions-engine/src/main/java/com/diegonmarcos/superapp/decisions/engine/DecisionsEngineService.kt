package com.diegonmarcos.superapp.decisions.engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.diegonmarcos.superapp.decisions.link.IDecisionsEngine
import org.json.JSONObject

/**
 * [com.diegonmarcos.superapp.decisions.core.Engine] behind libs:decisions-link's IDecisionsEngine,
 * shipped in Cloud-Lib-Decisions-Engine.apk (#881). Every app that wants a decision binds this through
 * the contract module's client and compiles nothing of this module, so an edit here ships this APK alone.
 *
 * WHO IS ASKING comes from the binder (the calling uid's package), never from the request: an app cannot
 * name another app's quota, consent or use. The OpenRouter token is read in THIS process by
 * [EngineHolder] and never appears in a request, a result or a log line.
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a new method
 * name and a higher CONTRACT in the manifest.
 */
class DecisionsEngineService : Service() {

    fun methodNames(): Array<String> = arrayOf(DECIDE, STATUS, CONSENT)

    fun dispatch(method: String, request: String, app: String): String = when (method) {
        DECIDE -> dispatcher.decide(app, request)
        STATUS -> dispatcher.status(app)
        CONSENT -> dispatcher.consent(app, request)
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private val dispatcher by lazy { Dispatcher { EngineHolder.get(applicationContext) } }

    private val binder = object : IDecisionsEngine.Stub() {
        override fun call(method: String?, request: String?): String =
            runCatching { dispatch(method.orEmpty(), request?.ifEmpty { null } ?: "{}", callerPackage()) }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown. The message is NOT passed on: it
                // could quote what was asked.
                .getOrElse { JSONObject().put("ok", false).put("reason", "exception").toString() }

        override fun methods(): Array<String> = methodNames()
    }

    /** The first package of the calling uid; empty when the system cannot say (the engine then refuses). */
    private fun callerPackage(): String =
        packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull().orEmpty()

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val DECIDE = "decide"
        const val STATUS = "status"
        const val CONSENT = "consent"
    }
}
