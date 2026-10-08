package com.diegonmarcos.cloudsearch.core.agents

import org.json.JSONArray
import org.json.JSONObject

/**
 * What an agent read and drafted, one line each. It records WHAT was touched (message ids and subjects, URLs, counts,
 * models, costs): never a message body, never page text, never a token.
 */
data class AuditEvent(val at: Long, val kind: String, val subject: String, val detail: String = "") {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("kind", kind).put("subject", subject).put("detail", detail)

    companion object {
        const val RUN_START = "run_start"
        const val MAIL_QUERY = "mail_query"
        const val MAIL_READ = "mail_read"
        const val MAIL_FAILED = "mail_failed"
        const val LINK_FOUND = "link_found"
        const val LINK_DUPLICATE = "link_duplicate"
        const val LINK_SKIPPED = "link_skipped"
        const val PAGE_READ = "page_read"
        const val PAGE_FAILED = "page_failed"
        const val LLM_CALL = "llm_call"
        const val LLM_DENIED = "llm_denied"
        const val DRAFT = "draft"
        const val RUN_END = "run_end"

        fun fromJson(o: JSONObject) = AuditEvent(o.getLong("at"), o.getString("kind"), o.getString("subject"), o.optString("detail"))
    }
}

class AuditLog(private val cap: Int = MAX_EVENTS) {
    private val events = ArrayList<AuditEvent>()
    var dropped = 0
        private set

    fun add(at: Long, kind: String, subject: String, detail: String = "") {
        if (events.size >= cap) { dropped++; return }
        events += AuditEvent(at, kind, subject.oneLine(MAX_FIELD), detail.oneLine(MAX_FIELD))
    }

    fun events(): List<AuditEvent> = events.toList()

    fun toJson(): JSONArray = JSONArray(events.map { it.toJson() })

    private fun String.oneLine(n: Int): String = replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n - 1) + "…" else it }

    companion object {
        const val MAX_EVENTS = 600
        const val MAX_FIELD = 160

        fun eventsFromJson(a: JSONArray?): List<AuditEvent> =
            if (a == null) emptyList() else (0 until a.length()).map { AuditEvent.fromJson(a.getJSONObject(it)) }
    }
}
