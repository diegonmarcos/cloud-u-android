package com.diegonmarcos.cloudtoolssmallllms.core

import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenRouter's chat completions as this app speaks them: the worker request (the task's fixed
 * system prompt with its parameters filled in, the user's text as the one user turn) and every
 * reply read into text + model + usage, or an error. The HTTP door is libs:decisions' [Http]; the
 * token is passed per call, added only as the bearer header, and scrubbed from every error text.
 */
object OpenRouterChat {
    data class Reply(val text: String?, val model: String?, val usage: Usage?, val error: String?) {
        val ok: Boolean get() = error == null && !text.isNullOrBlank()
    }

    /** [system] with every {param} replaced by the words of the chosen option. */
    fun render(cfg: ToolsConfig, system: String, params: Map<String, String>): String =
        params.entries.fold(system) { s, (id, opt) ->
            val p = cfg.params[id] ?: return@fold s
            s.replace("{$id}", p.options[opt] ?: p.options.getValue(p.default))
        }

    fun workerBody(cfg: ToolsConfig, task: ToolsConfig.Task, model: String, params: Map<String, String>, text: String): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", render(cfg, task.system, params)))
            .put(JSONObject().put("role", "user").put("content", text)))
        .put("temperature", 0.2)
        .put("max_tokens", task.maxTokens)
        .put("usage", JSONObject().put("include", true))
        .toString()

    /** A chat-completions answer body. Never throws. */
    fun reply(json: String): Reply {
        val o = runCatching { JSONObject(json) }.getOrElse { return Reply(null, null, null, "the answer is not JSON") }
        o.optJSONObject("error")?.let { return Reply(null, o.optString("model").ifBlank { null }, null, it.optString("message", "error")) }
        val usage = Costs.usage(o.optJSONObject("usage"))
        val model = o.optString("model").ifBlank { null }
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return Reply(null, model, usage, "the answer has no choices")
        val text = msg.opt("content") as? String
        if (text.isNullOrBlank()) return Reply(null, model, usage, "the model answered nothing")
        return Reply(text.trim(), model, usage, null)
    }

    /** POST [body] to [url]. Never throws: transport, HTTP and body failures come back as [Reply.error], the token scrubbed. */
    fun post(http: Http, url: String, token: String?, body: String, timeoutMs: Int): Reply {
        if (token.isNullOrBlank()) return Reply(null, null, null, NO_TOKEN)
        val res = runCatching { http.send(url, token, body, timeoutMs) }
            .getOrElse { return Reply(null, null, null, scrub("network: ${it.javaClass.simpleName}${it.message?.let { m -> ": $m" }.orEmpty()}", token)) }
        if (res.code !in 200..299) return Reply(null, null, null, scrub("HTTP ${res.code}: ${Decisions.apiError(res.body)}", token))
        return reply(res.body).let { r -> r.copy(error = r.error?.let { scrub(it, token) }) }
    }

    /** /api/v1/key: whether the token works, and its label / usage / limit. Spends nothing. */
    data class KeyInfo(val ok: Boolean, val label: String?, val usage: Double?, val limit: Double?, val error: String?)

    fun keyInfo(http: Http, url: String, token: String?, timeoutMs: Int): KeyInfo {
        if (token.isNullOrBlank()) return KeyInfo(false, null, null, null, NO_TOKEN)
        val res = runCatching { http.send(url, token, null, timeoutMs) }
            .getOrElse { return KeyInfo(false, null, null, null, scrub("network: ${it.javaClass.simpleName}", token)) }
        if (res.code !in 200..299) return KeyInfo(false, null, null, null, scrub("HTTP ${res.code}: ${Decisions.apiError(res.body)}", token))
        val d = runCatching { JSONObject(res.body).getJSONObject("data") }.getOrNull()
            ?: return KeyInfo(false, null, null, null, "the answer carries no key data")
        fun n(k: String) = d.opt(k)?.takeIf { it is Number }?.let { (it as Number).toDouble() }
        return KeyInfo(true, d.optString("label").ifBlank { null }?.let { scrub(it, token) }, n("usage"), n("limit"), null)
    }

    /** Whether the decision-model catalogue (decision_models_url) lists [model]; null when [json] is unreadable. */
    fun listsModel(json: String?, model: String): Boolean? {
        val a = runCatching { JSONObject(json ?: return null).getJSONArray("data") }.getOrNull() ?: return null
        return (0 until a.length()).any { a.optJSONObject(it)?.optString("id") == model }
    }

    /** The $/1M input price the decision catalogue lists for [model] (decisions bill no output); null when not listed. */
    fun decisionPrice(json: String?, model: String): Double? {
        val a = runCatching { JSONObject(json ?: return null).getJSONArray("data") }.getOrNull() ?: return null
        val m = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("id") == model } ?: return null
        val v = m.optJSONObject("pricing")?.optString("prompt")?.toDoubleOrNull() ?: return null
        return if (v < 0) null else Math.round(v * 1e12) / 1e6
    }

    /** [s] with every occurrence of [token] masked: an error that echoes the request never carries the key. */
    fun scrub(s: String, token: String?): String =
        if (token.isNullOrBlank() || token.length < 4) s else s.replace(token, Decisions.mask(token))

    const val NO_TOKEN = "no OpenRouter token - paste yours in Settings"
}
