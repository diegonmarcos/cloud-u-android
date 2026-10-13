package com.diegonmarcos.cloudtoolssmallllms.core

import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.Http

/**
 * One chat turn, end to end: route the request (Jev on the Decisions API while it is listed, else
 * — or when Jev fails — the chosen chat router), then run the task's worker and price both calls
 * from OpenRouter's usage. Blocking: the app calls it off the main thread. Never throws.
 *
 * [routerChoice] is Settings' router (auto, jev or a chat router id); [jevListed] what the last
 * read of the decision catalogue said (null = not read yet, which tries Jev in auto).
 */
class ToolsFlow(
    private val cfg: ToolsConfig,
    private val http: Http,
    private val token: () -> String?,
    private val prices: PriceBook,
    private val registry: () -> TaskRegistry,
    private val routerChoice: () -> String,
    private val jevListed: () -> Boolean?,
    private val jevPriceIn: () -> Double = { cfg.router.jevPriceIn },
) {
    data class Answer(
        val request: String, val task: String, val worker: String, val text: String, val confidence: Double,
        val workerCost: Double?, val routerModel: String, val routerCost: Double,
    ) {
        /** The answer's whole cost: worker + router; null when the worker's cost is unknown. */
        val total: Double? get() = workerCost?.let { it + routerCost }
    }

    sealed interface Turn {
        data class Answered(val answer: Answer) : Turn
        data class Clarify(val request: String, val decision: RouterDecision.Clarify) : Turn
        data class Failed(val request: String, val reason: String, val routerModel: String, val routerCost: Double) : Turn
    }

    /** Which routers [route] tries, in order. */
    fun routers(): List<String> {
        val choice = routerChoice().takeIf { it in cfg.router.choices } ?: cfg.router.default
        return when (choice) {
            ToolsConfig.AUTO -> (if (jevListed() != false) listOf(ToolsConfig.JEV) else emptyList()) + cfg.router.chatDefault
            ToolsConfig.JEV -> listOf(ToolsConfig.JEV, cfg.router.chatDefault)
            else -> listOf(choice)
        }
    }

    /** Route [request]: each router in [routers] until one answers usably; the costs of every attempt add up. */
    fun route(request: String): RouterDecision {
        var spent = 0.0
        var last: RouterDecision = RouterDecision.Invalid("no router", "", 0.0)
        for (r in routers()) {
            val d = if (r == ToolsConfig.JEV) jev(request) else chat(r, request)
            spent += d.routerCost
            last = when (d) {
                is RouterDecision.Route -> d.copy(routerCost = spent)
                is RouterDecision.Clarify -> d.copy(routerCost = spent)
                is RouterDecision.Invalid -> d.copy(routerCost = spent)
            }
            if (d !is RouterDecision.Invalid) return last
        }
        return last
    }

    private fun jev(request: String): RouterDecision {
        val m = cfg.router.jevModel
        val d = Decisions.post(cfg.openRouter.decisionsUrl, cfg.openRouter.timeoutMs, http, token(), m, Router.jevState(request), Router.jevQuestions(cfg))
        val cost = d.cost ?: d.inputTokens?.let { it * jevPriceIn() / 1_000_000.0 } ?: 0.0
        return Router.fromJev(cfg, registry(), d.answers, OpenRouterChat.scrub(d.error, token()), cost, m)
    }

    private fun chat(model: String, request: String): RouterDecision {
        val r = OpenRouterChat.post(http, cfg.openRouter.chatUrl, token(), Router.chatBody(cfg, model, request), cfg.openRouter.timeoutMs)
        val cost = Costs.actual(r.usage, prices.price(model)) ?: 0.0
        if (r.error != null) return RouterDecision.Invalid(r.error, model, cost)
        return Router.fromChat(cfg, registry(), r.text, cost, model)
    }

    /** A whole turn: [forcedTask] (a clarify chip, the task picker) skips the router. */
    fun send(request: String, forcedTask: String? = null, params: Map<String, String> = emptyMap()): Turn {
        val text = request.trim()
        if (text.isEmpty()) return Turn.Failed(request, "nothing to do", "", 0.0)
        if (token().isNullOrBlank()) return Turn.Failed(request, OpenRouterChat.NO_TOKEN, "", 0.0)
        val d = if (forcedTask != null) Router.forced(cfg, registry(), forcedTask, params) else route(text)
        return when (d) {
            is RouterDecision.Clarify -> Turn.Clarify(text, d)
            is RouterDecision.Invalid -> Turn.Failed(text, d.reason, d.routerModel, d.routerCost)
            is RouterDecision.Route -> work(text, d)
        }
    }

    /** Run [d]'s worker on [text]. */
    fun work(text: String, d: RouterDecision.Route): Turn {
        val body = OpenRouterChat.workerBody(cfg, d.task, d.worker, d.params, text)
        val r = OpenRouterChat.post(http, cfg.openRouter.chatUrl, token(), body, cfg.openRouter.timeoutMs)
        if (!r.ok) return Turn.Failed(text, r.error ?: "the model answered nothing", d.routerModel, d.routerCost + (Costs.actual(r.usage, prices.price(d.worker)) ?: 0.0))
        return Turn.Answered(Answer(text, d.task.id, r.model ?: d.worker, r.text!!, d.confidence, Costs.actual(r.usage, prices.price(d.worker)), d.routerModel, d.routerCost))
    }
}
