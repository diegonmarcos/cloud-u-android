package com.diegonmarcos.cloudsearch.core.agents

import org.json.JSONArray
import org.json.JSONObject

/** One thing a report lists: a draft, with the page it answers and the human's own status on it. */
data class ReportItem(val id: String, val title: String, val url: String, val body: String, val status: String, val note: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("url", url).put("body", body).put("status", status).put("note", note)

    companion object {
        const val DRAFT = "draft"
        const val SENT_BY_ME = "sent_by_me"
        const val DISMISSED = "dismissed"
        fun fromJson(o: JSONObject) = ReportItem(o.getString("id"), o.optString("title"), o.optString("url"), o.optString("body"), o.optString("status", DRAFT), o.optString("note"))
    }
}

/** What an agent publishes: a titled, dated summary and the items it produced. */
data class Report(
    val id: String, val agentId: String, val runId: String, val kind: String, val title: String, val createdAt: Long,
    val summary: String, val items: List<ReportItem>,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("agent", agentId).put("run", runId).put("kind", kind).put("title", title)
        .put("created_at", createdAt).put("summary", summary).put("items", JSONArray(items.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject) = Report(
            o.getString("id"), o.optString("agent"), o.optString("run"), o.optString("kind"), o.optString("title"),
            o.getLong("created_at"), o.optString("summary"),
            o.optJSONArray("items")?.let { a -> (0 until a.length()).map { ReportItem.fromJson(a.getJSONObject(it)) } }.orEmpty(),
        )
    }
}

/** The local API an agent writes its report through. Nothing else leaves the phone with it. */
interface ReportSink {
    fun publish(report: Report)
}

/** The reports as a list: newest first, a bounded history, an item's status changed in place. */
class ReportBook(initial: List<Report> = emptyList(), private val cap: Int = MAX_REPORTS) {
    private var all: List<Report> = initial.sortedByDescending { it.createdAt }.take(cap)

    fun newestFirst(): List<Report> = all
    fun get(id: String): Report? = all.firstOrNull { it.id == id }

    /** A report with an id already in the book replaces it (a re-run publishes the same run's report again). */
    fun publish(r: Report) { all = (listOf(r) + all.filterNot { it.id == r.id }).sortedByDescending { it.createdAt }.take(cap) }

    fun delete(id: String) { all = all.filterNot { it.id == id } }

    fun setItemStatus(reportId: String, itemId: String, status: String) {
        all = all.map { r -> if (r.id != reportId) r else r.copy(items = r.items.map { if (it.id == itemId) it.copy(status = status) else it }) }
    }

    fun toJson(): JSONArray = JSONArray(all.map { it.toJson() })

    companion object {
        const val MAX_REPORTS = 200
        fun fromJson(text: String): ReportBook = runCatching {
            val a = JSONArray(text)
            ReportBook((0 until a.length()).map { Report.fromJson(a.getJSONObject(it)) })
        }.getOrDefault(ReportBook())
    }
}

/** One finished run, for the Runs page: counts, cost and the audit lines. */
data class RunRecord(
    val id: String, val agentId: String, val startedAt: Long, val endedAt: Long, val status: String, val summary: String,
    val mails: Int, val links: Int, val pages: Int, val drafts: Int, val llmCalls: Int, val costUsd: Double, val audit: List<AuditEvent>,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("agent", agentId).put("started_at", startedAt).put("ended_at", endedAt)
        .put("status", status).put("summary", summary).put("mails", mails).put("links", links).put("pages", pages).put("drafts", drafts)
        .put("llm_calls", llmCalls).put("cost_usd", costUsd).put("audit", JSONArray(audit.map { it.toJson() }))

    companion object {
        const val OK = "ok"
        const val PARTIAL = "partial"
        const val FAILED = "failed"
        const val STOPPED = "stopped"

        fun fromJson(o: JSONObject) = RunRecord(
            o.getString("id"), o.optString("agent"), o.getLong("started_at"), o.getLong("ended_at"), o.optString("status"), o.optString("summary"),
            o.optInt("mails"), o.optInt("links"), o.optInt("pages"), o.optInt("drafts"), o.optInt("llm_calls"), o.optDouble("cost_usd"),
            AuditLog.eventsFromJson(o.optJSONArray("audit")),
        )

        const val MAX_RUNS = 100
        fun listFromJson(text: String): List<RunRecord> = runCatching {
            val a = JSONArray(text); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

object Reports {
    /** The report an agent publishes for one finished run: one item per draft, newest report first in the book. */
    fun digest(agent: AgentsConfig.Agent, out: DraftRunner.Outcome): Report {
        val r = out.record
        return Report(
            id = r.id, agentId = agent.id, runId = r.id, kind = agent.reportKind,
            title = agent.label + " digest",
            createdAt = r.endedAt, summary = r.summary + if (r.costUsd > 0) " · " + BudgetLedger.usd(r.costUsd) else "",
            items = out.drafts.map { ReportItem(it.listingId, it.title, it.url, it.message, ReportItem.DRAFT, it.note) },
        )
    }
}
