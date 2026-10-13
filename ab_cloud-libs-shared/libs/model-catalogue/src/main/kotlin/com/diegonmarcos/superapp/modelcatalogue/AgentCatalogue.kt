package com.diegonmarcos.superapp.modelcatalogue

import org.json.JSONObject

/**
 * The fleet gateway's agents as the shared descriptor (assets/agents/agents.json) declares them: the
 * one list Cloud Search's chat Agent field and Cloud Code's picker read. Logic only (no UI import),
 * so Cloud Code compiles it by reference with the rest of this package.
 */
object AgentCatalogue {
    const val ASSET = "agents/agents.json"

    data class Capabilities(val tools: Boolean, val mcp: Boolean, val images: Boolean, val reasoning: Boolean, val web: Boolean)

    data class Agent(
        val id: String, val label: String, val mode: String, val fleet: String?, val backend: String,
        /** "fixed" (the gateway picks) or "catalogue" (the user's pick, narrowed to [providers]/[prefix]). */
        val modelKind: String, val providers: List<String>, val prefix: String?, val defaultModel: String?,
        val caps: Capabilities,
    )

    data class Gateway(val service: String, val chatPath: String, val healthPath: String, val modeHeader: String, val meshOnly: Boolean)

    data class Catalogue(val gateway: Gateway, val agents: List<Agent>) {
        fun byId(id: String): Agent? = agents.firstOrNull { it.id == id }
    }

    fun parse(text: String): Catalogue {
        val root = JSONObject(text)
        val g = root.getJSONObject("gateway")
        val gw = Gateway(g.getString("service"), g.getString("chat_path"), g.optString("health_path", "/health"),
            g.optString("mode_header", "X-Agent-Mode"), g.optBoolean("mesh_only", true))
        val arr = root.getJSONArray("agents")
        val agents = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val m = a.getJSONObject("model")
            val c = a.getJSONObject("capabilities")
            val prov = m.optJSONArray("providers")
            Agent(
                id = a.getString("id"), label = a.getString("label"), mode = a.getString("mode"),
                fleet = if (a.isNull("fleet")) null else a.optString("fleet").ifBlank { null },
                backend = a.optString("backend"),
                modelKind = m.getString("kind"),
                providers = prov?.let { p -> (0 until p.length()).map { p.getString(it) } }.orEmpty(),
                prefix = m.optString("prefix").ifBlank { null },
                defaultModel = m.optString("default").ifBlank { null },
                caps = Capabilities(c.optBoolean("tools"), c.optBoolean("mcp"), c.optBoolean("images"), c.optBoolean("reasoning"), c.optBoolean("web")),
            )
        }
        require(agents.map { it.id }.toSet().size == agents.size) { "duplicate agent id in $ASSET" }
        return Catalogue(gw, agents)
    }

    /**
     * The model a request to [agent] carries for the user's [picked] catalogue model: null for a fixed-model
     * agent (the gateway runs its own); the pick when it fits the agent's [Agent.prefix]; else its default.
     */
    fun modelFor(agent: Agent, picked: String): String? = when {
        agent.modelKind == "fixed" -> null
        agent.prefix == null -> picked
        picked.startsWith(agent.prefix) -> picked
        else -> agent.defaultModel
    }

    /** The picker's one-line subtitle: what the agent can do, truthfully from its capabilities. */
    fun subtitle(agent: Agent): String = buildList {
        if (agent.caps.tools) add(if (agent.caps.mcp) "tools (MCP)" else "tools")
        if (agent.caps.reasoning) add("reasoning")
        add(if (agent.caps.images) "images" else "text only")
        if (agent.caps.web) add("web")
    }.joinToString(" · ")
}
