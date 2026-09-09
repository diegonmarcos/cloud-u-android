package app.sterna.core.data.text

/**
 * [name] proposed to the picker for an attachment: sanitised like [safeFileName], but with its
 */
fun attachmentFileName(name: String?): String {
    val raw = name.orEmpty().trim()
    val dot = raw.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < raw.length - 1 && raw.length - dot - 1 <= MAX_EXTENSION
    val trunk = safeFileName(if (hasExtension) raw.substring(0, dot) else raw, "attachment")
    val extension = if (hasExtension) safeFileName(raw.substring(dot + 1), "") else ""
    return if (extension.isEmpty()) trunk else "$trunk.$extension"
}

/** Past this many characters, what follows the last dot is a word and not a file type. */
private const val MAX_EXTENSION = 10
