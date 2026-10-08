package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.SearchConfig
import org.json.JSONArray
import org.json.JSONObject

/** One completion with no tools: a system prompt, one user message, a hard cap on the answer. */
data class LlmRequest(val model: String, val system: String, val user: String, val maxTokens: Int)

data class LlmReply(
    val text: String?, val error: String?, val promptTokens: Int = 0, val completionTokens: Int = 0, val costUsd: Double? = null,
)

interface Llm {
    fun complete(req: LlmRequest): LlmReply
}

/**
 * OpenRouter's chat completions, the way the chat page speaks it, with NO `tools`: a model can only answer in
 * text, it cannot act. The token comes from [token] for each call (the fleet Account's, read per use) and goes
 * only into the Authorization header; it is in no body, no error text and no return value of this class.
 */
class OpenRouterLlm(private val ai: SearchConfig.Ai, private val http: Http, private val token: () -> String?) : Llm {
    override fun complete(req: LlmRequest): LlmReply {
        val t = token() ?: return LlmReply(null, "no ${ai.accountProvider} token in the fleet Account")
        val body = JSONObject().put("model", req.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", req.system))
                .put(JSONObject().put("role", "user").put("content", req.user)))
            .put("max_tokens", req.maxTokens)
            .put("usage", JSONObject().put("include", true))
            .toString()
        val res = runCatching { http.post(ai.chatUrl, Chat.headers(ai, t), body, ai.timeoutMs) }
        val r = res.getOrNull() ?: return LlmReply(null, "request failed: ${res.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}")
        val reply = Chat.reply(r.body)
        val text = reply.text ?: return LlmReply(null, (reply.error ?: "HTTP ${r.code}").replace(t, "***"))
        val usage = runCatching { JSONObject(r.body).optJSONObject("usage") }.getOrNull()
        return LlmReply(
            text = text, error = null,
            promptTokens = usage?.optInt("prompt_tokens") ?: 0,
            completionTokens = usage?.optInt("completion_tokens") ?: 0,
            costUsd = usage?.takeIf { it.has("cost") && !it.isNull("cost") }?.optDouble("cost"),
        )
    }
}
