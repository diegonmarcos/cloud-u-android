package com.diegonmarcos.cloudagenda.agent

import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiHttp
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiUrlHttp
import org.json.JSONArray
import org.json.JSONObject

/** Whether anything is there to do the extraction. */
sealed interface BackendStatus {
    data object Ready : BackendStatus
    /** Nothing answers (or not the agent asked for). Shown to the user as "Agent backend not deployed". */
    data class NotDeployed(val reason: String) : BackendStatus
}

class AgentReply(val text: String?, val error: String?)

/** The extraction backend: ask a model one question. Blocking; the worker calls it off the main thread. */
interface AgentBackend {
    /** [agent] is the gateway agent the config picked (blank = the gateway's default). */
    fun status(agent: String = ""): BackendStatus
    fun extract(prompt: String, agent: String, model: String): AgentReply
}

/**
 * The fleet AI gateway (my-ai-api, WireGuard-only) through libs:ai's gateway backend: the same road the AI panel
 * takes, with the agent chosen by the X-Agent-Mode header and no token on the phone. [status] asks /health first, so
 * a phone off the mesh, or a gateway without the chosen agent live, is reported as "not deployed" instead of
 * producing nothing. Nothing here fabricates an answer.
 */
class GatewayAgentBackend(
    private val cfg: AiConfig,
    private val gatewayUrl: String,
    private val agents: List<AiAgent>,
    private val http: AiHttp = AiUrlHttp,
) : AgentBackend {

    override fun status(agent: String): BackendStatus {
        val r = try {
            http.get(gatewayUrl.trimEnd('/') + cfg.gatewayHealthPath, emptyMap(), HEALTH_TIMEOUT_MS)
        } catch (e: Exception) {
            return BackendStatus.NotDeployed("the fleet AI gateway is not reachable (mesh only): ${e.message ?: e.javaClass.simpleName}")
        }
        if (r.code !in 200..299) return BackendStatus.NotDeployed("the fleet AI gateway answered HTTP ${r.code} on ${cfg.gatewayHealthPath}")
        val picked = agents.firstOrNull { it.id == agent }
        if (picked != null && !modeLive(r.body, picked.mode)) {
            return BackendStatus.NotDeployed("the agent ${picked.label} (${picked.mode}) is not live at the fleet AI gateway")
        }
        return BackendStatus.Ready
    }

    /** Whether the gateway's /health lists [mode] among its live modes; true when the answer does not say. */
    fun modeLive(healthBody: String, mode: String): Boolean = liveModes(healthBody)?.let { mode in it } ?: true

    override fun extract(prompt: String, agent: String, model: String): AgentReply {
        val picked = agents.firstOrNull { it.id == agent }
        val req = AiRequest(model.ifBlank { null }, listOf(AiMsg("user", prompt)), agent = picked, stream = false)
        val reply = AiGatewayBackend(cfg, gatewayUrl, http).chat(req) {}
        return AgentReply(reply.text, reply.error)
    }

    companion object {
        const val HEALTH_TIMEOUT_MS = 5000

        /** The mode ids of a /health body, in whichever of the usual shapes it has; null when none is recognisable. */
        fun liveModes(body: String): Set<String>? {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val modes = root.opt("modes") ?: return null
            return when (modes) {
                is JSONArray -> (0 until modes.length()).mapNotNull { i ->
                    when (val e = modes.opt(i)) {
                        is String -> e
                        is JSONObject -> e.optString("id").ifBlank { null }
                        else -> null
                    }
                }.toSet()
                is JSONObject -> modes.keys().asSequence().toSet()
                else -> null
            }
        }
    }
}
