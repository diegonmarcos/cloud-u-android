package com.diegonmarcos.cloudcalc.sheet

/**
 * Point-and-click references while a formula is edited (as Google Sheets and Excel do): what a tap or a drag on
 * the grid inserts at the caret, whether it may, and where the references of a formula are (to colour them).
 * Pure text in, text out: no Android, so the suite runs in milliseconds.
 */
object Refs {
    /** The edit in progress: [text], the selection [start]..[end] (a caret when equal) and the span the last tap inserted. */
    data class Edit(val text: String, val start: Int = text.length, val end: Int = start, val last: IntRange? = null)

    /** A reference found in a formula: where it sits in the text, its sheet (null = the formula's own) and its cells. */
    data class Token(val range: IntRange, val sheet: String?, val area: Area)

    fun isFormula(text: String) = text.startsWith("=")

    /** "B3", "A1:B5", or "Sheet2!A1" / "'My Sheet'!A1:B5" when [sheet] is given (a cell of another sheet). */
    fun ref(area: Area, sheet: String? = null): String =
        (if (sheet != null) Formula.quoteSheet(sheet) + "!" else "") + area.label()

    /** Characters after which a reference may stand: an operator, an opening bracket or an argument separator. */
    private const val LEADERS = "=+-*/^&(,;<>"

    /** The span a tap would REPLACE: the last inserted reference, while the caret still sits right after it untouched. */
    fun replaceable(e: Edit): IntRange? {
        val l = e.last ?: return null
        return if (e.start == e.end && e.end == l.last + 1 && l.first >= 0 && l.last < e.text.length) l else null
    }

    /** True when a tap on a cell inserts its address: a formula, with an operator (or a bracket) before the caret or a reference to replace. */
    fun canInsert(e: Edit): Boolean {
        if (!isFormula(e.text)) return false
        if (replaceable(e) != null) return true
        val from = minOf(e.start, e.end).coerceIn(0, e.text.length)
        val before = e.text.substring(0, from).trimEnd()
        return before.isNotEmpty() && before.last() in LEADERS
    }

    /** [e] with [ref] in place of the selection, or of the last inserted reference; the caret after it and [Edit.last] its span. */
    fun insert(e: Edit, ref: String): Edit {
        val span = replaceable(e)
        val len = e.text.length
        val from = span?.first ?: minOf(e.start, e.end).coerceIn(0, len)
        val to = span?.let { it.last + 1 } ?: maxOf(e.start, e.end).coerceIn(0, len)
        val text = e.text.substring(0, from) + ref + e.text.substring(to)
        val caret = from + ref.length
        return Edit(text, caret, caret, from until caret)
    }

    private val TOKEN = Regex(
        "(?<![A-Za-z0-9_.$])((?:'(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_.]*)!)?" +
            "(\\$?[A-Za-z]{1,3}\\$?[0-9]{1,7})(?::(\\$?[A-Za-z]{1,3}\\$?[0-9]{1,7}))?(?![A-Za-z0-9_(.])",
    )

    /** Every cell or range reference of the formula [text], in order; text inside "quotes" is not looked at. */
    fun tokens(text: String): List<Token> {
        if (!isFormula(text)) return emptyList()
        val masked = StringBuilder(text)
        var inStr = false
        for (i in text.indices) {
            if (text[i] == '"') { inStr = !inStr; masked.setCharAt(i, ' ') } else if (inStr) masked.setCharAt(i, ' ')
        }
        return TOKEN.findAll(masked).mapNotNull { m ->
            val a = A1.parseCell(m.groupValues[2]) ?: return@mapNotNull null
            val b = if (m.groupValues[3].isNotEmpty()) A1.parseCell(m.groupValues[3]) ?: return@mapNotNull null else a
            val sheet = m.groupValues[1].takeIf { it.isNotEmpty() }?.dropLast(1)?.let { s ->
                if (s.startsWith("'")) s.substring(1, s.length - 1).replace("''", "'") else s
            }
            Token(m.range, sheet, Area(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second)))
        }.toList()
    }
}
