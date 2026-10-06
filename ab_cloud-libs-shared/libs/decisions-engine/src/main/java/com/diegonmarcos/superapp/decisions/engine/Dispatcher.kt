package com.diegonmarcos.superapp.decisions.engine

import com.diegonmarcos.superapp.decisions.core.Engine
import org.json.JSONObject

/**
 * The wire's three methods over an [Engine], with no Android in it so its suite runs on the JVM. The
 * service's `when (method)` routes to them (the engine-service tester reads that one). [engine] returns null
 * when the policy did not load: every method then answers `no_policy`, which a caller reads as "no opinion".
 */
class Dispatcher(private val engine: () -> Engine?) {

    fun decide(app: String, request: String): String =
        engine()?.decide(app, JSONObject(request))?.toString() ?: noPolicy()

    fun status(app: String): String = engine()?.status(app)?.toString() ?: noPolicy()

    fun consent(app: String, request: String): String =
        engine()?.setConsent(app, JSONObject(request))?.toString() ?: noPolicy()

    private fun noPolicy() = JSONObject().put("ok", false).put("reason", "no_policy").toString()
}
