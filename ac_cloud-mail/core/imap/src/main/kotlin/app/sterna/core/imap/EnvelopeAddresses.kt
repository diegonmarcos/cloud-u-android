package app.sterna.core.imap

    /**
     * One envelope address list — slot 4 (`reply-to`), 5 (`to`), 6 (`cc`) or 7 (`bcc`) of an ENVELOPE
     */
internal fun envelopeAddresses(entries: List<Any?>?): List<ImapAddress> {
    val parsed = entries.orEmpty().map(::parseEnvelopeEntry)
    val out = ArrayList<ImapAddress>(parsed.size)
    for (index in parsed.indices) {
        val entry = parsed[index] ?: continue
        if (entry.isMember) {
            out += ImapAddress(name = entry.name, email = entry.email)
            continue
        }
            // Neither an address nor a name: a delimiter, or something malformed. The only one worth
            // an entry is a group start that no member follows — a group naming nobody. "No member
            // follows" covers a group end, the end of the list, and another group start;
            // `parsed[index + 1]` is null both past the end and for a token that is not an address.
        if (entry.isGroupStart && parsed.getOrNull(index + 1)?.isMember != true) {
            out += ImapAddress(name = decodeWords(entry.rawBox), email = null)
        }
    }
    return out
}

/** One parsed envelope entry: what it says, and which of the three shapes it is. */
private class EnvelopeEntry(
    val name: String?,
    val email: String?,
    val rawBox: String?,
    val isGroupStart: Boolean,
) {
    /** It carries an address or a display name, so it is a recipient and not a delimiter. */
    val isMember: Boolean get() = email != null || name != null
}

/** An envelope address `(name adl mailbox host)`, or null for a token that is not one. */
@Suppress("UNCHECKED_CAST")
private fun parseEnvelopeEntry(entry: Any?): EnvelopeEntry? {
    val addr = entry as? List<Any?> ?: return null
    val rawName = addr.getOrNull(0) as? String
    val rawBox = addr.getOrNull(2) as? String
    val rawHost = addr.getOrNull(3) as? String
    val name = decodeWords(rawName)
        // The local part and the domain need [decodeHeaderBytes] as much as the display name beside
        // them: an EAI address (RFC 6531) arrives as an 8-bit literal, and a mangled one sends mail
        // to an address that does not exist.
    val box = rawBox?.let(::decodeHeaderBytes)
    val host = rawHost?.let(::decodeHeaderBytes)
    return EnvelopeEntry(
        name = name,
        email = if (box != null && host != null) "$box@$host" else null,
        rawBox = rawBox,
        // Index 1 (the source route, `adl`) is ignored here as it is everywhere else: it is
        // obsolete and no reader looks at it.
        isGroupStart = rawName == null && rawBox != null && rawHost == null,
    )
}
