package com.diegonmarcos.superapp.cgc

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The HTTP the connector needs: one POST. Tests pass a fake; the app passes [UrlConnectionHttp]. */
fun interface HttpPort {
    fun post(url: String, headers: Map<String, String>, body: String): HttpReply
}

data class HttpReply(val code: Int, val body: String)

/** Where the key comes from (the app: its encrypted key box). The connector reads it for the request and nothing else. */
fun interface KeySource {
    fun read(slot: String): String?
}

sealed class AiResult {
    /** [config] is the editor's new configuration; [applied] the category to option picks taken; [ignored] what was dropped. */
    data class Applied(val config: Map<String, String>, val applied: Map<String, String>, val ignored: List<String>) : AiResult()
    object NoKey : AiResult()
    object NoEndpoint : AiResult()
    data class Http(val code: Int, val message: String) : AiResult()
    data class BadReply(val reason: String) : AiResult()
    data class Network(val reason: String) : AiResult()
}

/**
 * "Describe your avatar": free text to an OpenRouter chat model (declared in the spec's "ai" block: endpoint, model, key slot), whose
 * JSON answer is mapped onto DECLARED options only. The key is read from [keys] per request, sent only in the Authorization header
 * of that request, and never logged, returned or put in an error. Pure apart from [http], so the suite mocks the network.
 */
class OpenRouterAvatar(private val keys: KeySource, private val http: HttpPort) {

    fun describe(spec: CgcSpec, current: Map<String, String>, text: String, engine: AvatarEngine? = null): AiResult {
        val ai = spec.ai ?: return AiResult.NoEndpoint
        if (ai.endpoint.isBlank() || ai.model.isBlank()) return AiResult.NoEndpoint
        val key = keys.read(ai.keySlot)?.trim()
        if (key.isNullOrEmpty()) return AiResult.NoKey
        val headers = linkedMapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json")
        if (ai.referer.isNotEmpty()) headers["HTTP-Referer"] = ai.referer
        if (ai.title.isNotEmpty()) headers["X-Title"] = ai.title
        val reply = try {
            http.post(ai.endpoint, headers, request(spec, text, engine).toString())
        } catch (e: Exception) {
            return AiResult.Network(e.javaClass.simpleName)
        }
        if (reply.code !in 200..299) return AiResult.Http(reply.code, errorMessage(reply.body))
        val content = runCatching {
            JSONObject(reply.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }.getOrNull() ?: return AiResult.BadReply("no message content")
        return map(spec, current, content, engine)
    }

    companion object {
        /** The chat request: a system prompt listing every wired category and its option ids, the user's text, JSON output. */
        fun request(spec: CgcSpec, text: String, engine: AvatarEngine? = null): JSONObject {
            val model = spec.ai?.model.orEmpty()
            val sb = StringBuilder()
            sb.append("You configure a 3D avatar editor. Reply with ONE JSON object and nothing else. ")
            sb.append("Keys are category ids, each value is ONE option id from that category's list. ")
            sb.append("Leave out categories the description does not mention. ")
            sb.append("For colour categories you may answer a #rrggbb colour instead; the nearest offered colour is used.\n")
            for (c in spec.categories) {
                if (CgcConfig.soon(c, engine)) continue
                sb.append("- ").append(c.id).append(" (").append(c.label).append("): ")
                sb.append(c.options.joinToString(", ") { o -> o.id + (if (o.color != null) " " + o.color else "") + " = " + o.label })
                sb.append('\n')
            }
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", sb.toString()))
                .put(JSONObject().put("role", "user").put("content", text.take(2000)))
            return JSONObject().put("model", model).put("messages", messages)
                .put("response_format", JSONObject().put("type", "json_object")).put("temperature", 0.2)
        }

        /**
         * Maps a model's answer onto [current]. [content] may wrap the object in prose or a code fence, or nest it under "config"
         * or "options". A key matches a category by id or label (case, spaces, dashes and underscores ignored); a value matches an
         * option by id or label, or, in a color/tint category, a #rgb/#rrggbb colour picks the nearest declared colour. Categories
         * not wired yet and values that match nothing are ignored and reported.
         */
        fun map(spec: CgcSpec, current: Map<String, String>, content: String, engine: AvatarEngine? = null): AiResult {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return AiResult.BadReply("no JSON object")
            var o = runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull() ?: return AiResult.BadReply("invalid JSON")
            for (wrap in listOf("config", "options")) o.optJSONObject(wrap)?.let { o = it }
            val cfg = current.toMutableMap()
            val applied = linkedMapOf<String, String>()
            val ignored = mutableListOf<String>()
            for (key in o.keys().asSequence().toList().sorted()) {
                val c = spec.categories.firstOrNull { norm(it.id) == norm(key) || norm(it.label) == norm(key) }
                val raw = o.opt(key)?.toString().orEmpty()
                if (c == null || CgcConfig.soon(c, engine)) { ignored += key; continue }
                val opt = c.options.firstOrNull { norm(it.id) == norm(raw) } ?: c.options.firstOrNull { norm(it.label) == norm(raw) }
                    ?: if (c.kind == CgcSpec.KIND_COLOR || c.kind == CgcSpec.KIND_TINT) nearest(c, raw) else null
                if (opt == null) { ignored += key; continue }
                cfg[c.id] = opt.id
                applied[c.id] = opt.id
            }
            return AiResult.Applied(cfg, applied, ignored)
        }

        private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() || it == '#' }

        /** The declared colour option closest (RGB distance) to [raw] when it is a hex colour, else null. */
        fun nearest(c: CgcCategory, raw: String): CgcOption? {
            val target = rgb(raw) ?: return null
            return c.options.filter { it.color != null }.minByOrNull { o ->
                val p = rgb(o.color!!)!!
                (0..2).sumOf { (p[it] - target[it]) * (p[it] - target[it]) }
            }
        }

        fun rgb(s: String): IntArray? {
            val h = s.trim().removePrefix("#")
            val full = when {
                h.length == 3 && h.all { it.isHexDigit() } -> h.map { "$it$it" }.joinToString("")
                h.length == 6 && h.all { it.isHexDigit() } -> h
                else -> return null
            }
            return IntArray(3) { full.substring(it * 2, it * 2 + 2).toInt(16) }
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        /** OpenRouter's {"error": {"message"}} or a short status line; the body is never echoed whole. */
        fun errorMessage(body: String): String =
            runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()?.take(200) ?: "request failed"
    }
}

/** The real POST (HttpURLConnection, 30 s timeouts). Call it off the main thread. */
class UrlConnectionHttp : HttpPort {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpReply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.doOutput = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpReply(code, text)
        } finally {
            c.disconnect()
        }
    }
}
