package com.diegonmarcos.cloudtoolssmallllms.core

import com.diegonmarcos.superapp.decisions.Decisions
import org.json.JSONArray
import org.json.JSONObject

/** What the router decided about one request. Every outcome carries what the routing call cost. */
sealed interface RouterDecision {
    val routerModel: String
    val routerCost: Double

    /** Run [task] on [worker] with [params]; [confidence] in [0, 1]. */
    data class Route(
        val task: ToolsConfig.Task, val worker: String, val params: Map<String, String>, val confidence: Double,
        override val routerModel: String, override val routerCost: Double,
    ) : RouterDecision

    /** Unsure (below threshold) or a confident "none": ask [question] and offer [candidates] (task id -> P), most probable first. */
    data class Clarify(
        val question: String, val candidates: List<Pair<String, Double>>,
        override val routerModel: String, override val routerCost: Double,
    ) : RouterDecision

    /** The router's answer could not be used ([reason]); the caller may try the next router. */
    data class Invalid(val reason: String, override val routerModel: String, override val routerCost: Double) : RouterDecision
}

/**
 * The router's two protocols. Jev (OpenRouter's Decisions API) scores options and never writes
 * text: one choice question over the tasks + none, one choice question per parameter, calibrated
 * probabilities back. A chat router (a small B1 Decisions model of the catalogue) answers one JSON
 * object {task, confidence, params}. Both are read into a [RouterDecision] here, and an answer
 * that names a task the app does not have, or no usable probability, is [RouterDecision.Invalid].
 */
object Router {
    const val TASK = "task"
    const val PARAM_PREFIX = "param_"

    // ── Jev ──────────────────────────────────────────────────────────────────────────────────

    /** The Decisions `questions` map: `task` over every task + none, then `param_<id>` per declared parameter. */
    fun jevQuestions(cfg: ToolsConfig): JSONObject {
        val criteria = JSONObject()
        cfg.tasks.forEach { criteria.put(it.id, it.criterion) }
        criteria.put(ToolsConfig.NONE, cfg.router.noneCriterion)
        val q = JSONObject().put(TASK, JSONObject().put("type", Decisions.CHOICE).put("instructions", cfg.router.instructions).put("criteria", criteria))
        for (p in cfg.params.values) {
            val c = JSONObject()
            p.options.forEach { (k, v) -> c.put(k, v) }
            q.put(PARAM_PREFIX + p.id, JSONObject().put("type", Decisions.CHOICE).put("instructions", p.instructions).put("criteria", c))
        }
        return q
    }

    fun jevState(request: String): JSONObject = JSONObject().put("request", request)

    /** A Decisions [answers] object (null = the call failed with [error]) read into a decision. */
    fun fromJev(cfg: ToolsConfig, registry: TaskRegistry, answers: JSONObject?, error: String, cost: Double, model: String): RouterDecision {
        if (answers == null) return RouterDecision.Invalid(error.ifBlank { "no answer" }, model, cost)
        val a = answers.optJSONObject(TASK)
        val allowed = cfg.taskIds + ToolsConfig.NONE
        val pick = Decisions.pick(a, allowed) ?: return RouterDecision.Invalid("the answer names no known task", model, cost)
        val options = Decisions.options(a).map { it.key to it.p }
        val params = { task: ToolsConfig.Task ->
            task.params.associateWith { id ->
                val p = cfg.params.getValue(id)
                Decisions.pick(answers.optJSONObject(PARAM_PREFIX + id), p.options.keys)?.key ?: p.default
            }
        }
        return decide(cfg, registry, pick.key, pick.p, options, params, model, cost)
    }

    // ── chat router ──────────────────────────────────────────────────────────────────────────

