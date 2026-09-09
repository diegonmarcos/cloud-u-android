package app.sterna.core.data.mail

/**
 * The lines that opened the blocks still open just before [index] — what a given line of source is
 */
internal fun openBlocksAt(lines: List<String>, index: Int): List<String> {
    val stack = ArrayDeque<String>()
    lines.take(index).forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEach
        val delta = line.count { it == '{' } - line.count { it == '}' }
        repeat(maxOf(0, -delta)) { stack.removeLastOrNull() }
        repeat(maxOf(0, delta)) { stack.addLast(line) }
    }
    return stack.toList()
}
