package com.diegonmarcos.superapp.ai

import com.diegonmarcos.superapp.modelcatalogue.AgentCatalogue
import org.json.JSONObject
import java.util.UUID

/**
 * What the AI page needs from the app it runs in. The page knows no network, no store and no token: the
 * host sends (reading its token per call), keeps the sessions, lists the models and opens a URL. [send]
 * and [models] block: the page calls them off the main thread. Most apps use [AiStandardHost].
 */
interface AiHost {
    /** The model and Web switch the user last picked. */
    var model: String
    var web: Boolean
    fun newSession(model: String, now: Long): AiSession
    fun models(): List<AiModel>
    /** One send: [sink] gets the answer's pieces as they stream and the consent question of a mutating tool. */
    fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome
    fun openUrl(url: String) {}
    /** The Agent picker's choices; empty (the default) hides it, as in a host with no agent gateway. */
    fun agents(): List<AiAgent> = emptyList()
    /** The agent the user last picked ("" = none); [send] routes through it. */
    fun agent(): String = ""
    fun pickAgent(id: String) {}
    /** The stored sessions, newest first (the page's Sessions list). */
    fun sessions(): List<AiSession> = emptyList()
    /** Renames / deletes a stored session (the sessions drawer); a host with no store ignores both. */
    fun renameSession(id: String, title: String) {}
    fun deleteSession(id: String) {}
}

/** The small choices a host remembers. */
interface AiPrefs {
    var model: String
    var web: Boolean
    var agent: String
}

/** [AiPrefs] kept in memory (Cloud Browser, Cloud Drive); an app with a store of its own maps [AiPrefs] onto it. */
class AiMemoryPrefs(model: String, web: Boolean = false, agent: String = "") : AiPrefs {
    @Volatile override var model: String = model
    @Volatile override var web: Boolean = web
    @Volatile override var agent: String = agent
}

/** The agents of the shared descriptor (libs:model-catalogue agents.json) as the picker lists them. */
object AiAgents {
    fun from(cat: AgentCatalogue.Catalogue): List<AiAgent> = cat.agents.map { a ->
        AiAgent(a.id, a.label, AgentCatalogue.subtitle(a), a.mode) { picked -> AgentCatalogue.modelFor(a, picked) }
    }

    /** The shared descriptor from the app's merged assets; empty when the app does not carry it. */
    fun load(ctx: android.content.Context): List<AiAgent> = runCatching {
        from(AgentCatalogue.parse(ctx.assets.open(AgentCatalogue.ASSET).bufferedReader().use { it.readText() }))
    }.getOrDefault(emptyList())
}

/**
 * The standard host: sessions in a store, the model and agent in prefs, and pluggable backends. With an agent
 * picked (and a [gateway]) the send goes through the gateway with that agent's mode; otherwise through [direct]
 * (OpenRouter, or an app's own backend). Tools, when given, are offered to the model and run in a bounded loop;
 * a mutating one waits for the consent card.
 */
class AiStandardHost(
    private val cfg: AiConfig,
    private val store: AiSessionStore,
    private val prefs: AiPrefs,
    private val direct: AiBackend? = null,
    private val gateway: AiBackend? = null,
    private val agents: List<AiAgent> = emptyList(),
    /** The model list when the app has a better one than the backend's (cached, offline-aware). */
    private val modelSource: (() -> List<AiModel>)? = null,
    private val tools: List<AiTool> = emptyList(),
    private val opener: (String) -> Unit = {},
    /** Words for the gateway agent's failure ("Hermes (fleet agent gateway, mesh only): ..."). */
    private val gatewayNote: String = "fleet agent gateway, mesh only",
) : AiHost {
    override var model: String
        get() = prefs.model
        set(v) { prefs.model = v }
    override var web: Boolean
        get() = prefs.web
        set(v) { prefs.web = v }

    override fun newSession(model: String, now: Long) = AiSession(UUID.randomUUID().toString(), "", model, now, emptyList())

    override fun models(): List<AiModel> = modelSource?.invoke() ?: (direct ?: gateway)?.models().orEmpty()

    override fun openUrl(url: String) = opener(url)
    override fun agents(): List<AiAgent> = if (gateway == null) emptyList() else agents
    override fun agent(): String = prefs.agent
    override fun pickAgent(id: String) { prefs.agent = id }
    override fun sessions(): List<AiSession> = store.all()
    override fun renameSession(id: String, title: String) = store.rename(id, title)
    override fun deleteSession(id: String) = store.delete(id)

    override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome {
        val asked = session.copy(
            title = session.title.ifBlank { AiProtocol.title(text, cfg.titleChars) },
            model = model, updated = now, messages = session.messages + AiMsg("user", text),
        )
        store.put(asked)
        // With no direct backend the gateway is the only road: an app that picked no agent gets the first one.
        val agent = if (gateway != null) agents.firstOrNull { it.id == prefs.agent } ?: (if (direct == null) agents.firstOrNull() else null) else null
        val backend = if (agent != null) gateway else direct
        if (backend == null) return AiOutcome(asked, "no AI backend is configured", emptyList())
        val via = agent?.let { "${it.label} ($gatewayNote)" }
        val nativeWeb = agent == null && web && runCatching { models().firstOrNull { it.id == model }?.nativeWeb }.getOrNull() == true
        val reply = converse(backend, AiRequest(model, asked.messages, web, nativeWeb, agent, tools), sink)
        val answer = reply.text
        if (answer == null || (answer.isEmpty() && reply.error == null)) {
            val why = reply.error ?: "no choices in the answer"
            return AiOutcome(asked, if (reply.transport) listOfNotNull(via, why).joinToString(": ") else why, emptyList())
        }
        val answered = asked.copy(messages = asked.messages + AiMsg("assistant", answer), updated = System.currentTimeMillis())
        store.put(answered)
        return AiOutcome(answered, null, reply.citations)
    }

    /** One model call, or the bounded tool loop when tools are offered. */
    private fun converse(backend: AiBackend, req: AiRequest, sink: AiSink): AiReply {
        if (tools.isEmpty()) return backend.chat(req, sink::delta)
        val wire = AiProtocol.messages(req.messages, cfg.historyTurns)
        var last = AiReply(null, error = "no answer")
        repeat(cfg.maxToolRounds) {
            last = backend.chat(AiRequest(req.model, req.messages, req.web, req.nativeWeb, req.agent, tools, wire, false), sink::delta)
            if (last.error != null || last.toolCalls.isEmpty()) return last
            wire.put(JSONObject().put("role", "assistant").put("content", last.text ?: "").put("tool_calls", org.json.JSONArray(last.toolCalls.map { c ->
                JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
            })))
            for (c in last.toolCalls) wire.put(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", runTool(c, sink)))
        }
        return AiReply(null, error = "the tool loop did not finish in ${cfg.maxToolRounds} rounds")
    }

    private fun runTool(c: AiToolCall, sink: AiSink): String {
        val tool = tools.firstOrNull { it.name == c.name } ?: return "unknown tool: ${c.name}"
        val args = runCatching { JSONObject(c.arguments) }.getOrDefault(JSONObject())
        if (tool.mutating && !sink.consent(AiConsentRequest(c.id, tool.name, tool.sentence(args)))) return "denied: the user did not allow ${tool.name}"
        return runCatching { tool.run(args) }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
    }
}
