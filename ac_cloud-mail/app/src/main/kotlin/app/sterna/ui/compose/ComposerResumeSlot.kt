package app.sterna.ui.compose

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.linkUrlFromHref
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The composer's body, parked on disk instead of in the activity's saved-state parcel. Two
 */
class ComposerResumeSlot(private val file: File) {

    /** What one saved composer holds. Text is handed back exactly as it went in, never hashed. */
    data class Resume(
        val body: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val baseline: String,
        /** The body's inline styling (#131), per family; empty for a plain body or a `/1` file. */
        val ranges: Map<Inline, List<Span>>,
        /** The baseline's styling, compared by the leave guard exactly like its text. */
        val baselineRanges: Map<Inline, List<Span>>,
        /**
         * The body's list blocks (#131), by line. Empty for a body with none, for a file whose
         * styling line carries no block section, and for a `/1` one.
         */
        val blocks: List<Block> = emptyList(),
        /** The baseline's blocks: a reopened list is not an edit, so the baseline carries it too. */
        val baselineBlocks: List<Block> = emptyList(),
        /**
         * The body's links (#131). Empty for a body with none, for a file whose styling line
         * carries no link section, and for a `/1` one.
         */
        val links: List<Link> = emptyList(),
        /** The baseline's links, compared by the leave guard exactly like its text. */
        val baselineLinks: List<Link> = emptyList(),
    )

