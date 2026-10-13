package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::tools, parsed and validated: the OpenRouter doors, the router, the typical call the
 * estimates are priced at, the parameters a task may take and the tasks themselves. Every task,
 * prompt, model and URL the app uses is here; nothing is spelled in Kotlin. [parse] throws on a
 * declaration that would route to nothing or render a prompt with a hole in it.
 */
data class ToolsConfig(
    val openRouter: OpenRouter,
    val router: Router,
    val typicalIn: Int,
    val typicalOut: Int,
    val params: Map<String, Param>,
    val tasks: List<Task>,
) {
    data class OpenRouter(
        val chatUrl: String, val decisionsUrl: String, val decisionModelsUrl: String, val keyUrl: String, val timeoutMs: Int,
    )

    data class Router(
        val default: String, val jevModel: String, val jevPriceIn: Double, val chatDefault: String, val chatModels: List<String>,
        val threshold: Double, val candidates: Int, val candidateMinP: Double, val maxTokens: Int,
        val instructions: String, val noneCriterion: String, val clarify: String,
    ) {
        /** What Settings offers: auto, Jev, then each chat router. */
        val choices: List<String> get() = listOf(AUTO, JEV) + chatModels
    }

    /** A task parameter: [options] is option id -> the words a prompt and the screen use, in declared order. */
    data class Param(val id: String, val label: String, val instructions: String, val default: String, val options: Map<String, String>)

    data class Task(
        val id: String, val label: String, val icon: String, val blurb: String, val example: String, val worker: String,
        val params: List<String>, val maxTokens: Int, val criterion: String, val system: String,
    )

    fun task(id: String): Task? = tasks.firstOrNull { it.id == id }

    val taskIds: Set<String> get() = tasks.map { it.id }.toSet()

    /** Every model this app may call through the chat API: the workers and the chat routers. */
    val chatModels: Set<String> get() = tasks.map { it.worker }.toSet() + router.chatModels

    companion object {
        const val AUTO = "auto"
        const val JEV = "jev"
        const val NONE = "none"
        private val ID = Regex("[a-z0-9_]+")
        private val PLACEHOLDER = Regex("\\{([a-z0-9_]+)\\}")

        fun parse(json: String): ToolsConfig {
            val o = JSONObject(json)
            val or = o.getJSONObject("openrouter")
            val open = OpenRouter(
                https(or, "chat_url"), https(or, "decisions_url"), https(or, "decision_models_url"), https(or, "key_url"), or.getInt("timeout_ms"),
            )
            require(open.timeoutMs > 0) { "openrouter.timeout_ms must be > 0" }
            val r = o.getJSONObject("router")
            val router = Router(
                r.getString("default"), r.getString("jev_model"), r.getDouble("jev_price_in"), r.getString("chat_default"),
                strings(r.getJSONArray("chat_models")), r.getDouble("threshold"), r.getInt("candidates"), r.getDouble("candidate_min_p"),
                r.getInt("max_tokens"), r.getString("instructions"), r.getString("none_criterion"), r.getString("clarify"),
            )
            require(router.threshold > 0 && router.threshold <= 1) { "router.threshold ${router.threshold} must be in (0, 1]" }
            require(router.candidateMinP >= 0 && router.candidateMinP < router.threshold) { "router.candidate_min_p must be in [0, threshold)" }
            require(router.candidates >= 1) { "router.candidates must be >= 1" }
            require(router.maxTokens >= 1) { "router.max_tokens must be >= 1" }
            require(router.chatModels.isNotEmpty() && router.chatDefault in router.chatModels) { "router.chat_default must be one of router.chat_models" }
            require(router.default in router.choices) { "router.default '${router.default}' is not auto, jev or a chat router" }
            require('/' in router.jevModel) { "router.jev_model '${router.jevModel}' is not provider/model" }
            val t = o.getJSONObject("typical")
            val typicalIn = t.getInt("in")
            val typicalOut = t.getInt("out")
            require(typicalIn > 0 && typicalOut > 0) { "typical in/out must be > 0" }
            val po = o.getJSONObject("params")
            val params = po.keys().asSequence().filterNot { it.startsWith("_") }.toList().associateWith { id -> param(id, po.getJSONObject(id)) }
            val ta = o.getJSONArray("tasks")
            val tasks = (0 until ta.length()).map { task(ta.getJSONObject(it), params) }
            require(tasks.isNotEmpty()) { "no tasks" }
            val ids = tasks.map { it.id }
            require(ids.size == ids.toSet().size) { "a task id repeats: $ids" }
            return ToolsConfig(open, router, typicalIn, typicalOut, params, tasks)
        }

        private fun param(id: String, p: JSONObject): Param {
            require(ID.matches(id)) { "param id '$id'" }
            val oo = p.getJSONObject("options")
            val options = LinkedHashMap<String, String>()
            oo.keys().forEach { options[it] = oo.getString(it) }
            val def = p.getString("default")
            require(options.isNotEmpty()) { "param $id has no options" }
            require(def in options) { "param $id: default '$def' is not an option" }
            return Param(id, p.getString("label"), p.getString("instructions"), def, options)
        }

        private fun task(t: JSONObject, params: Map<String, Param>): Task {
            val id = t.getString("id")
            require(ID.matches(id) && id != NONE) { "task id '$id'" }
            val task = Task(
                id, t.getString("label"), t.getString("icon"), t.getString("blurb"), t.getString("example"), t.getString("worker").trim(),
                strings(t.getJSONArray("params")), t.getInt("max_tokens"), t.getString("criterion"), t.getString("system"),
            )
            require('/' in task.worker) { "$id: worker '${task.worker}' is not provider/model" }
            require(task.system.isNotBlank()) { "$id: no system prompt" }
            require(task.maxTokens >= 1) { "$id: max_tokens must be >= 1" }
            task.params.forEach { require(it in params) { "$id: unknown param '$it'" } }
            val holes = PLACEHOLDER.findAll(task.system).map { it.groupValues[1] }.toSet()
            require(holes == task.params.toSet()) { "$id: the prompt's placeholders $holes are not its params ${task.params}" }
            return task
        }

        private fun https(o: JSONObject, k: String): String = o.getString(k).also { require(it.startsWith("https://")) { "openrouter.$k is not https" } }

        private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    }
}
