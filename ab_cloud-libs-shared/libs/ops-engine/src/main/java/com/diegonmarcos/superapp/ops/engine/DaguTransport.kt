package com.diegonmarcos.superapp.ops.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * The Dagu REST API v1 calls the SuperApp's and C3's Dagu page make, moved here from libs:ops'
 * DaguClient (#871) with the request and the parse unchanged: the engine answers the same fields
 * the page rendered before.
 *
 * Request: server (the Dagu base URL), token (the caller's Authelia bearer, sent with EVERY call and
 * never kept, never logged), and for start: fileName. Answer: {"ok":true,...} or
 * {"ok":false,"error":...}.
 *
 * [dagList] and [dagRunId] are pure (they only read a response body), so the schema drift Dagu has
 * shown is executable by a JVM test; [list] and [start] are the only parts that touch the network.
 */
internal object DaguTransport {

    /** GET /api/v1/dags -> {"ok":true,"dags":[{name,fileName,displayLabel,description,schedule,lastRun?}]}. */
    fun list(req: JSONObject): String = call("GET", req, "/api/v1/dags", null) { body ->
        JSONObject().put("ok", true).put("dags", dagList(body))
    }

    /** POST /api/v1/dags/{fileName}/start -> {"ok":true,"runId":...}; only a non-blank dagRunId is a started run. */
    fun start(req: JSONObject): String {
        val fileName = req.optString("fileName")
        if (fileName.isBlank()) return error("Cannot start a DAG with no name.")
        // The endpoint declares its request body required, so an empty JSON object is sent even
        // though every property is optional.
        return call("POST", req, "/api/v1/dags/${encodePathSegment(fileName)}/start", "{}") { body ->
            val runId = runCatching { JSONObject(body).optString("dagRunId") }.getOrDefault("")
            if (runId.isBlank()) {
                throw IllegalStateException(
                    "Dagu accepted the request but returned no dagRunId — the run did NOT start. Body: ${body.take(200)}")
            }
            JSONObject().put("ok", true).put("runId", runId)
        }
    }

    /**
     * Dagu renamed this payload between versions. The deployed server answers with the lower-case
     * shape `{"dags":[{"dag":{...},"fileName":...,"latestDAGRun":{...}}]}`, while older builds
     * answered `{"DAGs":[{"Config":{...},"Status":{...}}]}`. Both are probed, lower-case first.
     */
    fun dagList(body: String): JSONArray {
        val root = JSONObject(body)
        val arr = root.optJSONArray("dags") ?: root.optJSONArray("DAGs") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Config block: `dag` (current) / `Config` / `DAG` (legacy), else the entry itself.
            val cfg = o.optJSONObject("dag") ?: o.optJSONObject("Config") ?: o.optJSONObject("DAG") ?: o
            val name = cfg.optString("name").ifBlank { cfg.optString("Name") }.ifBlank { o.optString("fileName") }
            // `fileName` is the path segment the start endpoint takes; the name when the server omits it.
            val fileName = o.optString("fileName").ifBlank { name }
            val desc = cfg.optString("description").ifBlank { cfg.optString("Description") }
            // Current schema: schedule is an array of {"expression":"*/10 * * * *","kind":"cron"}.
            // Legacy: a bare "Schedule" string, or an array of strings.
            val schedule = cfg.optString("Schedule").ifBlank {
                val sched = cfg.optJSONArray("schedule")
                when {
                    sched == null -> ""
                    sched.optJSONObject(0) != null -> sched.optJSONObject(0)!!.optString("expression")
                    else -> sched.optString(0).orEmpty()
                }
            }
            val entry = JSONObject()
                .put("name", name)
                .put("fileName", fileName)
                .put("displayLabel", cfg.optString("displayName").ifBlank { name })
                .put("description", desc)
                .put("schedule", schedule)
            val s = o.optJSONObject("latestDAGRun") ?: o.optJSONObject("Status") ?: o.optJSONObject("status")
            if (s != null) {
                entry.put("lastRun", JSONObject()
                    .put("status", if (s.has("status")) s.optInt("status") else s.optInt("Status"))
                    .put("finishedAtMs", parseEpochMs(s.optString("finishedAt").ifBlank { s.optString("FinishedAt") }))
                    .put("startedAtMs", parseEpochMs(s.optString("startedAt").ifBlank { s.optString("StartedAt") })))
            }
            out.put(entry)
        }
        return out
    }

    /** Dagu's ISO-8601 timestamp (three variants in the wild) to epoch ms; 0 = "never ran yet". */
    fun parseEpochMs(raw: String): Long {
        if (raw.isBlank() || raw == "0001-01-01T00:00:00Z") return 0L
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss")) {
            runCatching {
                val sdf = java.text.SimpleDateFormat(p, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return sdf.parse(raw)?.time ?: 0L
            }
        }
        return 0L
    }

    /** DAG names are user data from a YAML filename: percent-encoded for the path (`+` is `%20`, not a form body). */
    fun encodePathSegment(raw: String): String = URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /** Null when [url] may be called, else why not: https, and one of the fleet's own ops hosts. */
    fun refusal(url: String, hosts: List<String> = BuildConfig.OPS_HOSTS.split(',')): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "not a URL"
        if (uri.scheme != "https") return "not https"
        return if (uri.host in hosts) null else "host ${uri.host} is not one of the fleet's ops hosts"
    }

    private fun error(why: String): String = JSONObject().put("ok", false).put("error", why).toString()

    private fun call(method: String, req: JSONObject, path: String, json: String?, answer: (String) -> JSONObject): String {
        val url = req.optString("server").trimEnd('/') + path
        val why = refusal(url)
        if (why != null) return error("refused: $why")
        val token = req.optString("token")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (json != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    return error("$method $path failed: HTTP $code · ${err.take(300)}")
                }
                answer(conn.inputStream.bufferedReader().readText()).toString()
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            // LOG IT, but never the request: it carries the caller's bearer.
            Log.w("cloud-ops-engine", "$method $path failed (${e.javaClass.simpleName})")
            error("$method $path failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
