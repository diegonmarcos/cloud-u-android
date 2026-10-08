package app.sterna.core.data.text

/** A summary and the reply suggested for the message it summarises, made by ONE model call. */
data class SummaryParts(val summary: String, val reply: String?)

/**
 * The wire format of that one call: the summary, then a line holding exactly [MARK], then the suggested
 * reply. One call and one cache row, so the reply is always the one written together with the summary
 * on the same read of the same message. [split] never throws and never loses the summary: a model that
 * forgot the marker gives a summary and no reply.
 */
object SuggestedReply {
    const val MARK = "===REPLY==="

    fun split(raw: String): SummaryParts {
        val at = raw.indexOf(MARK)
        if (at < 0) return SummaryParts(raw.trim(), null)
        val summary = raw.substring(0, at).trim()
        val reply = raw.substring(at + MARK.length).trim().ifEmpty { null }
        return SummaryParts(summary, reply)
    }

    fun join(parts: SummaryParts): String =
        if (parts.reply == null) parts.summary else parts.summary + "\n" + MARK + "\n" + parts.reply
}
