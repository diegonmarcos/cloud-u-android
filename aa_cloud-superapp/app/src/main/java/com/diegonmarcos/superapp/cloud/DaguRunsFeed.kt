package com.diegonmarcos.superapp.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dagu run-HISTORY feed: `GET /api/v1/dag-runs?limit=N`, the cross-DAG
 * run stream — as opposed to `ab_cloud-libs-shared`'s `DaguClient.listDags()`,
 * which returns only the LATEST run per DAG (one row per registered
 * workflow, not a run history). Confirmed against the live server
 * (2026-09-06): the endpoint already answers most-recent-first and
 * honors `limit` server-side, so no client-side re-sort is needed.
 *
 * Deliberately its own tiny client rather than a method added to the
 * shared `DaguClient` — this task is scoped to edits under
 * aa_cloud-superapp/ only. It still shares the server URL and bearer
 * token via the same [com.diegonmarcos.superapp.ops.dagu.DaguPrefs] the
 * native Dagu page uses, so there remains one Dagu config on the device.
 */
object DaguRunsFeed {
    data class Run(
        val name: String,
        val status: Int,
        val startedAtMs: Long,
        val finishedAtMs: Long,
    )

    suspend fun recentRuns(serverUrl: String, token: String, limit: Int): List<Run> =
        withContext(Dispatchers.IO) {
            val base = serverUrl.trimEnd('/')
            val conn = (URL("$base/api/v1/dag-runs?limit=$limit")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw IOException("GET /api/v1/dag-runs failed: HTTP $code · $err")
                }
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONObject(body).optJSONArray("dagRuns") ?: return@withContext emptyList()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Run(
                        name         = o.optString("name"),
                        status       = o.optInt("status"),
                        startedAtMs  = parseEpochMs(o.optString("startedAt")),
                        finishedAtMs = parseEpochMs(o.optString("finishedAt")),
                    )
                }
            } finally { conn.disconnect() }
        }

    private fun parseEpochMs(raw: String): Long = runCatching {
        java.time.Instant.parse(raw).toEpochMilli()
    }.getOrDefault(0L)
}
