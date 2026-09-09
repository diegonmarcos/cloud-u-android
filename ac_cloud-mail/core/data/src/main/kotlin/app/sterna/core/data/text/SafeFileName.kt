package app.sterna.core.data.text

/**
 * [name] made safe for a file name: keeps unicode letters, replaces only `/ \ : * ? " < > |` and
 */
fun safeFileName(name: String?, fallback: String, maxLength: Int = 80): String {
    val cleaned = name.orEmpty().trim()
        .map { c -> if (c in HOSTILE || c.isISOControl()) '_' else c }
        .joinToString("")
        .take(maxLength)
        .dropLastWhile { it.isHighSurrogate() }
        .trim()
    return cleaned.ifEmpty { fallback }
}

/** What no file system accepts in a name, across Android, Windows and the picker's providers. */
private const val HOSTILE = "/\\:*?\"<>|"
