package com.diegonmarcos.superapp.search

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.Advice
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONObject

/**
 * #883 (Jev wave 2, spec section 3 item 10): which search SCOPE chip fits the query being typed.
 *
 * SUGGEST-ONLY: the suggested chip is marked, never switched on; what is selected, what is searched and the order of
 * the results are untouched. Asked a beat after the last letter, off the main thread, at most once per query; every
 * doubt (no engine, no consent (a typed query is the person's, so this use is consent-gated and off by default), a
 * closed evidence gate, unsure, a scope that is not on the page) marks nothing, exactly as before. The person
 * turning ON the suggested chip (or another one) is the outcome.
 */
object SearchJev {

    private const val WAIT_MS = 1500L
    const val MIN_QUERY = 3

    /** The scope id to mark: an offered one only. */
    internal fun valid(pick: String?, scopeIds: Collection<String>): String? = pick?.takeIf { it.isNotEmpty() && it in scopeIds }

    internal fun questions(scopes: List<SearchScope>): JSONObject {
        val crit = JSONObject()
        scopes.forEach { crit.put(it.id, it.label) }
        return JSONObject().put(
            "scope", JSONObject().put("type", "choice").put(
                "instructions",
                "Which search scope is the person most likely looking in with the query `query`? Each option's text is the scope's name.",
            ).put("criteria", crit),
        )
    }

    /** Blocking up to [WAIT_MS]; null on the main thread and for every kind of no opinion. */
    fun suggest(ctx: Context, query: String, scopes: List<SearchScope>): Advice.Suggestion? {
        if (query.trim().length < MIN_QUERY || scopes.size < 2) return null
        val app = ctx.applicationContext
        val got = Advice.within(WAIT_MS) {
            val o = DecisionsLink.outcome(app, "search_scope", JSONObject().put("query", query.trim()), questions(scopes))
            Advice.Suggestion(valid(o.verdictOrNull?.pick("scope", scopes.map { it.id }.toSet()), scopes.map { it.id }).orEmpty(), o.callOrNull)
        } ?: return null
        if (got.pick.isEmpty()) return null
        Advice.remember(app, "search_scope", got, JSONObject().put("scope", got.pick))
        Advice.settleDue(app)
        return got
    }

    /** The person turned [scopeId] on: that is where they wanted to look. */
    fun chose(ctx: Context, suggestion: Advice.Suggestion?, scopeId: String) {
        val s = suggestion ?: return
        Advice.explicit(ctx.applicationContext, "search_scope", s.call, JSONObject().put("scope", scopeId))
    }
}
