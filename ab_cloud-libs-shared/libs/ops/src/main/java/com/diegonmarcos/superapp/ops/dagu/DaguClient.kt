package com.diegonmarcos.superapp.ops.dagu

import android.content.Context
import com.diegonmarcos.superapp.ops.OpsLink
import org.json.JSONObject
import java.io.IOException

/**
 * The Dagu REST API v1 client the Dagu page drives — one row per registered DAG, coloured status
 * dot, last-run timestamp, and a Start button. Since #871 this is the CONTRACT half: the HTTPS
 * calls (and the parse of Dagu's two payload shapes) run in Cloud-Lib-Ops-Engine.apk, reached
 * through [OpsLink]; this class keeps the typed surface the page was written against.
 *
 * Threading: every method blocks. Callers MUST run on a background thread (the fragment uses a
 * plain Thread — no kotlinx-coroutines dep on libs/ops).
 *
 * Auth: Authelia bearer token. The user pastes it once into the login form and DaguPrefs persists
 * it encrypted — HERE, in the app. The engine receives it with each call and keeps nothing.
 *
 * Failure: every failure — a Dagu error, a refused host, an engine that is not installed — raises
 * [IOException] carrying the sentence the page shows the user.
 */
class DaguClient(private val context: Context, private val serverUrl: String, private val token: String) {

    /** GET /api/v1/dags — every registered DAG with its last run summary. */
    fun listDags(): DaguDagList {
        val answer = check(OpsLink.daguList(context, request()))
        val arr = answer.optJSONArray("dags") ?: return DaguDagList(emptyList())
        val out = mutableListOf<DaguDag>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += DaguDag(
                name = o.optString("name"),
                fileName = o.optString("fileName"),
                displayLabel = o.optString("displayLabel"),
                description = o.optString("description"),
                schedule = o.optString("schedule"),
                lastRun = o.optJSONObject("lastRun")?.let { r ->
                    DaguRun(
                        status = r.optInt("status"),
                        finishedAtMs = r.optLong("finishedAtMs"),
                        startedAtMs = r.optLong("startedAtMs"),
                    )
                },
            )
        }
        return DaguDagList(out)
    }

    /**
     * POST /api/v1/dags/{fileName}/start — create a DAG-run from the DAG definition and start
     * executing it (the call the Dagu web UI's "Start" button makes). Success is a 2xx *and* a
     * non-blank `dagRunId`; the engine refuses to call anything else a started run, and 409 (the
     * DAG is already running under singleton mode) comes back verbatim because Dagu's wording is
     * already the clearest explanation. Returns the new run id.
     */
    fun startDag(fileName: String): String {
        if (fileName.isBlank()) throw IOException("Cannot start a DAG with no name.")
        val runId = check(OpsLink.daguStart(context, request().put("fileName", fileName))).optString("runId")
        if (runId.isBlank()) throw IOException("The ops engine accepted the request but returned no run id — the run did NOT start.")
        return runId
    }

    private fun request() = JSONObject().put("server", serverUrl).put("token", token)

    private fun check(answer: JSONObject): JSONObject {
        if (!answer.optBoolean("ok")) throw IOException(answer.optString("error", "the ops engine did not answer"))
        return answer
    }
}
