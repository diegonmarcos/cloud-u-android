package com.diegonmarcos.cloudsearch.core.agents

import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::search.agents, read once: the two engine contracts, the defaults for a run, the owner's profile
 * fields, the agents and the template each starts from. No host, path, column, model or word of a template is
 * spelled in Kotlin.
 */
data class AgentsConfig(
    val mail: MailEngine,
    val browser: BrowserEngine,
    val defaults: Defaults,
    val profileFields: List<ProfileField>,
    val agents: List<Agent>,
    val templates: List<TemplateSeed>,
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

    data class Agent(
        val id: String, val label: String, val blurb: String, val mailFrom: String, val mailSubject: String, val links: LinkRules,
        val group: String, val template: String, val usesLlm: Boolean, val reportKind: String,
    )

    data class TemplateSeed(val id: String, val label: String, val agent: String, val body: String)

    fun agent(id: String): Agent? = agents.firstOrNull { it.id == id }
    fun template(id: String): TemplateSeed? = templates.firstOrNull { it.id == id }

    /** Variables the run itself supplies, beside the owner's profile fields. */
    val builtinVars: List<String> get() = BUILTIN_VARS

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
        if (agents.map { it.id }.toSet().size != agents.size) bad += "duplicate agent ids"
        if (templates.map { it.id }.toSet().size != templates.size) bad += "duplicate template ids"
        if (profileFields.map { it.id }.toSet().size != profileFields.size) bad += "duplicate profile field ids"
        val known = profileFields.map { it.id }.toSet() + BUILTIN_VARS
        for (a in agents) {
            if (a.links.hosts.isEmpty()) bad += "agent ${a.id} allows no link host"
            if (a.links.idRegex.toPattern().matcher("").groupCount() < 1) bad += "agent ${a.id} link id_regex has no capture group"
            val t = template(a.template)
            if (t == null) bad += "agent ${a.id} starts from template ${a.template}, which is not declared"
        }
        for (t in templates) {
            if (agent(t.agent) == null) bad += "template ${t.id} belongs to agent ${t.agent}, which is not declared"
            Template.variables(t.body).filter { it !in known }.forEach { bad += "template ${t.id} uses {{$it}}, which no profile field or run supplies" }
        }
        return bad
    }

    companion object {
        val BUILTIN_VARS = listOf("listing_title", "listing_url", "listing_id", "personal")
        const val DRAFT_ONLY = "draft_only"

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
                agents = objects(o.getJSONArray("agents")).map { a ->
                    require(a.optString("mode") == DRAFT_ONLY) { "agent ${a.optString("id")} is not mode ${DRAFT_ONLY}: Cloud Search only drafts" }
                    val l = a.getJSONObject("links")
                    Agent(
                        a.getString("id"), a.getString("label"), a.optString("blurb"), a.getString("mail_from"), a.optString("mail_subject"),
                        LinkRules(strings(l.getJSONArray("hosts")).map { it.lowercase() }, Regex(l.getString("id_regex")), strings(l.optJSONArray("exclude_paths"))),
                        a.optString("group"), a.getString("template"), a.optBoolean("uses_llm", false), a.getString("report_kind"),
                    )
                },
                templates = objects(o.getJSONArray("templates")).map { TemplateSeed(it.getString("id"), it.getString("label"), it.getString("agent"), it.getString("body")) },
            )
            val problems = cfg.problems()
            require(problems.isEmpty()) { "build.json::search.agents is inconsistent: " + problems.joinToString("; ") }
            return cfg
        }

        private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    }
}
