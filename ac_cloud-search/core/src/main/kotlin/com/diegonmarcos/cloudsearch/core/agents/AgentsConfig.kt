package com.diegonmarcos.cloudsearch.core.agents

import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::search.agents, read once: the two engine contracts, the defaults for a run, the owner's profile
 * fields, the filters an agent may ask for, the sides and categories the Agents page groups by, the agents and the
 * template each starts from. No host, path, column, model or word of a template is spelled in Kotlin.
 *
 * Every agent runs on the SAME engine ([DraftRunner]); only its definition differs: which alert mails it reads
 * (its [Source]s), which listing links count, what it asks for ([Agent.filters]), what it aims at ([Agent.goal])
 * and which template its drafts start from. DRAFT ONLY: a definition has no field that could make an agent send,
 * submit, apply or buy, and the parser refuses one that tries ([FORBIDDEN_KEYS], [DRAFT_ONLY], [OUTPUTS]).
 */
data class AgentsConfig(
    val mail: MailEngine,
    val browser: BrowserEngine,
    val defaults: Defaults,
    val profileFields: List<ProfileField>,
    val agents: List<Agent>,
    val templates: List<TemplateSeed>,
    val filters: List<Filter> = emptyList(),
    val sides: List<Side> = emptyList(),
    val categories: List<Category> = emptyList(),
) {
    data class MailEngine(
        val fleet: String, val authoritySuffix: String, val permission: String, val pathMessages: String, val pathBody: String,
        val paramFrom: String, val paramSubject: String, val paramSince: String, val paramLimit: String, val paramAccount: String,
        val paramId: String, val messageColumns: List<String>, val bodyColumns: List<String>, val maxLimit: Int,
    )

    data class BrowserEngine(
        val fleet: String, val authoritySuffix: String, val permission: String, val openAction: String, val fetchMethod: String,
        val extraUrl: String, val extraGroup: String, val extraMaxChars: String,
    )

    data class Defaults(
        val model: String, val budgetRunUsd: Double, val budgetDayUsd: Double, val maxListings: Int, val maxMails: Int,
        val lookbackDays: Int, val pageChars: Int, val personalMaxWords: Int, val maxTokens: Int, val unknownPricePerToken: Double,
    )

    data class ProfileField(val id: String, val label: String, val hint: String)

    /** Something an agent asks the owner for (location, radius, price range, size, dates, keywords). */
    data class Filter(val id: String, val label: String, val hint: String, val number: Boolean)

    /** Buy-Side or Sell-Side. */
    data class Side(val id: String, val label: String)

    /** A group inside a side (Real Estate, Things, Services). */
    data class Category(val id: String, val side: String, val label: String)

    /**
     * One place an agent reads: the alert mails [mailFrom] sends (a blank [mailFrom] = no mails, the source is only a
     * search the person opens), the listing links in them ([links]), and [search], the search.sources link the
     * person opens in Cloud Browser to look (or to set up the alert) themselves.
     */
    data class Source(val id: String, val label: String, val search: String, val mailFrom: String, val mailSubject: String, val links: LinkRules)

    data class Agent(
        val id: String, val label: String, val blurb: String, val sources: List<Source>,
        val group: String, val template: String, val usesLlm: Boolean, val reportKind: String,
        val side: String = "", val category: String = "", val goal: String = "", val filters: List<String> = emptyList(),
        val outputs: List<String> = OUTPUTS, val legacyIds: List<String> = emptyList(), val query: String = "",
    ) {
        /** A run or report recorded under [agentId] is this agent's: its id now, or the id it had before (migration). */
        fun owns(agentId: String): Boolean = agentId == id || agentId in legacyIds
    }

    data class TemplateSeed(val id: String, val label: String, val agent: String, val body: String)

    /** One header of the Agents page and the agents under it, in declared order. */
    data class Group(val side: Side, val category: Category?, val agents: List<Agent>) {
        val key: String get() = side.id + "/" + (category?.id ?: "")
    }

    /** The agent [id] names: its id, or one of its earlier ids (a run or report saved before a rename). */
    fun agent(id: String): Agent? = agents.firstOrNull { it.id == id } ?: agents.firstOrNull { id in it.legacyIds }
    fun template(id: String): TemplateSeed? = templates.firstOrNull { it.id == id }
    fun filter(id: String): Filter? = filters.firstOrNull { it.id == id }

    /** Variables the run itself supplies, beside the owner's profile fields and the agent's filters. */
    val builtinVars: List<String> get() = BUILTIN_VARS

    /**
     * The Agents page, top to bottom: each side in declared order, its categories in declared order, then the side's
     * agents that belong to no category (Sell-Side has none). A group with no agent is not shown.
     */
    fun groups(): List<Group> = sides.flatMap { s ->
        val mine = agents.filter { it.side == s.id }
        categories.filter { it.side == s.id }.map { c -> Group(s, c, mine.filter { it.category == c.id }) } +
            listOf(Group(s, null, mine.filter { it.category.isEmpty() }))
    }.filter { it.agents.isNotEmpty() }

    /**
     * The owner's saved preferences after a rename: what an agent remembered under an earlier id (the listings it
     * already drafted, its filters, its source switches) moves to its id now. Everything else, the templates (keyed
     * by template id), the profile, the model and the budgets, is kept as it is. Applying it twice changes nothing.
     */
    fun migratePrefs(prefs: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(prefs)
        for (a in agents) for (old in a.legacyIds) {
            for ((k, v) in prefs) {
                val prefix = PER_AGENT_PREFIXES.firstOrNull { p -> k == p + old || k.startsWith(p + old + "_") } ?: continue
                val nk = prefix + a.id + k.removePrefix(prefix + old)
                out.remove(k)
                out[nk] = if (prefix == SEEN_PREFIX && out[nk] is String && v is String) mergeIds(out[nk] as String, v) else (out[nk] ?: v)
            }
        }
        return out
    }

    fun problems(): List<String> {
        val bad = mutableListOf<String>()
        if (mail.permission != browser.permission) bad += "the two engines name different permissions"
        if (mail.maxLimit < 1) bad += "mail max_limit must be at least 1"
        if (mail.messageColumns.isEmpty() || mail.bodyColumns.isEmpty()) bad += "mail columns are not declared"
        val d = defaults
        if (d.model.isBlank()) bad += "defaults.model is empty"
        if (d.budgetRunUsd < 0 || d.budgetDayUsd < 0) bad += "a budget cap is negative"
        if (d.budgetRunUsd > d.budgetDayUsd) bad += "the per-run cap is above the per-day cap"
        if (d.maxListings < 1 || d.maxMails < 1 || d.lookbackDays < 1 || d.pageChars < 500 || d.maxTokens < 16 || d.personalMaxWords < 5)
            bad += "a run default is out of range"
        if (d.unknownPricePerToken <= 0) bad += "unknown_price_per_token must be positive"
        val allIds = agents.map { it.id } + agents.flatMap { it.legacyIds }
        if (allIds.toSet().size != allIds.size) bad += "duplicate agent ids"
        if (templates.map { it.id }.toSet().size != templates.size) bad += "duplicate template ids"
        if (profileFields.map { it.id }.toSet().size != profileFields.size) bad += "duplicate profile field ids"
        if (filters.map { it.id }.toSet().size != filters.size) bad += "duplicate filter ids"
        if (sides.map { it.id }.toSet().size != sides.size) bad += "duplicate side ids"
        if (categories.map { it.id }.toSet().size != categories.size) bad += "duplicate category ids"
        categories.filter { c -> sides.none { it.id == c.side } }.forEach { bad += "category ${it.id} is on side ${it.side}, which is not declared" }
        val known = profileFields.map { it.id }.toSet() + filters.map { it.id } + BUILTIN_VARS
        for (a in agents) {
            if (a.sources.isEmpty()) bad += "agent ${a.id} reads no source"
            if (a.sources.map { it.id }.toSet().size != a.sources.size) bad += "agent ${a.id} names a source twice"
            if (a.sources.none { it.mailFrom.isNotBlank() }) bad += "agent ${a.id} has no source with alert mails: it could never draft"
            for (s in a.sources.filter { it.mailFrom.isNotBlank() }) {
                if (s.links.hosts.isEmpty()) bad += "agent ${a.id} source ${s.id} allows no link host"
                if (s.links.idRegex.toPattern().matcher("").groupCount() < 1) bad += "agent ${a.id} source ${s.id} link id_regex has no capture group"
            }
            if (sides.isNotEmpty() && sides.none { it.id == a.side }) bad += "agent ${a.id} is on side ${a.side.ifBlank { "(none)" }}, which is not declared"
            if (a.category.isNotEmpty()) {
                val c = categories.firstOrNull { it.id == a.category }
                if (c == null) bad += "agent ${a.id} is in category ${a.category}, which is not declared"
                else if (c.side != a.side) bad += "agent ${a.id} is in category ${a.category} of side ${c.side}, not of its own side ${a.side}"
            }
            a.filters.filter { f -> filters.none { it.id == f } }.forEach { bad += "agent ${a.id} asks for filter $it, which is not declared" }
            a.outputs.filter { it !in OUTPUTS }.forEach { bad += "agent ${a.id} output $it is none of $OUTPUTS" }
            Template.variables(a.goal).filter { it !in known }.forEach { bad += "agent ${a.id} goal uses {{$it}}, which no profile field, filter or run supplies" }
            val t = template(a.template)
            if (t == null) bad += "agent ${a.id} starts from template ${a.template}, which is not declared"
        }
        for (t in templates) {
            if (agents.none { it.id == t.agent }) bad += "template ${t.id} belongs to agent ${t.agent}, which is not declared"
            Template.variables(t.body).filter { it !in known }.forEach { bad += "template ${t.id} uses {{$it}}, which no profile field, filter or run supplies" }
        }
        return bad
    }

    companion object {
        val BUILTIN_VARS = listOf("listing_title", "listing_url", "listing_id", "personal")
        const val DRAFT_ONLY = "draft_only"

        /** What an agent produces: a result table and drafts. Nothing else exists. */
        val OUTPUTS = listOf("results", "drafts")

        /**
         * Keys no agent (or source) definition may carry: each would ask an agent to act for the owner. Any key that
         * starts with `auto_` is refused too. An agent searches, compares, summarises and drafts; the person sends.
         */
        val FORBIDDEN_KEYS = setOf("submit", "send", "apply", "buy", "post", "checkout", "order", "book", "pay", "contact", "reply")

        private const val SEEN_PREFIX = "agents_seen_"
        private val NO_LINKS = LinkRules(emptyList(), Regex("(^$)"), emptyList())
        /** The per-agent preference keys ([migratePrefs] moves them to an agent's new id). */
        val PER_AGENT_PREFIXES = listOf(SEEN_PREFIX, "agents_filter_", "agents_source_")

        private fun mergeIds(a: String, b: String): String = runCatching {
            val x = JSONArray(a); val y = JSONArray(b)
            JSONArray(((0 until y.length()).map { y.getString(it) } + (0 until x.length()).map { x.getString(it) }).distinct()).toString()
        }.getOrDefault(a)

        /** Every key an agent definition may carry; anything else is refused, so no new switch can slip in. */
        val AGENT_KEYS = setOf(
            "id", "label", "side", "category", "mode", "blurb", "goal", "query", "filters", "outputs", "sources", "group", "template",
            "uses_llm", "report_kind", "legacy_ids",
        )
        val SOURCE_KEYS = setOf("id", "label", "search", "mail_from", "mail_subject", "links")

        private fun refuseActions(o: JSONObject, allowed: Set<String>, what: String) {
            for (k in o.keys()) {
                if (k.startsWith("_")) continue // a _doc note
                val key = k.lowercase()
                require(!key.startsWith("auto") && key !in FORBIDDEN_KEYS && FORBIDDEN_KEYS.none { key.startsWith(it + "_") }) {
                    "$what declares `$k`: Cloud Search only drafts, the person submits, sends, applies and buys"
                }
                require(k in allowed) { "$what declares `$k`, which no agent definition has: Cloud Search only drafts" }
            }
        }

        fun parse(o: JSONObject): AgentsConfig {
            val e = o.getJSONObject("engines")
            val m = e.getJSONObject("mail")
            val b = e.getJSONObject("browser")
            val d = o.getJSONObject("defaults")
            val cfg = AgentsConfig(
                mail = MailEngine(
                    m.getString("fleet"), m.getString("authority_suffix"), m.getString("permission"), m.getString("path_messages"),
                    m.getString("path_body"), m.getString("param_from"), m.getString("param_subject"), m.getString("param_since"),
                    m.getString("param_limit"), m.getString("param_account"), m.getString("param_id"),
                    strings(m.getJSONArray("message_columns")), strings(m.getJSONArray("body_columns")), m.getInt("max_limit"),
                ),
                browser = BrowserEngine(
                    b.getString("fleet"), b.getString("authority_suffix"), b.getString("permission"), b.getString("open_action"),
                    b.getString("fetch_method"), b.getString("extra_url"), b.getString("extra_group"), b.getString("extra_max_chars"),
                ),
                defaults = Defaults(
                    d.getString("model"), d.getDouble("budget_run_usd"), d.getDouble("budget_day_usd"), d.getInt("max_listings"),
                    d.getInt("max_mails"), d.getInt("lookback_days"), d.getInt("page_chars"), d.getInt("personal_max_words"),
                    d.getInt("max_tokens"), d.getDouble("unknown_price_per_token"),
                ),
                profileFields = objects(o.getJSONArray("profile_fields")).map { ProfileField(it.getString("id"), it.getString("label"), it.optString("hint")) },
                // The catalogue (agents, templates) is build.json::agents, put here by SearchConfig.withAgents; without it there is no agent.
                agents = objects(o.optJSONArray("agents")).map { a ->
                    require(a.optString("mode") == DRAFT_ONLY) { "agent ${a.optString("id")} is not mode ${DRAFT_ONLY}: Cloud Search only drafts" }
                    refuseActions(a, AGENT_KEYS, "agent ${a.optString("id")}")
                    Agent(
                        id = a.getString("id"), label = a.getString("label"), blurb = a.optString("blurb"),
                        sources = objects(a.getJSONArray("sources")).map { s ->
                            refuseActions(s, SOURCE_KEYS, "agent ${a.optString("id")} source ${s.optString("id")}")
                            // A search-only source (no alert sender) reads no mail, so it needs no link rules.
                            val l = s.optJSONObject("links")
                            Source(
                                s.getString("id"), s.getString("label"), s.optString("search"), s.optString("mail_from"), s.optString("mail_subject"),
                                if (l == null) NO_LINKS
                                else LinkRules(strings(l.getJSONArray("hosts")).map { it.lowercase() }, Regex(l.getString("id_regex")), strings(l.optJSONArray("exclude_paths"))),
                            )
                        },
                        group = a.optString("group"), template = a.getString("template"), usesLlm = a.optBoolean("uses_llm", false),
                        reportKind = a.getString("report_kind"), side = a.optString("side"), category = a.optString("category"),
                        goal = a.optString("goal"), filters = strings(a.optJSONArray("filters")),
                        outputs = a.optJSONArray("outputs")?.let { strings(it) } ?: OUTPUTS, legacyIds = strings(a.optJSONArray("legacy_ids")),
                        query = a.optString("query"),
                    )
                },
                templates = objects(o.optJSONArray("templates")).map { TemplateSeed(it.getString("id"), it.getString("label"), it.getString("agent"), it.getString("body")) },
                filters = objects(o.optJSONArray("filters")).map { Filter(it.getString("id"), it.getString("label"), it.optString("hint"), it.optBoolean("number", false)) },
                sides = objects(o.optJSONArray("sides")).map { Side(it.getString("id"), it.getString("label")) },
                categories = objects(o.optJSONArray("categories")).map { Category(it.getString("id"), it.getString("side"), it.getString("label")) },
            )
            val problems = cfg.problems()
            require(problems.isEmpty()) { "build.json::search.agents is inconsistent: " + problems.joinToString("; ") }
            return cfg
        }

        private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    }
}