    /** The system prompt: every task id with its criterion, every parameter with its options, and the JSON contract. */
    fun chatSystem(cfg: ToolsConfig): String = buildString {
        append(cfg.router.instructions).append("\nTasks (id: when):\n")
        cfg.tasks.forEach { append("- ").append(it.id).append(": ").append(it.criterion).append('\n') }
        append("- ").append(ToolsConfig.NONE).append(": ").append(cfg.router.noneCriterion).append('\n')
        append("Parameters (only for a task that takes them):\n")
        cfg.tasks.filter { it.params.isNotEmpty() }.forEach { t ->
            t.params.forEach { id -> append("- ").append(t.id).append('.').append(id).append(": one of ").append(cfg.params.getValue(id).options.keys.joinToString("|")).append('\n') }
        }
        append("Reply with ONE JSON object and nothing else: {\"task\": \"<id or none>\", \"confidence\": <0..1>, \"params\": {\"<param>\": \"<option>\"}}")
    }

    /** One chat-completions request to router [model]: JSON mode, deterministic, short. */
    fun chatBody(cfg: ToolsConfig, model: String, request: String): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", chatSystem(cfg)))
            .put(JSONObject().put("role", "user").put("content", request)))
        .put("response_format", JSONObject().put("type", "json_object"))
        .put("temperature", 0)
        .put("max_tokens", cfg.router.maxTokens)
        .put("usage", JSONObject().put("include", true))
        .toString()

    /** A chat router's reply [content] read into a decision. */
    fun fromChat(cfg: ToolsConfig, registry: TaskRegistry, content: String?, cost: Double, model: String): RouterDecision {
        val o = json(content) ?: return RouterDecision.Invalid("the router did not answer JSON", model, cost)
        val task = (o.opt(TASK) as? String)?.trim()?.lowercase() ?: return RouterDecision.Invalid("the router named no task", model, cost)
        if (task != ToolsConfig.NONE && task !in cfg.taskIds) return RouterDecision.Invalid("the router named an unknown task '$task'", model, cost)
        val c = o.opt("confidence")
        if (c !is Number || c.toDouble().isNaN() || c.toDouble() !in 0.0..1.0) {
            return RouterDecision.Invalid("the router's confidence is not a number in [0, 1]", model, cost)
        }
        val p = o.optJSONObject("params")
        val params = { t: ToolsConfig.Task ->
            t.params.associateWith { id ->
                val decl = cfg.params.getValue(id)
                (p?.opt(id) as? String)?.trim()?.lowercase()?.takeIf { it in decl.options } ?: decl.default
            }
        }
        // A chat router names one task; the others get nothing, so it can only offer that one back.
        return decide(cfg, registry, task, c.toDouble(), listOf(task to c.toDouble()), params, model, cost)
    }

    /** The first JSON object in [s]: a bare object, or one inside a ```json fence or prose. */
    fun json(s: String?): JSONObject? {
        if (s.isNullOrBlank()) return null
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull()
    }

    // ── shared ───────────────────────────────────────────────────────────────────────────────

    /** The user picked [taskId] (a clarify chip or the task picker): routed with certainty, the parameters at their defaults. */
    fun forced(cfg: ToolsConfig, registry: TaskRegistry, taskId: String, params: Map<String, String> = emptyMap()): RouterDecision {
        val t = cfg.task(taskId) ?: return RouterDecision.Invalid("no task '$taskId'", "", 0.0)
        val ps = t.params.associateWith { id -> params[id]?.takeIf { it in cfg.params.getValue(id).options } ?: cfg.params.getValue(id).default }
        return RouterDecision.Route(t, registry.worker(t), ps, 1.0, "", 0.0)
    }

    private fun decide(
        cfg: ToolsConfig, registry: TaskRegistry, pick: String, p: Double, options: List<Pair<String, Double>>,
        params: (ToolsConfig.Task) -> Map<String, String>, model: String, cost: Double,
    ): RouterDecision {
        val r = cfg.router
        if (pick != ToolsConfig.NONE && p >= r.threshold) {
            val t = cfg.task(pick)!!
            return RouterDecision.Route(t, registry.worker(t), params(t), p, model, cost)
        }
        val cands = options.filter { it.first != ToolsConfig.NONE && it.second >= r.candidateMinP }
            .sortedByDescending { it.second }.take(r.candidates)
        return RouterDecision.Clarify(r.clarify, cands, model, cost)
    }
}
