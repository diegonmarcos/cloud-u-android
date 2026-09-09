package app.sterna.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span

/**
 * What the body field DRAWS in front of a list's lines (#131): `• ` on every line of a bullet block,
 */
fun markedText(text: AnnotatedString, blocks: List<Block>): TransformedText {
    // FIRST LINE, and that is the whole of it: a body with no list is handed straight back with
    // the identity mapping, so nothing is walked. Placed after `lineStarts`, this "free" path
    // scanned every character and allocated a list per line on every recomposition — on a 324 kB
    // reply, in a field whose smoothness is #5, #6 and #26.
    if (blocks.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
    val starts = lineStarts(text.text)
    val markers = markersOf(starts.size, blocks)
    // A block that falls entirely outside this text marks nothing: same answer, same mapping.
    if (markers.all { it.isEmpty() }) return TransformedText(text, OffsetMapping.Identity)
    val out = AnnotatedString.Builder()
    for (line in starts.indices) {
        out.append(markers[line])
        // A SUBSEQUENCE of the original, never its plain `text`: the inline styling (#131) rides
        // along on its own, so a bold word inside a list item stays bold and stays put.
        val from = starts[line]
        val to = if (line + 1 < starts.size) starts[line + 1] else text.length
        out.append(text.subSequence(from, to))
    }
    return TransformedText(out.toAnnotatedString(), MarkerOffsets(starts, markers, text.length))
}

/**
 * THE body field's one and only `VisualTransformation`, carrying BOTH halves (#131): the inline styling
 */
fun bodyTransformation(rich: RichBody, linkColor: Color): VisualTransformation =
    object : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText =
            markedText(styledBody(text, rich, linkColor), rich.blocks)
    }

/**
 * The list kind covering EVERY line the selection touches, or null — what the two list buttons show
 */
fun listKindAt(body: RichBody, selection: Span): BlockKind? {
    val starts = lineStarts(body.text)
    val first = lineOf(starts, selection.start)
    // A selection stopping exactly at the start of a line does not reach into it.
    val last = lineOf(starts, if (selection.isEmpty) selection.start else selection.end - 1)
    val kind = kindOfLine(body.blocks, first) ?: return null
    for (line in first..last) if (kindOfLine(body.blocks, line) != kind) return null
    return kind
}

/** The marker each line is drawn with, `""` for a line no block covers. */
private fun markersOf(lineCount: Int, blocks: List<Block>): List<String> {
    val out = MutableList(lineCount) { "" }
    for (block in blocks) {
        var n = 1
        for (line in block.lines) {
            if (line in 0 until lineCount) {
                out[line] = if (block.kind == BlockKind.BULLET) BULLET_MARKER else "$n. "
            }
            n++
        }
    }
    return out
}

/**
 * The two halves of the mapping, and the half that breaks everything visible if it is wrong.
 */
private class MarkerOffsets(
    private val starts: List<Int>,
    private val markers: List<String>,
    private val length: Int,
) : OffsetMapping {
    /** How many marker characters stand before line `l` in the transformed text. */
    private val before: IntArray = IntArray(starts.size + 1).also {
        for (l in starts.indices) it[l + 1] = it[l] + markers[l].length
    }

    /** Where each line starts in the TRANSFORMED text (its marker included). */
    private val transformedStarts: List<Int> = starts.mapIndexed { l, s -> s + before[l] }

    override fun originalToTransformed(offset: Int): Int {
        val o = offset.coerceIn(0, length)
        val line = lineOf(starts, o)
        return o + before[line] + markers[line].length
    }

    override fun transformedToOriginal(offset: Int): Int {
        val t = offset.coerceIn(0, length + before[starts.size])
        val line = lineOf(transformedStarts, t)
        val d = t - transformedStarts[line]
        return if (d <= markers[line].length) starts[line]
        else (starts[line] + d - markers[line].length).coerceAtMost(length)
    }
}

/** The offset each line starts at; its size is the line count. */
private fun lineStarts(text: String): List<Int> {
    val out = ArrayList<Int>()
    out.add(0)
    for (i in text.indices) if (text[i] == '\n') out.add(i + 1)
    return out
}

/** The index of the line holding [offset], given the line starts; the `\n` belongs to its line. */
private fun lineOf(starts: List<Int>, offset: Int): Int {
    val found = starts.binarySearch(offset.coerceAtLeast(0))
    return if (found >= 0) found else (-found - 2).coerceIn(0, starts.size - 1)
}

private fun kindOfLine(blocks: List<Block>, line: Int): BlockKind? =
    blocks.firstOrNull { line in it.lines }?.kind

/** Two characters, marker plus one space, exactly like the `- ` of `toPlainText`. */
private const val BULLET_MARKER = "• "
