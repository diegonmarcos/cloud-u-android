package com.diegonmarcos.cloudsuperintel

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP exchange with the service: the reply body on a 2xx, null on anything else (a refusal, a timeout, no route). */
fun interface FleetPost {
    fun send(path: String, contentType: String, body: ByteArray, readMs: Int): String?
}

/**
 * The fleet embeddings + rerank + PDF text service (cloud-u-containers infra-ai_embeddings-api, mesh only, base URL baked from
 * the fleet declaration as BuildConfig.EMBED_API_URL) as a [Ranker]. Plain HTTP on the mesh: off the mesh every call is null,
 * and after one failure the service is not asked again for [NotebookConfig.Semantic.retryAfterMs], so a phone without the
 * tunnel pays one short connect timeout, not one per question. Vectors are kept per text for the process, so a question embeds
 * only itself once the passages are known.
 */
class FleetRank(private val sem: NotebookConfig.Semantic, private val post: FleetPost, private val now: () -> Long = System::currentTimeMillis) : Ranker {
    private val cache = HashMap<String, DoubleArray>()
    @Volatile private var downUntil = 0L

    private fun json(path: String, body: JSONObject, readMs: Int): JSONObject? {
        if (now() < downUntil) return null
        val reply = runCatching { post.send(path, "application/json", body.toString().toByteArray(Charsets.UTF_8), readMs) }.getOrNull()
        if (reply == null) { downUntil = now() + sem.retryAfterMs; return null }
        return runCatching { JSONObject(reply) }.getOrNull()
    }

    @Synchronized override fun embed(texts: List<String>): List<DoubleArray>? {
        val missing = texts.filter { it !in cache }.distinct()
        for (batch in missing.chunked(sem.embedBatch)) {
            val r = json("/embeddings", JSONObject().put("model", sem.embedModel).put("input", JSONArray(batch)), sem.readMs) ?: return null
            val data = r.optJSONArray("data") ?: return null
            if (data.length() != batch.size) return null
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                val v = o.optJSONArray("embedding") ?: return null
                cache[batch[o.optInt("index", i)]] = DoubleArray(v.length()) { v.getDouble(it) }
            }
        }
        return texts.map { cache[it] ?: return null }
    }

    override fun rerank(query: String, docs: List<String>): List<Double>? {
        if (docs.isEmpty()) return emptyList()
        val r = json("/rerank", JSONObject().put("model", sem.rerankModel).put("query", query).put("documents", JSONArray(docs))
            .put("top_n", docs.size).put("return_documents", false), sem.readMs) ?: return null
        val res = r.optJSONArray("results") ?: return null
        val scores = arrayOfNulls<Double>(docs.size)
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            val at = o.optInt("index", -1)
            if (at !in docs.indices) return null
            scores[at] = o.getDouble("relevance_score")
        }
        return scores.map { it ?: return null }
    }

    /** The text of a PDF (the service extracts it), or null when the service does not answer or cannot read the file. */
    fun pdfText(pdf: ByteArray): String? {
        if (now() < downUntil) return null
        val reply = runCatching { post.send("/pdf", "application/pdf", pdf, sem.pdfReadMs) }.getOrNull()
        if (reply == null) { downUntil = now() + sem.retryAfterMs; return null }
        return runCatching { JSONObject(reply).getString("text") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** The real transport: [base] + path, connect timeout [NotebookConfig.Semantic.probeMs] so an absent mesh fails fast. */
        fun http(base: String, probeMs: Int) = FleetPost { path, contentType, body, readMs ->
            val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = probeMs; c.readTimeout = readMs
                c.setRequestProperty("Content-Type", contentType)
                c.setRequestProperty("Accept", "application/json")
                c.outputStream.use { it.write(body) }
                if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
            } finally { c.disconnect() }
        }

        @Volatile private var instance: FleetRank? = null

        /** The process's one ranker (it holds the vector cache); null when no service URL is baked or the notebook declares none. */
        fun shared(cfg: NotebookConfig, base: String = BuildConfig.EMBED_API_URL): FleetRank? {
            val sem = cfg.semantic ?: return null
            if (base.isBlank()) return null
            return instance ?: synchronized(this) { instance ?: FleetRank(sem, http(base, sem.probeMs)).also { instance = it } }
        }
    }
}
