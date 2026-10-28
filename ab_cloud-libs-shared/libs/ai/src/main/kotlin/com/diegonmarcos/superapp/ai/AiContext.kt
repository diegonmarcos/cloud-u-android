package com.diegonmarcos.superapp.ai

import java.util.Locale

/** Context-window usage, from what the backend REPORTED (no estimate): the last answer's prompt + completion is what the next request starts from. */
object AiContext {
    /** Tokens the open session occupies: the last answer's reported prompt plus completion; 0 when no answer has reported any. */
    fun used(session: AiSession): Long = session.messages.lastOrNull { it.role == "assistant" && it.prompt + it.completion > 0 }?.let { it.prompt + it.completion } ?: 0

    /** Percent of [limit] used, one decimal; null when the limit is unknown. */
    fun percent(used: Long, limit: Long): String? = if (limit <= 0) null else String.format(Locale.ROOT, "%.1f", used * 100.0 / limit)

    /** "Context 1,234 / 128,000 tokens (1.0%)", the same without a limit when the model list reports none, null before any reported usage. */
    fun line(session: AiSession, limit: Long, s: AiStrings): String? {
        val u = used(session)
        if (u <= 0) return null
        val pct = percent(u, limit) ?: return s.contextUsed.format(n(u))
        return s.contextLine.format(n(u), n(limit), pct)
    }

    /** One answer's reported tokens ("812 in · 140 out"); "" when none were reported. */
    fun answer(m: AiMsg, fmt: String): String = if (m.prompt + m.completion <= 0) "" else fmt.format(n(m.prompt), n(m.completion))

    private fun n(v: Long) = String.format(Locale.ROOT, "%,d", v)
}
