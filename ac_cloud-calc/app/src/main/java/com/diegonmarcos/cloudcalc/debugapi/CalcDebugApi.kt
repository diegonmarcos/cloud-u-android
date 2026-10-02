package com.diegonmarcos.cloudcalc.debugapi

import android.content.Context
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.engine.CalcClient
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud Calc on the fleet debug API (libs:devtools AppDebugServer: loopback, fleet token), so a
 * calculation can be checked with the phone locked:
 *
 *   /api/calc/eval?expr=2+2&mode=standard   the bound engine's answer, with that mode's options
 *   /api/calc/modes                         every declared mode (id, label, tab, kind)
 *   /api/calc/info                          the handshake verdict + the engine's own info
 *
 * Every handler goes through [CalcClient], the same handshake and binder the screens use, so a
 * green here is the app's real path. The group name is build.json::ui.debug_api.group.
 */
object CalcDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val client = CalcClient(ctx.applicationContext)
        AppDebugServer.route(
            BuildConfig.DEBUG_API_GROUP,
            listOf(
                AppDebugServer.Op("eval", "expr=<expression>&mode=<mode id, default the first expression mode>&options=<eval options JSON, optional>", "evaluate through the bound Cloud-Lib-Calc engine"),
                AppDebugServer.Op("modes", "", "every mode build.json declares: id, label, tab, kind"),
                AppDebugServer.Op("info", "", "engine handshake verdict + engine info (libqalculate version, definition counts, rates age)"),
            ),
        ) { op, q ->
            when (op) {
                "eval" -> evalJson(client, q)
                "modes" -> modesJson()
                "info" -> JSONObject().put("handshake", client.check() ?: "ok").put("engine", parse(client.info())).toString()
                else -> null
            }
        }
    }

    private fun evalJson(client: CalcClient, q: Map<String, String>): String {
        val expr = q["expr"].orEmpty()
        val mode = q["mode"]?.let { Declarations.mode(it) } ?: Declarations.modes.firstOrNull { it.kind == "expression" }
        val options = q["options"] ?: mode?.options ?: "{}"
        return JSONObject().put("expr", expr).put("mode", mode?.id ?: JSONObject.NULL).put("options", parse(options))
            .put("engine", parse(client.eval(expr, options))).toString()
    }

    private fun modesJson(): String = JSONArray().apply {
        Declarations.modes.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("tab", it.tab).put("kind", it.kind)) }
    }.toString()

    private fun parse(json: String): Any = runCatching { JSONObject(json) }.getOrElse { runCatching { JSONArray(json) }.getOrDefault(json) }
}
