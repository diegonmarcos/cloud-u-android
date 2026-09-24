package com.diegonmarcos.cloudlib.fileeditor

import java.io.File
import java.nio.charset.Charset

/** How lines end on disk. Detected on read, preserved on write, switchable by the user. */
enum class LineEnding(val chars: String, val label: String) {
    LF("\n", "LF"), CRLF("\r\n", "CRLF"), CR("\r", "CR");

    companion object {
        /** The dominant ending in [text]; LF when there is none to judge by. */
        fun detect(text: String): LineEnding {
            var crlf = 0; var lf = 0; var cr = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r') { if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i++ } else cr++ }
                else if (c == '\n') lf++
                i++
            }
            return when {
                crlf >= lf && crlf >= cr && crlf > 0 -> CRLF
                cr > lf && cr > 0 -> CR
                else -> LF
            }
        }
    }
}

/** What was read off disk besides the text: the shape the file has to keep on write. */
data class EditorDocument(
    val text: String,
    val lineEnding: LineEnding,
    val charset: Charset,
    val hadBom: Boolean,
) {
    companion object {
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** Decode [bytes]: a UTF-8/UTF-16 byte-order mark wins, otherwise UTF-8. Line endings normalised to LF in memory. */
        fun decode(bytes: ByteArray): EditorDocument {
            val (charset, bomLen) = when {
                bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2] -> Charsets.UTF_8 to 3
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
                else -> Charsets.UTF_8 to 0
            }
            val raw = String(bytes, bomLen, bytes.size - bomLen, charset)
            return EditorDocument(normalize(raw), LineEnding.detect(raw), charset, bomLen > 0)
        }

        fun read(file: File): EditorDocument = decode(file.readBytes())

        /** Every CRLF / CR becomes LF: one in-memory shape, whatever the disk had. */
        fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

        /** The bytes to write: LF text re-joined with [lineEnding], BOM restored if the file had one. */
        fun encode(text: String, lineEnding: LineEnding, charset: Charset, withBom: Boolean): ByteArray {
            val body = if (lineEnding == LineEnding.LF) text else text.replace("\n", lineEnding.chars)
            val bytes = body.toByteArray(charset)
            if (!withBom) return bytes
            val bom = when (charset) {
                Charsets.UTF_8 -> UTF8_BOM
                Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> ByteArray(0)
            }
            return bom + bytes
        }
    }

    /** Write [text] beside the target, then move it into place: a crash mid-write never truncates the file. */
    fun write(file: File, text: String, lineEnding: LineEnding = this.lineEnding) {
        val tmp = File(file.parentFile, "." + file.name + ".cloud-drive-tmp")
        tmp.writeBytes(encode(text, lineEnding, charset, hadBom))
        if (!tmp.renameTo(file)) { file.writeBytes(tmp.readBytes()); tmp.delete() }
    }
}

/**
 * The editor's model: the text, its undo/redo history, find and replace, and
 * the line/column arithmetic the gutter and status bar need. Pure Kotlin so the
 * JVM suite proves every branch; the Compose screen is a view over it.
 *
 * History is snapshot-based (each edit keeps the previous whole text), capped
 * at [maxHistory] entries. ponytail: snapshots, not deltas — a phone editing a
 * 200 KB file keeps at most ~40 MB of history, and delta compression is the
 * upgrade path if that is ever measured to matter.
 */
class EditorBuffer(initial: String = "", private val maxHistory: Int = 200) {
    var text: String = initial
        private set
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Replace the whole text (the text field hands the new value back on every keystroke). */
    fun edit(newText: String) {
        if (newText == text) return
        undo.addLast(text)
        while (undo.size > maxHistory) undo.removeFirst()
        redo.clear()
        text = newText
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(text); text = prev; return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(text); text = next; return true
    }

    // ── find / replace ───────────────────────────────────────────────────

    /** Every match of [query] as an offset range; empty for an empty or invalid query. */
    fun find(query: String, caseSensitive: Boolean = false, regex: Boolean = false): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val pattern = try {
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            if (regex) Regex(query, opts) else Regex(Regex.escape(query), opts)
        } catch (e: IllegalArgumentException) { return emptyList() }
        return pattern.findAll(text).filter { it.value.isNotEmpty() }.map { it.range }.toList()
    }

    /** The match after [fromOffset], wrapping to the first one; null when there is none. */
    fun findNext(query: String, fromOffset: Int, caseSensitive: Boolean = false, regex: Boolean = false): IntRange? {
        val all = find(query, caseSensitive, regex)
        return all.firstOrNull { it.first >= fromOffset } ?: all.firstOrNull()
    }

    fun findPrevious(query: String, beforeOffset: Int, caseSensitive: Boolean = false, regex: Boolean = false): IntRange? {
        val all = find(query, caseSensitive, regex)
        return all.lastOrNull { it.first < beforeOffset } ?: all.lastOrNull()
    }

    /** Replace one range (one undo step). */
    fun replace(range: IntRange, replacement: String) {
        edit(text.substring(0, range.first) + replacement + text.substring(range.last + 1))
    }

    /** Replace every match in ONE undo step; returns how many. `$1` groups work in regex mode. */
    fun replaceAll(query: String, replacement: String, caseSensitive: Boolean = false, regex: Boolean = false): Int {
        val matches = find(query, caseSensitive, regex)
        if (matches.isEmpty()) return 0
        val pattern = if (regex) Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                      else Regex(Regex.escape(query), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        edit(if (regex) pattern.replace(text, replacement) else pattern.replace(text) { replacement })
        return matches.size
    }

    // ── geometry ─────────────────────────────────────────────────────────

    val lineCount: Int get() = text.count { it == '\n' } + 1

    /** 1-based line of [offset]. */
    fun lineOf(offset: Int): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /** 1-based column of [offset]. */
    fun columnOf(offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', o - 1) + 1
        return o - lineStart + 1
    }

    /** Offset of the start of 1-based [line], clamped to the last line. */
    fun offsetOfLine(line: Int): Int {
        if (line <= 1) return 0
        var current = 1; var i = 0
        while (i < text.length && current < line) { if (text[i] == '\n') current++; i++ }
        return i
    }

    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotEmpty() }
}
