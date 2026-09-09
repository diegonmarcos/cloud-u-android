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
    if (isFilePart(fields)) return null
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

    /** Index of `body-fld-dsp` in a `text/…` part's BODYSTRUCTURE (RFC 3501 §7.4.2). A text part
     *  carries one field the others do not — `body-fld-lines` at 7 — so its extension data starts one
     *  late. Only ever read for a `text/…` part, which is what makes the constant correct. */
private const val TEXT_DISPOSITION = 9

    /** Whether this part is a FILE rather than the message, by the rule [MimeParser.walk] already
     *  applies to the same message: an `attachment` disposition, or a filename (on the disposition or
     *  as the content type's `name` parameter). An `inline` disposition with NO filename is not a file
     *  — that is what a sender writes on the body itself. */
private fun isFilePart(fields: List<Any?>): Boolean {
    val disposition = fields.getOrNull(TEXT_DISPOSITION) as? List<Any?>
    if ((disposition?.firstOrNull() as? String).equals("attachment", ignoreCase = true)) return true
    val filename = structureParameter(disposition?.getOrNull(1), "filename")
        ?: structureParameter(fields.getOrNull(2), "name")
    return !filename.isNullOrBlank()
}

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
