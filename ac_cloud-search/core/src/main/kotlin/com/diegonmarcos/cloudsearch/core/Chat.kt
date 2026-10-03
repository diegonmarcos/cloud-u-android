package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * The AI chat's protocol with OpenRouter (OpenAI-compatible chat completions) and its sessions.
 * Everything that is a URL, a header or a parameter name comes from build.json::search.ai; the
 * token is passed in per call and is never part of anything this object returns or stores.
 */
object Chat {
    data class Msg(val role: String, val content: String)
    data class Model(val id: String, val name: String, val nativeWeb: Boolean, val free: Boolean, val context: Int)
    data class Reply(val text: String?, val citations: List<String>, val model: String?, val error: String?,
                     val toolCalls: List<ToolCall> = emptyList())

    /** #802 I9 a tool the model may call (OpenAI-compatible `tools`); [parameters] is a JSON-Schema object. Names are data. */
    data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

    /** One call the model asked for: [arguments] is its JSON text, unparsed. */
    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class Session(val id: String, val title: String, val model: String, val updated: Long, val messages: List<Msg>) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("model", model).put("updated", updated)
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))
    }

    enum class Bucket { TODAY, PREVIOUS_7_DAYS, OLDER }

    /** The request body: the last [SearchConfig.Ai.historyTurns] messages; web search natively when the model has it, else by the web plugin. */
    fun body(ai: SearchConfig.Ai, model: String, history: List<Msg>, web: Boolean, nativeWeb: Boolean): String {
        val o = JSONObject().put("model", model)
            .put("messages", JSONArray(history.takeLast(ai.historyTurns).map { JSONObject().put("role", it.role).put("content", it.content) }))
        if (web) {
            if (nativeWeb) o.put(ai.nativeWebParam, JSONObject())
            else o.put("plugins", JSONArray().put(JSONObject().put("id", ai.webPlugin)))
        }
        return o.toString()
    }

    /**
     * #802 I9 a tool-using request: [messages] are already in wire shape (an assistant turn may carry
     * `tool_calls`, a `tool` turn its `tool_call_id`), so a multi-step turn round-trips unchanged.
     * No tools → no `tools`/`tool_choice` keys at all.
     */
    fun toolsBody(model: String, messages: JSONArray, tools: List<ToolSpec>): String {
        val o = JSONObject().put("model", model).put("messages", messages)
        if (tools.isNotEmpty()) {
            o.put("tools", JSONArray(tools.map { t ->
                JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))
            }))
            o.put("tool_choice", "auto")
        }
        return o.toString()
    }

    fun headers(ai: SearchConfig.Ai, token: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $token", "HTTP-Referer" to ai.referer, "X-Title" to ai.title)

    /** choices[0].message.content plus any url_citation annotations; an `error` object becomes [Reply.error]. */
    fun reply(json: String): Reply {
        val o = runCatching { JSONObject(json) }.getOrElse { return Reply(null, emptyList(), null, "unreadable answer") }
        o.optJSONObject("error")?.let { return Reply(null, emptyList(), null, it.optString("message", "error")) }
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return Reply(null, emptyList(), o.optStr("model"), "no choices in the answer")
        val notes = msg.optJSONArray("annotations")
        val cites = if (notes == null) emptyList() else (0 until notes.length()).mapNotNull { i ->
            notes.optJSONObject(i)?.optJSONObject("url_citation")?.optStr("url")
        }.distinct()
        val calls = msg.optJSONArray("tool_calls")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { c ->
                val f = c.optJSONObject("function") ?: return@mapNotNull null
                val name = f.optStr("name") ?: return@mapNotNull null
                ToolCall(c.optString("id"), name, f.optString("arguments", "{}").ifBlank { "{}" })
            }
        }.orEmpty()
        return Reply(msg.optStr("content"), cites, o.optStr("model"), null, calls)
    }

    /** The live catalogue: text-output models, the natively web-searching ones flagged, free ones flagged. */
    fun models(json: String, nativeWebParam: String): List<Model> {
        val a = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { m ->
            val out = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            out == null || (0 until out.length()).any { out.optString(it) == "text" }
        }.map { m ->
            val params = m.optJSONArray("supported_parameters")
            val pricing = m.optJSONObject("pricing")
            Model(
                id = m.getString("id"), name = m.optString("name", m.getString("id")),
                nativeWeb = params != null && (0 until params.length()).any { params.optString(it) == nativeWebParam },
                free = pricing != null && pricing.optString("prompt") == "0" && pricing.optString("completion") == "0",
                context = m.optInt("context_length"),
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun title(firstUserMessage: String, chars: Int): String {
        val t = firstUserMessage.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= chars) t else t.take(chars).trimEnd() + "…"
    }

    fun bucket(updated: Long, now: Long, zone: ZoneId): Bucket {
        val day = Instant.ofEpochMilli(updated).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            !day.isBefore(today) -> Bucket.TODAY
            !day.isBefore(today.minusDays(7)) -> Bucket.PREVIOUS_7_DAYS
            else -> Bucket.OLDER
        }
    }

    /** Sessions grouped Today / Previous 7 Days / Older, each newest first; empty groups dropped. */
    fun group(sessions: List<Session>, now: Long, zone: ZoneId): List<Pair<Bucket, List<Session>>> =
        sessions.sortedByDescending { it.updated }.groupBy { bucket(it.updated, now, zone) }
            .toSortedMap().map { (k, v) -> k to v }

    fun sessionFromJson(o: JSONObject): Session = Session(
        id = o.getString("id"), title = o.optString("title"), model = o.optString("model"), updated = o.optLong("updated"),
        messages = o.optJSONArray("messages")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Msg(it.getString("role"), it.getString("content")) }
        } ?: emptyList(),
    )
}
