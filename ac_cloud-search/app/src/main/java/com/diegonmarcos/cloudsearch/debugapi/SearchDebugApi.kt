package com.diegonmarcos.cloudsearch.debugapi

import android.content.Context
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.core.Calculators
import com.diegonmarcos.cloudsearch.core.Things
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
 *   /api/search/analysis?v=house             the Analysis page's numbers: the jobs statistics
 *                                            (v=jobs&q=&city=) or the market series (v=house),
 *                                            with each series' status
 *   /api/search/feed?v=jobs                  the Feed page: the vertical's headlines and each
 *                                            feed's status
 *   /api/search/things?q=laptop&city=Köln    #903 the Things comparison: stores of the item's kind
 *                                            around lat/lon (or the typed city, or the selected
 *                                            one), each with its price or why it has none, and
 *                                            each source's status; radius= overrides the setting
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
                AppDebugServer.Op("analysis", "v=<vertical id>&q=<term, jobs>&city=<city id, jobs>", "the Analysis page's numbers: jobs statistics or market series with their sources' status"),
                AppDebugServer.Op("feed", "v=<vertical id>", "the Feed page's headlines and each feed's status"),
                AppDebugServer.Op("things", "q=<item>&lat=<n>&lon=<n>|city=<typed city>&radius=<km, optional>", "the Things comparison: stores near the place with their price or why none, and each source's status"),
                AppDebugServer.Op("agents", "", "#913 the draft-only agents, their last run, the model and the budget (never the token)"),
                AppDebugServer.Op("runs", "n=<count, default 10>", "#913 the run log with each run's audit lines"),
                AppDebugServer.Op("reports", "", "#913 what agents published, newest first"),
                AppDebugServer.Op("report", "id=<report id>", "#913 one report with its drafts"),
                AppDebugServer.Op("cloud", "kind=apps|messages&q=<text>", "#913 the Cloud Search section: fleet apps, or Cloud Mail messages through the read-only agent door"),
            ),
        ) { op, q ->
            when (op) {
                "verticals" -> verticals(Services.get(app)).toString()
                "query" -> query(Services.get(app), q).toString()
                "calc" -> calc(Services.get(app), q).toString()
                "analysis" -> analysis(Services.get(app), q).toString()
                "feed" -> feed(Services.get(app), q).toString()
                "things" -> things(Services.get(app), q).toString()
                "agents" -> agents(Services.get(app)).toString()
                "runs" -> runs(Services.get(app), q).toString()
                "reports" -> reports(Services.get(app)).toString()
                "report" -> report(Services.get(app), q).toString()
                "cloud" -> cloud(Services.get(app), q).toString()
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

    fun analysis(s: Services, q: Map<String, String>): JSONObject {
        val v = s.cfg.vertical(q["v"].orEmpty()) ?: return JSONObject().put("ok", false).put("error", "v must be one of ${s.cfg.verticals.map { it.id }}")
        return when (v.analysis) {
            "jobs" -> s.engine.analysis(v.id, q["q"].orEmpty(), q["city"] ?: s.prefs.city)
                ?.let { JSONObject().put("ok", true).put("kind", "jobs").put("jobs", it.toJson()) }
                ?: JSONObject().put("ok", false).put("error", "no source answered")
            "market" -> s.engine.market(v.id)!!.let { m ->
                JSONObject().put("ok", true).put("kind", "market").put("market", m.toJson()).put("sources", s.engine.statusJson(m.statuses))
            }
            else -> JSONObject().put("ok", true).put("kind", v.analysis)
        }
    }

    fun feed(s: Services, q: Map<String, String>): JSONObject {
        val v = s.cfg.vertical(q["v"].orEmpty())?.takeIf { it.feeds.isNotEmpty() }
            ?: return JSONObject().put("ok", false).put("error", "v must be one of ${s.cfg.verticals.filter { it.feeds.isNotEmpty() }.map { it.id }}")
        val f = s.engine.feed(v.id)
        return JSONObject().put("ok", true).put("vertical", v.id).put("fetched", f.fetched).put("sources", s.engine.statusJson(f.statuses))
            .put("items", JSONArray(f.items.map { JSONObject().put("title", it.title).put("feed", it.feed).put("url", it.url ?: JSONObject.NULL).put("date", it.date ?: JSONObject.NULL) }))
    }

    fun things(s: Services, q: Map<String, String>): JSONObject {
        val t = s.cfg.things ?: return JSONObject().put("ok", false).put("error", "build.json::search.things is not declared")
        val radius = t.clampRadius(q["radius"]?.toIntOrNull() ?: s.prefs.radiusKm)
        val lat = q["lat"]?.toDoubleOrNull()
        val lon = q["lon"]?.toDoubleOrNull()
        val area = when {
            lat != null && lon != null -> Things.Area(lat, lon, radius, "")
            !q["city"].isNullOrBlank() -> s.things.geocode(q["city"]!!, radius).first
                ?: return JSONObject().put("ok", false).put("error", "no place called '${q["city"]}'")
            else -> s.cfg.city(s.prefs.city).let { Things.Area(it.lat, it.lon, radius, it.label) }
        }
        val r = s.things.compare(area, q["q"].orEmpty())
        return JSONObject(s.things.json(r).toString()).put("ok", true).put("sources", s.engine.statusJson(r.statuses))
    }

    private fun noAgents() = JSONObject().put("ok", false).put("error", "build.json::search.agents is not declared")

    /** #913 the agents with their last run, the model and the budget. The token is never part of the answer. */
    fun agents(s: Services): JSONObject {
        val svc = s.agents ?: return noAgents()
        val last = svc.runs.all()
        return JSONObject().put("ok", true).put("mode", "draft_only")
            .put("model", svc.prefs.model).put("budget_run_usd", svc.prefs.budgetRunUsd).put("budget_day_usd", svc.prefs.budgetDayUsd)
            .put("spent_today_usd", svc.prefs.spentToday(System.currentTimeMillis()))
            .put("agents", JSONArray(svc.agents.agents.map { a ->
                JSONObject().put("id", a.id).put("label", a.label).put("mode", "draft_only").put("uses_llm", a.usesLlm)
                    .put("last_run", last.firstOrNull { it.agentId == a.id }?.let { JSONObject().put("id", it.id).put("status", it.status).put("summary", it.summary).put("ended_at", it.endedAt) } ?: JSONObject.NULL)
            }))
    }

    fun runs(s: Services, q: Map<String, String>): JSONObject {
        val svc = s.agents ?: return noAgents()
        val n = (q["n"]?.toIntOrNull() ?: 10).coerceIn(1, 100)
        return JSONObject().put("ok", true).put("runs", JSONArray(svc.runs.all().take(n).map { it.toJson() }))
    }

    fun reports(s: Services): JSONObject {
        val svc = s.agents ?: return noAgents()
        return JSONObject().put("ok", true).put("reports", JSONArray(svc.reports.newestFirst().map { r ->
            JSONObject().put("id", r.id).put("agent", r.agentId).put("kind", r.kind).put("title", r.title).put("created_at", r.createdAt)
                .put("summary", r.summary).put("items", r.items.size)
        }))
    }

    fun report(s: Services, q: Map<String, String>): JSONObject {
        val svc = s.agents ?: return noAgents()
        val r = svc.reports.get(q["id"].orEmpty()) ?: return JSONObject().put("ok", false).put("error", "no report with that id")
        return JSONObject().put("ok", true).put("report", r.toJson())
    }

    fun cloud(s: Services, q: Map<String, String>): JSONObject {
        val cloud = s.cfg.cloud ?: return JSONObject().put("ok", false).put("error", "build.json::search.cloud is not declared")
        return when (q["kind"]) {
            "apps" -> {
                val all = com.diegonmarcos.cloudsearch.core.CloudConfig.fleetApps(String(android.util.Base64.decode(BuildConfig.FLEET_APPS_B64, android.util.Base64.DEFAULT), Charsets.UTF_8))
                JSONObject().put("ok", true).put("apps", JSONArray(cloud.apps(all, q["q"].orEmpty()).map { JSONObject().put("id", it.id).put("label", it.label).put("package", it.pkg) }))
            }
            "messages" -> {
                val svc = s.agents ?: return noAgents()
                runCatching { svc.mail.messages("", q["q"].orEmpty(), cloud.messagesSince(System.currentTimeMillis()), cloud.messagesLimit) }.fold(
                    { rows -> JSONObject().put("ok", true).put("messages", JSONArray(rows.map { JSONObject().put("id", it.id).put("subject", it.subject).put("from", it.fromEmail).put("received_at", it.receivedAt) })) },
                    { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) },
                )
            }
            else -> JSONObject().put("ok", false).put("error", "kind must be apps or messages")
        }
    }
}
