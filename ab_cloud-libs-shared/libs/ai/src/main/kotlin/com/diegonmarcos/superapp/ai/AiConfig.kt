package com.diegonmarcos.superapp.ai

import android.content.Context
import org.json.JSONObject

/**
 * The shared contract (assets/ai/ai.json) as the lib reads it: the gateway's paths and mode header,
 * OpenRouter's endpoints and request parameters, the protocol knobs. One file, read by every Kotlin
 * app through this class and by Cloud Code (JS) through tools/resolve-targets.py, so no consumer
 * restates an endpoint, a path or a header name.
 */
data class AiConfig(
    val gatewayService: String,
    val gatewayChatPath: String,
    val gatewayHealthPath: String,
    val gatewayModelsPath: String,
    val gatewayMcpPath: String,
    val modeHeader: String,
    val openRouterId: String,
    val openRouterLabel: String,
    val chatUrl: String,
    val modelsUrl: String,
    val accountProvider: String,
    val defaultModel: String,
    val referer: String,
    val timeoutMs: Int,
    val historyTurns: Int,
    val webPlugin: String,
    val nativeWebParam: String,
    val titleChars: Int,
    val maxToolRounds: Int,
    val stream: Boolean,
) {
    companion object {
        const val ASSET = "ai/ai.json"

        fun parse(text: String): AiConfig {
            val root = JSONObject(text)
            val g = root.getJSONObject("gateway")
            val o = root.getJSONObject("openrouter")
            val p = root.getJSONObject("protocol")
            return AiConfig(
                gatewayService = g.getString("service"),
                gatewayChatPath = g.getString("chat_path"),
                gatewayHealthPath = g.optString("health_path", "/health"),
                gatewayModelsPath = g.optString("models_path", "/v1/models"),
                gatewayMcpPath = g.optString("mcp_path", "/v1/mcp/status"),
                modeHeader = g.optString("mode_header", "X-Agent-Mode"),
                openRouterId = o.getString("id"),
                openRouterLabel = o.getString("label"),
                chatUrl = o.getString("chat_url"),
                modelsUrl = o.getString("models_url"),
                accountProvider = o.getString("account_provider"),
                defaultModel = o.getString("default_model"),
                referer = o.optString("referer"),
                timeoutMs = p.optInt("timeout_ms", 60000),
                historyTurns = p.optInt("history_turns", 20),
                webPlugin = o.optString("web_plugin", "web"),
                nativeWebParam = o.optString("native_web_param", "web_search_options"),
                titleChars = p.optInt("title_chars", 40),
                maxToolRounds = p.optInt("max_tool_rounds", 6),
                stream = p.optBoolean("stream", true),
            )
        }

        /** The contract from the app's merged assets (the lib ships it). */
        fun load(ctx: Context): AiConfig = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
