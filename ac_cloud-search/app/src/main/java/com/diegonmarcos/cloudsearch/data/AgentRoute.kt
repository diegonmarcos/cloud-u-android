package com.diegonmarcos.cloudsearch.data

import android.content.Context
import com.diegonmarcos.superapp.modelcatalogue.AgentCatalogue
import org.json.JSONObject

/**
 * The chat's Agent field: the fleet gateway's agents from the SHARED descriptor (libs:model-catalogue
 * assets/agents/agents.json, the list Cloud Code reads too), and the request a send to one of them is.
 * A gateway agent is reached on the mesh (my-ai-api, WireGuard-only) with the X-Agent-Mode header; no
 * OpenRouter token leaves the phone for it (the gateway holds the fleet key).
 */
object AgentRoute {
    data class Request(val url: String, val headers: Map<String, String>, val body: String)

    @Volatile private var cached: AgentCatalogue.Catalogue? = null

    fun catalogue(ctx: Context): AgentCatalogue.Catalogue? = cached ?: runCatching {
        AgentCatalogue.parse(ctx.assets.open(AgentCatalogue.ASSET).bufferedReader().use { it.readText() })
    }.getOrNull()?.also { cached = it }

    /**
     * The gateway request for [agentId] with the chat's [openAiBody] (OpenAI shape, `model` = the user's pick):
     * the model rewritten to what the agent runs ([AgentCatalogue.modelFor]; dropped for a fixed-model agent),
     * OpenRouter-only web fields removed. Null when [agentId] is not a declared agent.
     */
    fun request(cat: AgentCatalogue.Catalogue, gatewayUrl: String, agentId: String, openAiBody: String): Request? {
        val a = cat.byId(agentId) ?: return null
        val o = JSONObject(openAiBody)
        val m = AgentCatalogue.modelFor(a, o.optString("model"))
        if (m == null) o.remove("model") else o.put("model", m)
        o.remove("plugins"); o.remove("web_search_options")
        return Request(gatewayUrl.trimEnd('/') + cat.gateway.chatPath, mapOf(cat.gateway.modeHeader to a.mode, "Content-Type" to "application/json"), o.toString())
    }
}