    /**
     * Parks [body] (caret at [selectionStart]..[selectionEnd]) and [baseline] under [token], and says
     */
    fun write(
        token: String,
        body: String,
        selectionStart: Int,
        selectionEnd: Int,
        baseline: String,
        ranges: Map<Inline, List<Span>> = emptyMap(),
        baselineRanges: Map<Inline, List<Span>> = emptyMap(),
        blocks: List<Block> = emptyList(),
        baselineBlocks: List<Block> = emptyList(),
        links: List<Link> = emptyList(),
        baselineLinks: List<Link> = emptyList(),
    ): Boolean {
        val tmp = tempFile()
        return try {
            file.parentFile?.mkdirs()
            tmp.outputStream().bufferedWriter(Charsets.UTF_8).use { out ->
                out.write(header(token, selectionStart, selectionEnd, body.length, baseline.length))
                // Normalised through RichBody, so what is written is always within the text.
                out.write(
                    encodeRanges(
                        RichBody(body, ranges, blocks, links),
                        RichBody(baseline, baselineRanges, baselineBlocks, baselineLinks),
                    ),
                )
                out.write("\n")
                out.write(body)
                out.write(baseline)
            }
            // `false`, and it matters as much as the `catch` below: a refused rename reported as
            // success hands back a token for a file that is not there, the inline fallback is never
            // reached, and the composer comes back EMPTY at any size.
            if (tmp.renameTo(file)) true else { tmp.delete(); false }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /** Reads back what [write] parked under [token], or `null` when the file is missing, unreadable,
     * truncated, or was written under a DIFFERENT token. A successful read ERASES the file — a
     *  resume is consumed once — while a refused read leaves it alone, the matching token still
     *  possibly about to ask for it. */
    fun read(token: String): Resume? = try {
        if (!file.isFile) null else parse(file.readText(Charsets.UTF_8), token)?.also { file.delete() }
    } catch (e: Exception) {
        null
    }

    /** Erases THIS slot and any temporary beside it. Never throws: callers are leaving the screen. */
    fun clear() {
        try {
            file.delete()
            tempFile().delete()
        } catch (e: Exception) {
            // Nothing to do and nothing to say: the caller is on its way out of the composer.
        }
    }

    // -- the format on disk -------------------------------------------------------------------
    //     sterna-composer-resume/4 <token> <selStart> <selEnd> <bodyChars> <baselineChars>\n

    private fun header(token: String, start: Int, end: Int, bodyLen: Int, baseLen: Int): String =
        "$MAGIC $token $start $end $bodyLen $baseLen\n"

    private fun parse(text: String, token: String): Resume? {
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        val head = text.substring(0, newline).split(' ')
        if (head.size != 6 || head[0] !in READABLE_MAGIC) return null
        // The token, and the whole point of it: this file belongs to some OTHER saved composer.
        if (head[1] != token) return null
        val start = head[2].toIntOrNull() ?: return null
        val end = head[3].toIntOrNull() ?: return null
        val bodyLen = head[4].toIntOrNull() ?: return null
        val baseLen = head[5].toIntOrNull() ?: return null
        if (bodyLen < 0 || baseLen < 0) return null
        val styling: DecodedStyling
        val rest: String
        if (head[0] != MAGIC_V1) {
            val second = text.indexOf('\n', newline + 1)
            if (second < 0) return null
            val line = text.substring(newline + 1, second)
            // The shape comes from the line itself, not from the version alone: two builds wrote a
            // `/3`. An unknown shape refuses the record, like an unreadable span.
            val shape = stylingShapeOf(head[0], line) ?: return null
            styling = decodeRanges(line, bodyLen, baseLen, shape) ?: return null
            rest = text.substring(second + 1)
        } else {
            styling = DecodedStyling()
            rest = text.substring(newline + 1)
        }
        // The verification the header exists for: a file cut short by a crash or a full disk has
        // fewer characters than it promised, and is refused whole rather than handed back short.
        if (rest.length != bodyLen + baseLen) return null
        return Resume(
            rest.substring(0, bodyLen), start, end, rest.substring(bodyLen),
            styling.ranges, styling.baselineRanges, styling.blocks, styling.baselineBlocks,
            styling.links, styling.baselineLinks,
        )
    }

    private fun tempFile(): File = File(file.parentFile, file.name + ".tmp")

    companion object {
        /** Erases EVERY composer slot in [dir], and only those. Sweeping them all is SAFER than
         *  sweeping one name: it is called from a launch with no saved state, where every file present
         *  is the orphan of a crash, and a sweep knowing one name would leave the others' cleartext
         *  under `filesDir`. */
        fun sweep(dir: File) {
            try {
                dir.listFiles()?.forEach { if (it.name.startsWith(COMPOSER_SLOT_PREFIX)) it.delete() }
            } catch (e: Exception) {
                // A launch is not the place to fail over housekeeping.
            }
        }

        /** The only header ever WRITTEN: four families, two block groups, one link section. */
        private const val MAGIC = "sterna-composer-resume/4"

        /**
         * AMBIGUOUS ON PURPOSE: two builds of #131 shipped a `/3` and wrote different lines under
         * it, so this number alone says nothing about the shape. Read, never written.
         */
        private const val MAGIC_V3 = "sterna-composer-resume/3"

        /** #131 before either of them: a styling line of four groups per side. Read, never written. */
        private const val MAGIC_V2 = "sterna-composer-resume/2"

        /** The format before #131: no styling line at all. Read, never written. */
        private const val MAGIC_V1 = "sterna-composer-resume/1"

        /** Written: [MAGIC] only. Read: the three older ones too, or a composer parked by the
         *  build being replaced comes back empty on the first rotation after the update. */
        private val READABLE_MAGIC = setOf(MAGIC, MAGIC_V3, MAGIC_V2, MAGIC_V1)

        /** The shape [line] was written in UNDER [magic], or `null` — the FILE's face of the decision.
         *  [stylingShapeOfLine] reads the shape; this one checks that header and shape are a pair some
         *  build actually wrote together, which no `/3` alone can say. */
        internal fun stylingShapeOf(magic: String, line: String): StylingShape? {
            val shape = stylingShapeOfLine(line) ?: return null
            val written = when (magic) {
                MAGIC -> shape == StylingShape.FAMILIES_BLOCKS_LINKS
                // The one header two builds wrote different lines under, and the only reason
                // [stylingShapeOfLine] exists as a decision of its own.
                MAGIC_V3 -> shape == StylingShape.FAMILIES_BLOCKS || shape == StylingShape.FAMILIES_LINKS
                MAGIC_V2 -> shape == StylingShape.FAMILIES
                else -> false
            }
            return if (written) shape else null
        }
    }
}

/**
 * The composer's body and baseline, held as ordinary Compose state so the ~71 sites that read and
 * write them do not change at all, plus the one flag that decides whether they get parked.
 */
class ComposerResumeState(
    body: TextFieldValue,
    baseline: String,
    /** The body's inline styling (#131). Held beside the text, not inside the `TextFieldValue`. */
    ranges: Map<Inline, List<Span>> = emptyMap(),
    /** The baseline's styling: the leave guard compares text AND styling against it. */
    baselineRanges: Map<Inline, List<Span>> = emptyMap(),
    /** The body's list blocks (#131), held beside the text exactly like the spans. */
    blocks: List<Block> = emptyList(),
    /** The baseline's blocks: reopening a draft that HELD a list is not an edit. */
    baselineBlocks: List<Block> = emptyList(),
    /** The body's links (#131), held beside the text exactly like the spans. */
    links: List<Link> = emptyList(),
    /** The baseline's links: the leave guard compares text, styling, blocks AND links against it. */
    baselineLinks: List<Link> = emptyList(),
    /**
     * Names the record this composer parks, minted ONCE — here — never per write.
     */
    val token: String = newComposerToken(),
    /** Whether THIS composer has already lost a body on its way into a parcel (see
     * [saveComposerResume]). A `val`, and that is its whole design: raised at construction, with
     *  nowhere to be lowered. What comes back after a loss is NOT what was lost, so a flag that could
     *  fall would be clean one rotation later and the save after that would destroy the server copy. */
    val bodyWasLost: Boolean = false,
) {
    val bodyState: MutableState<TextFieldValue> = mutableStateOf(body)
    val baselineState: MutableState<String> = mutableStateOf(baseline)
    val rangesState: MutableState<Map<Inline, List<Span>>> = mutableStateOf(ranges)
    val baselineRangesState: MutableState<Map<Inline, List<Span>>> = mutableStateOf(baselineRanges)
    val blocksState: MutableState<List<Block>> = mutableStateOf(blocks)
    val baselineBlocksState: MutableState<List<Block>> = mutableStateOf(baselineBlocks)
    val linksState: MutableState<List<Link>> = mutableStateOf(links)
    val baselineLinksState: MutableState<List<Link>> = mutableStateOf(baselineLinks)

    /** Raised when the composer closes FOR GOOD. Do not simplify this into a bare `clear()` at the
     *  exit: `SaveableStateProvider` saves a navigation entry as it LEAVES the composition, so a
     *  `clear()` there is followed by a re-WRITE of the file at teardown, leaving the user's cleartext
     *  on disk. The flag makes the saver return `null` and write nothing. */
    var closed: Boolean = false

    /** The composer is done: nothing more gets parked, and what was parked goes away now. */
    fun close(slot: ComposerResumeSlot) {
        closed = true
        slot.clear()
    }
}

/**
 * THE DECISION, on the way out. Two forms carry the text, and a third mark carries a LOSS:
 */
internal fun saveComposerResume(state: ComposerResumeState, slot: ComposerResumeSlot): String? {
    if (state.closed) return null
    val body = state.bodyState.value
    val baseline = state.baselineState.value
    val parked = slot.write(
        token = state.token,
        body = body.text,
        selectionStart = body.selection.start,
        selectionEnd = body.selection.end,
        baseline = baseline,
        ranges = state.rangesState.value,
        baselineRanges = state.baselineRangesState.value,
        blocks = state.blocksState.value,
        baselineBlocks = state.baselineBlocksState.value,
        links = state.linksState.value,
        baselineLinks = state.baselineLinksState.value,
    )
    // The styling line travels in the parcel with the text, so it counts against the bound.
    val encoded = encodeRanges(
        RichBody(body.text, state.rangesState.value, state.blocksState.value, state.linksState.value),
        RichBody(
            baseline, state.baselineRangesState.value, state.baselineBlocksState.value,
            state.baselineLinksState.value,
        ),
    )
    val carried = when {
        parked -> TOKEN_FORM + state.token
        body.text.length + baseline.length + encoded.length <= INLINE_LIMIT ->
            INLINE_FORM + "${body.selection.start}:${body.selection.end}:${body.text.length}:" +
                encoded + ":" + body.text + baseline
        // Nothing can be carried: the disk refused and the text is over the bound. This is the
        // loss itself, and the ONLY thing left to do about it is to say so.
        else -> null
    }
    // Never `null` from here on — the one `null` this function still returns is `state.closed`
    // above. A parcel with no value restores no state, and a restore that yields no state takes the
    // flag down with it.
    if (state.bodyWasLost || carried == null) return LOST_FORM + carried.orEmpty()
    return carried
}

/**
 * THE DECISION, on the way back: rebuild the composer's text, caret and baseline — or, when the
 * body was lost, rebuild a composer that KNOWS it, which is not the same thing as nothing.
 */
internal fun restoreComposerResume(saved: String, slot: ComposerResumeSlot): ComposerResumeState? {
    val lost = saved.startsWith(LOST_FORM)
    val form = if (lost) saved.substring(LOST_FORM.length) else saved
    val resume = when {
        form.startsWith(TOKEN_FORM) -> slot.read(form.substring(TOKEN_FORM.length))
        form.startsWith(INLINE_FORM) -> parseInlineResume(form)
        // Anything else is not ours: a value from an older build, a truncated one, or nothing at
        // all. A fresh composer is the only honest answer, and it is never an exception.
        else -> null
    }
    // A RECORD THE PARCEL NAMES AND THAT CAME BACK `null` IS A LOSS, NOT AN ABSENCE. `t:<token>`
    // promises that a body WAS parked, and [ComposerResumeSlot.read] collapses five causes into one
    val unreadable = resume == null && (
        (form.startsWith(TOKEN_FORM) && form.length > TOKEN_FORM.length) ||
            (form.startsWith(INLINE_FORM) && form.length > INLINE_FORM.length)
        )
    // `null` ONLY when there was no mark either. With the mark there, returning `null` would drop
    // the loss along with the text: `rememberSaveable` falls back to its factory, the composer comes
    // back empty AND clean, and the next save destroys the server copy.
    if (resume == null && !lost && !unreadable) return null
    // A name of its own, not the one just read: `read` has CONSUMED the file, so the composer
    // rebuilt here parks a new record — see [ComposerResumeState.token].
    return ComposerResumeState(
        TextFieldValue(
            resume?.body.orEmpty(),
            TextRange(resume?.selectionStart ?: 0, resume?.selectionEnd ?: 0),
        ),
        resume?.baseline.orEmpty(),
        ranges = resume?.ranges.orEmpty(),
        baselineRanges = resume?.baselineRanges.orEmpty(),
        blocks = resume?.blocks.orEmpty(),
        baselineBlocks = resume?.baselineBlocks.orEmpty(),
        links = resume?.links.orEmpty(),
        baselineLinks = resume?.baselineLinks.orEmpty(),
        bodyWasLost = lost || unreadable,
    )
}

/** Reads `v:<start>:<end>:<bodyChars>:<styling>:<body><baseline>`; only the first four `:` are
 *  separators (the styling line has none), so a body full of colons, newlines or surrogate pairs
 *  cannot be split in the wrong place. */
private fun parseInlineResume(saved: String): ComposerResumeSlot.Resume? {
    val at = INLINE_FORM.length
    val a = saved.indexOf(':', at)
    if (a < 0) return null
    val b = saved.indexOf(':', a + 1)
    if (b < 0) return null
    val c = saved.indexOf(':', b + 1)
    if (c < 0) return null
    val d = saved.indexOf(':', c + 1)
    if (d < 0) return null
    val start = saved.substring(at, a).toIntOrNull() ?: return null
    val end = saved.substring(a + 1, b).toIntOrNull() ?: return null
    val bodyLen = saved.substring(b + 1, c).toIntOrNull() ?: return null
    val payload = saved.substring(d + 1)
    if (bodyLen < 0 || bodyLen > payload.length) return null
    // THE PARCEL CARRIES OLD FORMS TOO: a parked composer CROSSES an update — the disk write fails,
    // the body travels in the parcel, the app is updated, and the process comes back on a saved state
    // written by the build before. Read with the current shape nailed on, that line is refused, and a
    // refused `v:` is the worst outcome. So the shape is read off the LINE, as the file does it.
    val stylingLine = saved.substring(c + 1, d)
    val shape = stylingShapeOfLine(stylingLine) ?: return null
    val styling = decodeRanges(
        stylingLine, bodyLen, payload.length - bodyLen, shape,
    ) ?: return null
    return ComposerResumeSlot.Resume(
        payload.substring(0, bodyLen), start, end, payload.substring(bodyLen),
        styling.ranges, styling.baselineRanges, styling.blocks, styling.baselineBlocks,
        styling.links, styling.baselineLinks,
    )
}

// -- the styling line (#131) ------------------------------------------------------------------

private val LETTER_OF = mapOf(Inline.BOLD to 'b', Inline.ITALIC to 'i', Inline.UNDERLINE to 'u', Inline.STRIKE to 's')

/** Upper case, so a block group can never be mistaken for an inline one by a sloppy parser. */
private val BLOCK_LETTER_OF = mapOf(BlockKind.BULLET to 'U', BlockKind.NUMBER to 'O')

/** The one section that is not a family and not a block: the links. */
private const val LINK_LETTER = 'a'

/**
 * WHICH sections one side of a styling line holds, in the one order they are ever written: the four
 */
internal enum class StylingShape(val sections: Int, val hasBlocks: Boolean, val hasLinks: Boolean) {
    /** `b;i;u;s` — a `/2` line, written before either the lists or the links existed. */
    FAMILIES(Inline.entries.size, false, false),

    /** `b;i;u;s;U;O` — a `/3` line as the LISTS build wrote it. */
    FAMILIES_BLOCKS(Inline.entries.size + BlockKind.entries.size, true, false),

    /** `b;i;u;s;a` — a `/3` line as the LINKS build wrote it. The two never met until now. */
    FAMILIES_LINKS(Inline.entries.size + 1, false, true),

    /** `b;i;u;s;U;O;a` — the `/4` line, the only one this build writes. */
    FAMILIES_BLOCKS_LINKS(Inline.entries.size + BlockKind.entries.size + 1, true, true),
}

/**
 * The shape a styling line was written in, from the LINE ALONE — no header needed, and the parcel has
 */
internal fun stylingShapeOfLine(line: String): StylingShape? {
    val sections = line.substringBefore('|').split(';')
    val opens = { index: Int, letter: Char -> sections.getOrNull(index)?.firstOrNull() == letter }
    val bullet = BLOCK_LETTER_OF.getValue(BlockKind.BULLET)
    val number = BLOCK_LETTER_OF.getValue(BlockKind.NUMBER)
    val families = Inline.entries.size
    return when {
        sections.size == StylingShape.FAMILIES.sections -> StylingShape.FAMILIES
        sections.size == StylingShape.FAMILIES_LINKS.sections && opens(families, LINK_LETTER) ->
            StylingShape.FAMILIES_LINKS
        sections.size == StylingShape.FAMILIES_BLOCKS.sections &&
            opens(families, bullet) && opens(families + 1, number) -> StylingShape.FAMILIES_BLOCKS
        sections.size == StylingShape.FAMILIES_BLOCKS_LINKS.sections &&
            opens(families, bullet) && opens(families + 1, number) && opens(families + 2, LINK_LETTER) ->
            StylingShape.FAMILIES_BLOCKS_LINKS
        else -> null
    }
}

/**
 * `b0-4,9-12;i;u;s;U1-3;O;a2-6-https%3A%2F%2Fx|b;i;u;s;U;O;a`: the body's four inline families in
 */
internal fun encodeRanges(body: RichBody, baseline: RichBody): String =
    encodeSide(body) + "|" + encodeSide(baseline)

private fun encodeSide(rich: RichBody): String =
    Inline.entries.joinToString(";") { kind ->
        LETTER_OF.getValue(kind) + rich.ranges[kind].orEmpty().joinToString(",") { "${it.start}-${it.end}" }
    } + BlockKind.entries.joinToString("") { kind ->
        ";" + BLOCK_LETTER_OF.getValue(kind) +
            rich.blocks.filter { it.kind == kind }.joinToString(",") { "${it.lines.first}-${it.lines.last}" }
    } + ";" + LINK_LETTER +
    rich.links.joinToString(",") { "${it.span.start}-${it.span.end}-" + encodeLinkUrl(it.url) }

/**
 * Percent-encoding of everything outside `A-Za-z0-9._~`, over the UTF-8 bytes — the `-` included,
 */
private fun encodeLinkUrl(url: String): String {
    val out = StringBuilder(url.length + 8)
    for (byte in url.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '~') {
            out.append(c)
        } else {
            out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
    }
    return out.toString()
}

/** The strict inverse: an unreserved character, or `%` and two hex digits. Anything else, `null`. */
private fun decodeLinkUrl(encoded: String): String? {
    val bytes = java.io.ByteArrayOutputStream(encoded.length)
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        when {
            c == '%' -> {
                val high = encoded.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
                val low = encoded.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
                bytes.write(high * 16 + low)
                i += 3
            }
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '~' -> {
                bytes.write(c.code)
                i++
            }
            // A raw separator, or anything else this encoder never writes: the record is refused.
            else -> return null
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

/** What one styling line says, for the body and for the baseline. */
internal data class DecodedStyling(
    val ranges: Map<Inline, List<Span>> = emptyMap(),
    val baselineRanges: Map<Inline, List<Span>> = emptyMap(),
    val blocks: List<Block> = emptyList(),
    val baselineBlocks: List<Block> = emptyList(),
    val links: List<Link> = emptyList(),
    val baselineLinks: List<Link> = emptyList(),
)

/**
 * STRICT inverse of [encodeRanges], in the [shape] the caller has identified: exactly two parts, then
 */
internal fun decodeRanges(
    line: String,
    bodyLength: Int,
    baselineLength: Int,
    shape: StylingShape,
): DecodedStyling? {
    val parts = line.split('|')
    if (parts.size != 2) return null
    val body = decodeSide(parts[0], bodyLength, shape) ?: return null
    val baseline = decodeSide(parts[1], baselineLength, shape) ?: return null
    return DecodedStyling(
        body.ranges, baseline.ranges, body.blocks, baseline.blocks, body.links, baseline.links,
    )
}

/** One side of a styling line, decoded. */
private class DecodedSide(
    val ranges: Map<Inline, List<Span>>,
    val blocks: List<Block>,
    val links: List<Link>,
)

private fun decodeSide(text: String, length: Int, shape: StylingShape): DecodedSide? {
    val sections = text.split(';')
    if (sections.size != shape.sections) return null
    val ranges = decodeFamilies(sections.take(Inline.entries.size), length) ?: return null
    val blocks =
        if (shape.hasBlocks) {
            decodeBlocks(sections.subList(Inline.entries.size, Inline.entries.size + BlockKind.entries.size), length)
                ?: return null
        } else {
            emptyList()
        }
    val links = if (shape.hasLinks) (decodeLinks(sections.last(), length) ?: return null) else emptyList()
    return DecodedSide(ranges, blocks, links)
}

private fun decodeFamilies(families: List<String>, length: Int): Map<Inline, List<Span>>? {
    val out = LinkedHashMap<Inline, List<Span>>()
    for ((kind, family) in Inline.entries.zip(families)) {
        if (family.isEmpty() || family[0] != LETTER_OF.getValue(kind)) return null
        val spans = family.substring(1)
        if (spans.isEmpty()) continue
        out[kind] = spans.split(',').map { pair ->
            val dash = pair.indexOf('-')
            if (dash < 0) return null
            val start = pair.substring(0, dash).toIntOrNull() ?: return null
            val end = pair.substring(dash + 1).toIntOrNull() ?: return null
            if (start < 0 || start > end || end > length) return null
            Span(start, end)
        }
    }
    return out
}

private fun decodeBlocks(groups: List<String>, length: Int): List<Block>? {
    val blocks = ArrayList<Block>()
    for ((kind, group) in BlockKind.entries.zip(groups)) {
        if (group.isEmpty() || group[0] != BLOCK_LETTER_OF.getValue(kind)) return null
        val runs = group.substring(1)
        if (runs.isEmpty()) continue
        for (pair in runs.split(',')) {
            val dash = pair.indexOf('-')
            if (dash < 0) return null
            val first = pair.substring(0, dash).toIntOrNull() ?: return null
            val last = pair.substring(dash + 1).toIntOrNull() ?: return null
            if (first < 0 || first > last || last > length) return null
            blocks.add(Block(kind, first..last))
        }
    }
    return blocks
}

/**
 * THE THIRD BORDER OF THE URL GUARD. What comes out of here is a `Link` the composer hands to the
 */
private fun decodeLinks(section: String, length: Int): List<Link>? {
    if (section.isEmpty() || section[0] != LINK_LETTER) return null
    val body = section.substring(1)
    if (body.isEmpty()) return emptyList()
    return body.split(',').map { record ->
        // Exactly three fields: a raw `-` inside the URL would make four, and is refused — which
        // is why the encoder percent-encodes the `-` as well.
        val fields = record.split('-')
        if (fields.size != 3) return null
        val start = fields[0].toIntOrNull() ?: return null
        val end = fields[1].toIntOrNull() ?: return null
        if (start < 0 || start > end || end > length) return null
        val url = linkUrlFromHref(decodeLinkUrl(fields[2]) ?: return null) ?: return null
        Link(Span(start, end), url)
    }
}

private val HEX = "0123456789ABCDEF".toCharArray()

/** What goes into the activity's parcel for the composer's body: a short token, and that is all. */
fun composerResumeSaver(slot: ComposerResumeSlot): Saver<ComposerResumeState, String> = Saver(
    save = { saveComposerResume(it, slot) },
    restore = { restoreComposerResume(it, slot) },
)

/** Names one composer's slot for as long as that navigation entry lives. Parcelled (a dozen
 *  bytes), so the composer rebuilt after a process death still knows which file is its own. */
fun newComposerSlotId(): String = shortId(SLOT_IDS)

/** Names one composer's parked RECORD. Distinct per composer, and the same for every save of
 *  the same one — see [ComposerResumeState.token] for the rotation that taught us the difference. */
fun newComposerToken(): String = shortId(TOKENS)

/** The file one composer uses. Through `applicationContext`, never the activity's own `Context`:
 *  this object is built inside a composition that is torn down and rebuilt, and holding the activity
 *  from a `remember` is how an activity leak starts. */
fun composerResumeFile(context: Context, slotId: String): File =
    composerResumeSlotFile(context.applicationContext.filesDir, slotId)

/** The same naming, on a plain directory, so the slot can be exercised without a `Context`. */
fun composerResumeSlotFile(dir: File, slotId: String): File = File(dir, COMPOSER_SLOT_PREFIX + slotId)

/** What [ComposerResumeSlot.sweep] recognises as ours, and nothing else in `filesDir` starts with it. */
const val COMPOSER_SLOT_PREFIX = "composer-resume-"

private const val TOKEN_FORM = "t:"
private const val INLINE_FORM = "v:"

/** The mark that says this composer lost a body. Composes with the two forms above, or stands
 *  alone when neither of them could carry anything — see [saveComposerResume]. */
private const val LOST_FORM = "l:"

/** ~64 kB of UTF-16 parcel: far under the 1 MB transaction buffer, and less than this screen was
 *  already sending before any of this existed. See [saveComposerResume] for why it is bounded. */
private const val INLINE_LIMIT = 16_384

private val SLOT_IDS = AtomicLong(0)

/** Not `Math.random()`: two composers opened in the same millisecond must not be able to collide,
 *  and a counter is the only thing that says so with certainty. */
private val TOKENS = AtomicLong(0)

/** Short, and distinct on each call within a process — which is all either of its users needs. */
private fun shortId(sequence: AtomicLong): String =
    java.lang.Long.toUnsignedString(System.nanoTime(), 36) + "-" +
        java.lang.Long.toUnsignedString(sequence.incrementAndGet(), 36)
