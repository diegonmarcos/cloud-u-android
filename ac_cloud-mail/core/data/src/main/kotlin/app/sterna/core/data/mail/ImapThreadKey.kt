package app.sterna.core.data.mail

/** The thread key of an IMAP message, rebuilt from its threading headers: the root of `References`
 *  (RFC 5322 §3.6.4), failing that `In-Reply-To`, failing that its own `Message-ID`; none of the
 *  three gives no key, and `COALESCE(threadId, id)` makes it its own conversation. Stored with its
 *  angle brackets, so a key cannot collide with an `imap:…` row id; no case folding. */
internal fun imapThreadKey(references: String?, inReplyTo: String?, messageId: String?): String? =
    firstHeaderId(references) ?: firstHeaderId(inReplyTo) ?: firstHeaderId(messageId)

private val BRACKETED_ID = Regex("<[^<>]+>")

private fun firstHeaderId(value: String?): String? {
    if (value.isNullOrBlank()) return null
    BRACKETED_ID.find(value)?.let { return it.value }
    return value.trim().split(Regex("\\s+")).firstOrNull()?.let { "<$it>" }
}
