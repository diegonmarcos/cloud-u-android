package com.diegonmarcos.superapp.browser

import org.json.JSONObject

/** #823 the assistant side panel's transcript model ([AgentChatPanel]); pure, JVM-tested. */

/** One line of the panel's transcript. */
data class AgentLine(val role: String, val text: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        /** A status line: an error, a denial, the route a summary came from. */
        const val NOTE = "note"
    }
}

/** A mutating call waiting for his decision. */
data class AgentPending(val callId: String, val sentence: String)

/** Pure: what the runner's answer adds to the transcript (a pending one adds nothing: its card shows). */
object AgentPanelText {
    fun lineFor(answer: JSONObject): AgentLine? = when (answer.optString("status")) {
        "answered" -> AgentLine(AgentLine.ASSISTANT, answer.optString("message"))
        "pending_confirmation" -> null
        else -> AgentLine(AgentLine.NOTE, "Error: " + answer.optString("error", "no answer"))
    }

    /** A summary answer ([PageSummary.Result.json]) as the assistant's line plus where it came from. */
    fun summaryLines(r: JSONObject): List<AgentLine> =
        if (!r.optBoolean("ok")) listOf(AgentLine(AgentLine.NOTE, "Could not summarize: " + r.optString("error")))
        else listOf(AgentLine(AgentLine.ASSISTANT, r.optString("summary")), AgentLine(AgentLine.NOTE, PageSummary.credit(r)))

    fun decisionLine(allow: Boolean, sentence: String) =
        AgentLine(AgentLine.NOTE, (if (allow) "Allowed: " else "Denied: ") + sentence)
}
