package com.diegonmarcos.cloudtoolssmallllms.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One POST to the service: the reply body on a 2xx, null on anything else (refused, timeout, no route). */
fun interface RetrievalPost {
    fun send(path: String, body: String, readMs: Int): String?
}

/**
 * The fleet embeddings + rerank service (cloud-u-containers infra-ai_embeddings-api, mesh only, base URL baked from the fleet
 * declaration as BuildConfig.EMBED_API_URL) as the Retrieval steps of the Apps use it: [rankByEmbedding] orders passages by
 * cosine to the question (bge-m3), [rerank] re-scores them with the cross-encoder. Every call is null when the service does not
 * answer; after a failure it is left alone for [RetrievalService.retryAfterMs] so a phone off the mesh pays one short timeout.
 */
class FleetRetrieval(private val cfg: RetrievalService, private val post: RetrievalPost, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var downUntil = 0L

    private fun call(path: String, body: JSONObject): JSONObject? {
        if (now() < downUntil) return null
        val reply = runCatching { post.send(path, body.toString(), cfg.readMs) }.getOrNull()
        if (reply == null) { downUntil = now() + cfg.retryAfterMs; return null }
        return runCatching { JSONObject(reply) }.getOrNull()
    }

    private fun embed(texts: List<String>): List<DoubleArray>? {
        val out = ArrayList<DoubleArray>()
        for (batch in texts.chunked(cfg.embedBatch)) {
            val data = call("/embeddings", JSONObject().put("model", cfg.embedModel).put("input", JSONArray(batch)))?.optJSONArray("data") ?: return null
            if (data.length() != batch.size) return null
            val byIndex = arrayOfNulls<DoubleArray>(batch.size)
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                val v = o.optJSONArray("embedding") ?: return null
                val at = o.optInt("index", i)
                if (at !in batch.indices) return null
                byIndex[at] = DoubleArray(v.length()) { v.getDouble(it) }
            }
            out.addAll(byIndex.map { it ?: return null })
        }
        return out
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    /** [passages] with their cosine to [question], best first; null when the service does not answer. */
    fun rankByEmbedding(question: String, passages: List<String>): List<Pair<String, Double>>? {
        val v = embed(listOf(question) + passages)?.takeIf { it.size == passages.size + 1 } ?: return null
        return passages.indices.map { passages[it] to cosine(v[0], v[it + 1]) }.sortedByDescending { it.second }
    }

    /** [passages] with the reranker's score for [question], best first; null when the service does not answer. */
    fun rerank(question: String, passages: List<String>): List<Pair<String, Double>>? {
        val res = call("/rerank", JSONObject().put("model", cfg.rerankModel).put("query", question).put("documents", JSONArray(passages))
            .put("top_n", passages.size).put("return_documents", false))?.optJSONArray("results") ?: return null
        val scores = arrayOfNulls<Double>(passages.size)
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            val at = o.optInt("index", -1)
            if (at !in passages.indices) return null
            scores[at] = o.getDouble("relevance_score")
        }
        return passages.indices.map { passages[it] to (scores[it] ?: return null) }.sortedByDescending { it.second }
    }

    companion object {
        /** The real transport: [base] + path, connect timeout [probeMs] so an absent mesh fails fast. */
        fun http(base: String, probeMs: Int) = RetrievalPost { path, body, readMs ->
            val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = probeMs; c.readTimeout = readMs
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
            } finally { c.disconnect() }
        }

        /** The client for the declared service at [base]; null when the data declares none or no URL is baked. */
        fun create(cfg: RetrievalService?, base: String): FleetRetrieval? =
            if (cfg == null || base.isBlank()) null else FleetRetrieval(cfg, http(base, cfg.probeMs))
    }
}

/** What a Retrieval step does with its input: the first paragraph is the question, every later paragraph one passage. */
object Retrieval {
    fun split(text: String): Pair<String, List<String>> {
        val parts = text.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        return (parts.firstOrNull() ?: "") to parts.drop(1)
    }

    fun join(question: String, passages: List<String>): String = (listOf(question) + passages).joinToString("\n\n")
}
