package app.sterna.core.imap

    /**
     * Where a message's readable text is, as the SERVER described it in the BODYSTRUCTURE that came
     */
data class ImapTextPart(
    val section: String,
    val mime: String,
    val encoding: String?,
    val charset: String?,
)

/** How much of a body a preview fetch reads: the first 8 KiB of the text part, and no more. */
const val IMAP_PREVIEW_FETCH_BYTES = 8192

/** Bound the nesting a structure walk will follow (the [MimeParser] bound, and for the reason). */
private const val MAX_STRUCTURE_DEPTH = 24

/** Bound the siblings of one multipart a structure walk will look at. */
private const val MAX_STRUCTURE_PARTS = 1024

    /**
     * The FIRST part of a parsed BODYSTRUCTURE that is this message's BODY, depth first — or null,
     */
fun firstTextPart(bodystructure: Any?): ImapTextPart? = textPartIn(bodystructure, prefix = "", depth = 0)

private fun textPartIn(node: Any?, prefix: String, depth: Int): ImapTextPart? {
    if (depth > MAX_STRUCTURE_DEPTH) return null
    val fields = node as? List<Any?> ?: return null
    val head = fields.firstOrNull()
        // A multipart's structure opens with the structures of its children; a single part opens with
        // its type as a string. That shape IS the discriminator (RFC 3501 §7.4.2), and there is no
        // other: the subtype of a multipart sits after its children, not before them.
    if (head is List<*>) {
        fields.asSequence().takeWhile { it is List<*> }.take(MAX_STRUCTURE_PARTS)
            .forEachIndexed { index, child ->
                val childPrefix = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
                textPartIn(child, childPrefix, depth + 1)?.let { return it }
            }
        return null
    }
    if (!(head as? String).equals("text", ignoreCase = true)) return null
    val subtype = (fields.getOrNull(1) as? String)?.lowercase() ?: return null
    if (subtype !in BODY_SUBTYPES) return null
    if (isFilePart(fields, dispositionIndex(head as? String, subtype))) return null
    return ImapTextPart(
        // A message that is not a multipart at all is section "1" — the same numbering
        // `MimeParser.walk` gives it (`prefix.ifEmpty { "1" }`), so both sides address one part.
        section = prefix.ifEmpty { "1" },
        mime = "text/$subtype",
        encoding = (fields.getOrNull(5) as? String)?.lowercase(),
        charset = structureParameter(fields.getOrNull(2), "charset"),
    )
}

    /** The text subtypes that are a message's body. `text/calendar` is deliberately absent:
     *  [MimeParser] captures it as a downloadable `invite.ics`, and `BEGIN:VCALENDAR` is machine text,
     *  not something to read on a lock screen. */
private val BODY_SUBTYPES = setOf("plain", "html")

    /**
     * Index of `body-fld-dsp` in a leaf part's BODYSTRUCTURE (RFC 3501 §7.4.2). It is NOT one number:
     * the fixed fields before the extension data differ by type. A `text/…` part carries
     * `body-fld-lines` at 7; a `message/rfc822` part carries an envelope, a body and a line count at
     * 7, 8 and 9. Everything else has neither. Reading the disposition at a single hardcoded offset
     * therefore reads a DIFFERENT field on two of the three shapes -- for `message/rfc822` it lands on
     * the nested body structure, a list, whose head is not the word "attachment", so an attached
     * message would silently stop being recognised as a file.
     */
private fun dispositionIndex(type: String?, subtype: String?): Int = when {
    type.equals("text", ignoreCase = true) -> 9
    type.equals("message", ignoreCase = true) && subtype.equals("rfc822", ignoreCase = true) -> 11
    else -> 8
}

    /** Whether this part is a FILE rather than the message, by the rule [MimeParser.walk] already
     *  applies to the same message: an `attachment` disposition, or a filename (on the disposition or
     *  as the content type's `name` parameter). An `inline` disposition with NO filename is not a file
     *  — that is what a sender writes on the body itself. */
private fun isFilePart(fields: List<Any?>, dispositionAt: Int): Boolean =
    !declaredFilename(fields, dispositionAt).isNullOrBlank() ||
        (dispositionOf(fields, dispositionAt)?.firstOrNull() as? String).equals("attachment", ignoreCase = true)

