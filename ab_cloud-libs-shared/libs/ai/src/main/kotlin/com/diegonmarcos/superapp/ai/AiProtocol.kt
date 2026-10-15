package com.diegonmarcos.superapp.ai

import org.json.JSONArray
import org.json.JSONObject

/** What a backend is asked: the conversation, the model pick, the agent, the web switch, the tools. */
class AiRequest(
    /** The user's pick; null = send none (a fixed-model agent). */
    val model: String?,
    val messages: List<AiMsg>,
    val web: Boolean = false,
    val nativeWeb: Boolean = false,
    /** The gateway agent this goes to; null = no agent. */
    val agent: AiAgent? = null,
    val tools: List<AiTool> = emptyList(),
    /** Messages already in wire shape (a tool round-trips); replaces [messages] when set. */
    val wire: JSONArray? = null,
    val stream: Boolean = true,
)

/** One call the model asked for: [arguments] is its JSON text, unparsed. */
data class AiToolCall(val id: String, val name: String, val arguments: String)

/** A model's answer. [transport] = the request itself failed (no answer reached us), as opposed to the model refusing. */
class AiReply(
    val text: String?,
    val citations: List<String> = emptyList(),
    val error: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val transport: Boolean = false,
)

/** The OpenAI-style wire the gateway and OpenRouter share, as pure functions (shared cases: assets/ai/protocol-cases.json). */
object AiProtocol {
    /** A session's title: the first message, one line, cut to [chars]. */
    fun title(first: String, chars: Int): String {
        val t = first.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= chars) t else t.take(chars).trimEnd() + "…"
    }

    /** The last [turns] messages as the wire's `messages`. */
    fun messages(history: List<AiMsg>, turns: Int): JSONArray =
        JSONArray(history.takeLast(turns).map { JSONObject().put("role", it.role).put("content", it.content) })

    /**
     * The request body. [openRouter] adds OpenRouter's own web fields (the gateway takes none); a null
     * [AiRequest.model] sends no `model`. Tools, when there are any, go as OpenAI `tools`.
     */
    fun body(cfg: AiConfig, req: AiRequest, model: String?, stream: Boolean, openRouter: Boolean): String {
        val o = JSONObject()
        if (model != null) o.put("model", model)
        o.put("messages", req.wire ?: messages(req.messages, cfg.historyTurns))
        if (stream) o.put("stream", true)
        if (openRouter && req.web) {
            if (req.nativeWeb) o.put(cfg.nativeWebParam, JSONObject())
            else o.put("plugins", JSONArray().put(JSONObject().put("id", cfg.webPlugin)))
        }
        if (req.tools.isNotEmpty()) {
            o.put("tools", JSONArray(req.tools.map { t ->
                JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", t.name).put("description", t.description).put("parameters", JSONObject(t.schema)))
            }))
            o.put("tool_choice", "auto")
        }
        return o.toString()
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

    private fun urlCitations(notes: JSONArray?): List<String> = if (notes == null) emptyList() else (0 until notes.length()).mapNotNull { i ->
        notes.optJSONObject(i)?.optJSONObject("url_citation")?.str("url")
    }.distinct()

    /** A server-sent-event stream fed chunk by chunk (a chunk may end mid-line). [push] returns the text deltas it completed. */
    class Sse {
        var done = false
            private set
        var error: String? = null
            private set
        val citations = LinkedHashSet<String>()
        private val buf = StringBuilder()

        fun push(chunk: String): List<String> {
            buf.append(chunk)
            val out = ArrayList<String>()
            while (true) {
                val nl = buf.indexOf("\n")
                if (nl < 0) break
                val line = buf.substring(0, nl).trimEnd('\r')
                buf.delete(0, nl + 1)
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") { done = true; continue }
                val j = runCatching { JSONObject(data) }.getOrNull() ?: continue
                if (j.has("error") && !j.isNull("error")) error = j.optJSONObject("error")?.str("message") ?: j.opt("error").toString()
                val choice = j.optJSONArray("choices")?.optJSONObject(0)
                val delta = choice?.optJSONObject("delta")
                val piece = delta?.str("content") ?: choice?.optJSONObject("message")?.str("content")
                if (!piece.isNullOrEmpty()) out.add(piece)
                citations.addAll(urlCitations(delta?.optJSONArray("annotations")))
            }
            return out
        }
    }

    /** A whole (non-streamed) answer: choices[0].message.content (or the gateway's message.content), its citations, its tool calls, or the error. */
    fun parseWhole(status: Int, body: String): AiReply {
        val j = runCatching { JSONObject(body) }.getOrNull()
            ?: return if (status >= 400) AiReply(null, error = "HTTP $status: ${body.take(300)}") else AiReply(body)
        if (status >= 400 || (j.has("error") && !j.isNull("error"))) {
            val msg = j.optJSONObject("error")?.str("message")?.takeIf { it.isNotBlank() }
            return AiReply(null, error = msg ?: "HTTP $status")
        }
        val msg = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: j.optJSONObject("message")
        val calls = msg?.optJSONArray("tool_calls")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { c ->
                val f = c.optJSONObject("function") ?: return@mapNotNull null
                val name = f.str("name") ?: return@mapNotNull null
                AiToolCall(c.optString("id"), name, f.optString("arguments", "{}").ifBlank { "{}" })
            }
        }.orEmpty()
        return AiReply(msg?.str("content") ?: "", urlCitations(msg?.optJSONArray("annotations")), null, calls)
    }

    /** The live model list (`data`): text-output models, the natively web-searching ones and the free ones flagged, by name. */
    fun parseModels(json: String, nativeWebParam: String): List<AiModel> {
        val a = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { m ->
            val out = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            out == null || (0 until out.length()).any { out.optString(it) == "text" }
        }.mapNotNull { m ->
            val id = m.str("id") ?: return@mapNotNull null
            val params = m.optJSONArray("supported_parameters")
            val pricing = m.optJSONObject("pricing")
            AiModel(
                id = id, name = m.str("name") ?: id,
                nativeWeb = params != null && (0 until params.length()).any { params.optString(it) == nativeWebParam },
                free = pricing != null && pricing.optString("prompt") == "0" && pricing.optString("completion") == "0",
            )
        }.sortedBy { it.name.lowercase() }
    }
}
