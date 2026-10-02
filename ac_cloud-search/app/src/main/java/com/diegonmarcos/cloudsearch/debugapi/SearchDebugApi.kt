package com.diegonmarcos.cloudsearch.debugapi

import android.content.Context
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.core.Calculators
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud Search on the fleet debug API (libs:devtools AppDebugServer: loopback, fleet token), so the
 * app can be checked with the phone locked:
 *
 *   /api/search/verticals                    every vertical, its subpages, and every source with
 *                                            its kind, whether it is enabled, and why not
 *   /api/search/query?v=jobs&q=&city=berlin  the real query through the engine and cache the screens
 *                                            use: listings + each source's status (ok, cached,
 *                                            stale, error, skipped, link, disabled)
 *   /api/search/calc?name=payslip&gross=5000 a calculator's outputs and warnings; any field not
 *                                            given takes its declared default
 *
 * The group is build.json::ui.debug_api.group. No op is named `state` (GET /api/state's key), and
 * nothing here reads the AI token.
 */
object SearchDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route(
            BuildConfig.DEBUG_API_GROUP,
            listOf(
                AppDebugServer.Op("verticals", "", "every vertical, subpage and source (kind, enabled, why)"),
                AppDebugServer.Op("query", "v=<vertical id>&q=<search term>&city=<city id, optional>", "run a search through the app's engine and cache: listings + per-source status"),
                AppDebugServer.Op("calc", "name=<calculator id>&<field>=<number>...", "a calculator's outputs and warnings (missing fields take their defaults)"),
            ),
        ) { op, q ->
            when (op) {
                "verticals" -> verticals(Services.get(app)).toString()
                "query" -> query(Services.get(app), q).toString()
                "calc" -> calc(Services.get(app), q).toString()
                else -> null
            }
        }
    }

    fun verticals(s: Services): JSONObject = JSONObject()
        .put("default", s.cfg.defaultVertical)
        .put("verticals", JSONArray(s.cfg.verticals.map { v ->
            JSONObject().put("id", v.id).put("label", v.label).put("title", v.title)
                .put("subpages", JSONArray(v.subpages)).put("calculators", JSONArray(v.calculators)).put("analysis", v.analysis)
                .put("sources", JSONArray(v.sources.mapNotNull { s.cfg.sources[it] }.map { src ->
                    JSONObject().put("id", src.id).put("label", src.label).put("kind", src.kind).put("enabled", src.enabled)
                        .put("fetched", src.fetches).put("why", src.why)
                }))
        }))

    fun query(s: Services, q: Map<String, String>): JSONObject {
        val v = q["v"].orEmpty()
        if (s.cfg.vertical(v) == null) return JSONObject().put("ok", false).put("error", "v must be one of ${s.cfg.verticals.map { it.id }}")
        val r = s.engine.query(v, q["q"].orEmpty(), q["city"] ?: s.prefs.city)
        return JSONObject().put("ok", true).put("vertical", r.vertical).put("q", r.q).put("city", r.city)
            .put("count", r.listings.size).put("sources", s.engine.statusJson(r.statuses))
            .put("listings", JSONArray(r.listings.map { it.toJson() }))
    }

    fun calc(s: Services, q: Map<String, String>): JSONObject {
        val name = q["name"].orEmpty()
        val c = s.cfg.calculators[name] ?: return JSONObject().put("ok", false).put("error", "name must be one of ${s.cfg.calculators.keys}")
        val given = c.fields.mapNotNull { f -> q[f.id]?.replace(',', '.')?.toDoubleOrNull()?.let { f.id to it } }.toMap()
        val r = Calculators.run(s.cfg, name, given)
        return JSONObject().put("ok", true).put("name", name)
            .put("inputs", JSONObject(c.fields.associate { it.id to (given[it.id] ?: it.default) }))
            .put("outputs", JSONObject(r.values))
            .put("warnings", JSONArray(r.warnings.map { c.warnings[it] ?: it }))
    }
}
