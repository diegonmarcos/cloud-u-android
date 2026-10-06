package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject

/**
 * The decisions journal: one JSON line per call, written whether it was answered, served from the cache
 * or refused, and NEVER containing the state, the questions or the token, so nothing a redaction rule
 * missed can reach it. Bounded: every [max] lines written it is cut back to the newest [max].
 */
class Journal(private val sink: LineSink, private val max: Int, private val clock: () -> Long) {

    private var written = 0

    @Synchronized
    fun record(app: String, use: String, outcome: String, extra: JSONObject = JSONObject()) {
        val line = JSONObject(extra.toString()).put("ts", clock()).put("app", app).put("use", use).put("outcome", outcome)
        sink.append(line.toString())
        if (++written >= max) {
            written = 0
            sink.trim(max)
        }
    }

    /** The newest [n] entries as `app use ✓|✗ reason`: names and a mark, nothing else (the debug route). */
    fun summary(n: Int): List<String> = sink.tail(n).mapNotNull { raw ->
        runCatching {
            val o = JSONObject(raw)
            val ok = o.optString("outcome") != "refused"
            "${o.optString("app")} ${o.optString("use")} ${if (ok) "✓" else "✗"}" +
                (o.optString("reason").takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
        }.getOrNull()
    }
}