private fun dispositionOf(fields: List<Any?>, dispositionAt: Int): List<Any?>? =
    fields.getOrNull(dispositionAt) as? List<Any?>

    /** The filename the SENDER declared, from the disposition first and the content type's `name`
     *  parameter second. Attacker-controlled in both places and never used as a path: it reaches the
     *  disk only through [StorageRepository.cacheAttachment], which reduces it to a bare safe name. */
private fun declaredFilename(fields: List<Any?>, dispositionAt: Int): String? =
    structureParameter(dispositionOf(fields, dispositionAt)?.getOrNull(1), "filename")
        ?: structureParameter(fields.getOrNull(2), "name")

/** One parameter of a BODYSTRUCTURE parameter list — `("charset" "utf-8" "format" "flowed")`. */
private fun structureParameter(parameters: Any?, name: String): String? {
    val list = parameters as? List<Any?> ?: return null
    var i = 0
    while (i < list.size) {
        if ((list[i] as? String).equals(name, ignoreCase = true)) return list.getOrNull(i + 1) as? String
        i += 2
    }
    return null
}

    /**
     * Every FILE part of a parsed BODYSTRUCTURE, depth first, with the numbering a later
     * `BODY[section]` fetch addresses them by.
     *
     * WHY THIS COSTS NOTHING. Every IMAP list fetch in this app already asks for BODYSTRUCTURE --
     * `FETCH … (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE …)`, at four call sites -- and already
     * walks it twice, for [firstTextPart] and for the boolean `hasAttachment`. The server has
     * therefore ALREADY sent the filename, the MIME type and the octet count of every part on this
     * page; they were being parsed past and dropped. Reading them out adds no FETCH item, no round
     * trip and no byte on the wire. The message list can name a file because the wire always said so.
     *
     * The message's own body is not a file and is not returned ([isFilePart], the rule
     * [MimeParser.walk] applies to the same message, so a part is classified the same whether it was
     * found here or in the downloaded source).
     *
     * An empty [MimeAttachment.name] means the sender declared NO filename -- it is not a name. The
     * callers show their own placeholder rather than let one be invented this far down.
     */
fun attachmentParts(bodystructure: Any?): List<MimeAttachment> =
    buildList { collectFileParts(bodystructure, prefix = "", depth = 0, into = this) }

private fun collectFileParts(node: Any?, prefix: String, depth: Int, into: MutableList<MimeAttachment>) {
    if (depth > MAX_STRUCTURE_DEPTH) return
    val fields = node as? List<Any?> ?: return
    val head = fields.firstOrNull()
    // The same discriminator [textPartIn] uses, for the same reason: a multipart opens with its
    // children's structures, a leaf with its type as a string (RFC 3501 §7.4.2).
    if (head is List<*>) {
        fields.asSequence().takeWhile { it is List<*> }.take(MAX_STRUCTURE_PARTS)
            .forEachIndexed { index, child ->
                val childPrefix = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
                collectFileParts(child, childPrefix, depth + 1, into)
            }
        return
    }
    val type = head as? String ?: return
    val subtype = fields.getOrNull(1) as? String
    val dispositionAt = dispositionIndex(type, subtype)
    if (!isFilePart(fields, dispositionAt)) return
    into += MimeAttachment(
        // A message that is not a multipart at all is section "1", the numbering [textPartIn] and
        // [MimeParser.walk] both give it, so all three address one part.
        section = prefix.ifEmpty { "1" },
        name = declaredFilename(fields, dispositionAt).orEmpty(),
        type = listOfNotNull(type, subtype).joinToString("/").lowercase(),
        // `body-fld-octets` is the ENCODED size the server counted, so a base64 part reads about a
        // third larger than the file the user will get. It is what every other client shows and what
        // the download ceiling is enforced against, so showing anything else here would disagree
        // with the refusal the user gets when they tap.
        size = (fields.getOrNull(6) as? Number)?.toInt() ?: 0,
        encoding = (fields.getOrNull(5) as? String)?.lowercase().orEmpty(),
        // `body-fld-id` arrives inside angle brackets; strip them, as the reader's own parse does.
        cid = (fields.getOrNull(3) as? String)?.trim()?.removePrefix("<")?.removeSuffix(">")
            ?.takeIf { it.isNotBlank() },
    )
}
