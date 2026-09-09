// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.widget.Toast
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SuperApp addition — "Text Enhancements": send the selection (or the whole field)
 * through [AiRouter] with the configured style prompt and replace it with the reply.
 * Triggered by the ENHANCE toolbar key; [GrammarChecker] mode "ai" reuses it with the
 * "grammar" style.
 *
 * What gets rewritten is [Settings.PREF_ENHANCE_SCOPE]: "auto" (the selection when there
 * is one, otherwise the whole field), "selection", or "field".
 *
 * Interaction = GrammarChecker's family: the improved text is committed in place and
 * carries a [SuggestionSpan] holding the ORIGINAL, so the field underlines it and a tap
 * offers the one-tap revert. Progress/failure are Toasts (the IME has no other surface
 * that survives the field losing focus).
 *
 * Cancel-on-new-input: each run takes a sequence number; a reply is dropped if a newer
 * run started, or if the field text changed while the model was thinking.
 */
object TextEnhancer {
    private const val TAG = "TextEnhancer"

    // Cached rather than single-threaded: a run stuck in a provider's read timeout must not
    // hold the next one in a queue behind it — that is what made the key look dead for
    // half a minute after one bad call.
    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()

    /** Values of [Settings.PREF_ENHANCE_SCOPE]; see [Defaults.PREF_ENHANCE_SCOPE]. */
    private enum class Scope { AUTO, SELECTION, FIELD }

    private fun scope(context: Context) =
        when (context.prefs().getString(Settings.PREF_ENHANCE_SCOPE, Defaults.PREF_ENHANCE_SCOPE)) {
            "selection" -> Scope.SELECTION
            "field" -> Scope.FIELD
            else -> Scope.AUTO
        }

    /**
     * The stretch of the field one run rewrites, in the editor's own coordinates, plus its
     * text. Resolving the range UP FRONT is what makes the apply deterministic: the old code
     * re-derived it from the cursor at commit time and silently left the text after the cursor
     * behind whenever the cursor position was unknown, duplicating the tail.
     *
     * [start]/[end] are negative when the editor never told us where the cursor is; only a
     * [fromSelection] target can still be applied then, because commitText replaces a
     * selection without needing coordinates.
     */
    class Target(val text: String, val start: Int, val end: Int, val fromSelection: Boolean) {
        fun sameAs(other: Target?) = other != null && other.text == text && other.start == start && other.end == end
        /** Whether the editor gave real coordinates, which is what a later write-back needs. */
        val addressable get() = start >= 0 && end >= start
    }

    /** What the ENHANCE key would rewrite right now — [EnhanceBarView] shows and applies this. */
    @JvmStatic
    fun target(context: Context, connection: RichInputConnection): Target? {
        connection.finishComposingText()
        return target(connection, scope(context))
    }

    /**
     * One extraction gives the whole field and where the selection sits in it, so both the
     * text and its range come from the editor's own coordinates. Nothing here stops at a
     * line break: what stopped short before was the per-side character cap of the
     * cursor-relative calls, which is now only the fallback below.
     */
    private fun target(connection: RichInputConnection, scope: Scope): Target? {
        val extracted = connection.getExtractedText()
        val whole = extracted?.text?.toString() ?: return targetAroundCursor(connection, scope)
        val base = extracted.startOffset
        val lo = minOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, whole.length)
        val hi = maxOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, whole.length)
        val selected = whole.substring(lo, hi)
        if (scope != Scope.FIELD && selected.isNotBlank()) return Target(selected, base + lo, base + hi, true)
        if (scope == Scope.SELECTION) return null
        return Target(whole, base, base + whole.length, false)
    }

    /**
     * For editors that refuse extraction: the window the cursor-relative calls can reach.
     * getTextBeforeCursor stops at the selection start and getTextAfterCursor resumes at the
     * selection end, so before + selected + after is the field as far as each cap allows; the
     * range is derived from the very same lengths, so what is replaced is exactly what was sent.
     */
    private fun targetAroundCursor(connection: RichInputConnection, scope: Scope): Target? {
        // Apps that do not report the cursor leave the expected position at -1; this is the
        // engine's own repair for that, and without it the range below cannot be computed.
        connection.tryFixIncorrectCursorPosition()
        val limit = AiRouter.maxChars
        val selectionStart = connection.expectedSelectionStart
        val selectionEnd = connection.expectedSelectionEnd
        val selected = connection.getSelectedText(0)?.toString()
        if (scope != Scope.FIELD && !selected.isNullOrBlank()) return Target(selected, selectionStart, selectionEnd, true)
        if (scope == Scope.SELECTION) return null
        // A selection the editor will not hand over cannot be stitched around: the text sent
        // would have a hole where the range replaced does not, so the rewrite would overwrite
        // text the model never saw.
        if (selected == null && selectionStart != selectionEnd) return null
        val before = connection.getTextBeforeCursor(limit, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(limit, 0)?.toString() ?: ""
        return Target(before + selected.orEmpty() + after, selectionStart - before.length, selectionEnd + after.length, false)
    }

    /**
     * True when the editor still holds exactly [target] at its range, which is the one question
     * a write-back has to ask. Checked against the field itself rather than against what the
     * ENHANCE key would pick now: moving the cursor or selecting a word after generating does
     * not change what is under the rewrite.
     */
    @JvmStatic
    fun stillThere(context: Context, connection: RichInputConnection, target: Target): Boolean {
        val whole = connection.getExtractedText()?.let { it.text?.toString() to it.startOffset }
            ?: return target.sameAs(target(context, connection))
        val (text, base) = whole
        if (text == null) return false
        val lo = target.start - base
        val hi = target.end - base
        return target.addressable && lo >= 0 && hi <= text.length && hi - lo == target.text.length &&
            text.regionMatches(lo, target.text, 0, target.text.length)
    }

    /** One piece of the text sent as its own request, and the whitespace that followed it in the field. */
    class Piece(val body: String, val tail: String)

    /**
     * Cut [text] into pieces of at most [limit] chars, preferring a paragraph break, then a line
     * break, then a sentence end, then a space, and only then a hard cut (never inside a surrogate
     * pair). The whitespace at each cut stays OUT of the request: models trim their reply, so a
     * paragraph break sent to the model comes back missing and the paragraphs fuse.
     */
    fun pieces(text: String, limit: Int): List<Piece> {
        val out = ArrayList<Piece>()
        val step = limit.coerceAtLeast(1)   // a zero budget would otherwise never advance
        var rest = text
        while (rest.isNotEmpty()) {
            val cut = if (rest.length <= step) rest.length else cutPoint(rest, step)
            val piece = rest.substring(0, cut)
            val body = piece.trimEnd()
            out.add(Piece(body, piece.substring(body.length)))
            rest = rest.substring(cut)
        }
        return out
    }

    private fun cutPoint(text: String, limit: Int): Int {
        for (separator in listOf("\n\n", "\n", ". ", " ")) {
            val at = text.lastIndexOf(separator, limit - separator.length)
            if (at <= 0) continue
            // Swallow the whole whitespace run so the next piece starts on a word: leading
            // whitespace sent to the model is trimmed from its reply and would be lost.
            var cut = at + separator.length
            while (cut < text.length && text[cut].isWhitespace()) cut++
            return cut
        }
        return if (limit > 1 && Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    }

    /**
     * The whole of [text] rewritten: one request when it fits the model's input budget, else one
     * per piece, joined back with the whitespace that separated them. [progress] gets (done,
     * total) before each request, on the calling thread. Blocking — never on the main thread.
     *
     * ponytail: pieces are rewritten independently; feed the previous piece's last sentence
     * as context if the seams ever read badly.
     */
    fun rewrite(context: Context, style: AiRouter.Style, text: String, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val lead = text.length - text.trimStart().length
        val pieces = pieces(text.substring(lead), AiRouter.maxChars)
        val out = StringBuilder(text.substring(0, lead))
        pieces.forEachIndexed { i, piece ->
            progress(i, pieces.size)
            if (piece.body.isNotEmpty()) out.append(AiRouter.complete(context, style.prompt, piece.body))
            out.append(piece.tail)
        }
        return out.toString()
    }

    @JvmStatic
    fun enhance(context: Context, connection: RichInputConnection) = run(context, connection, AiRouter.enhanceStyle(context))

    /** Whole-field / selection enhancement with an explicit [style] (GrammarChecker passes "grammar"). */
    @JvmStatic
    fun run(context: Context, connection: RichInputConnection, style: AiRouter.Style) {
        val scope = scope(context)
        val target = target(context, connection)
        if (target == null || target.text.isBlank()) {
            Log.i(TAG, "nothing to enhance: scope=$scope, target=${if (target == null) "none" else "blank"}")
            if (scope == Scope.SELECTION) toast(context, context.getString(R.string.enhance_no_selection))
            return
        }
        val id = seq.incrementAndGet()
        Log.i(TAG, "run $id: scope=$scope, selection=${target.fromSelection}, " +
                "range=${target.start}..${target.end}, chars=${target.text.length}, style=${style.id}")
        toast(context, context.getString(R.string.enhance_in_progress, AiRouter.provider(context).label))

        executor.execute {
            val improved = try {
                rewrite(context, style, target.text)
            } catch (e: AiRouter.NoTokenException) {
                Log.i(TAG, "run $id: no token for ${e.provider.id}")
                toast(context, context.getString(R.string.ai_no_token, e.provider.label)); return@execute
            } catch (e: Exception) {
                Log.e(TAG, "run $id failed", e)
                toast(context, context.getString(R.string.enhance_failed, e.message ?: e.javaClass.simpleName)); return@execute
            }
            if (id != seq.get()) return@execute // a newer run superseded this one
            if (improved.isEmpty() || improved == target.text) {
                Log.i(TAG, "run $id: reply is empty or identical")
                toast(context, context.getString(R.string.enhance_unchanged)); return@execute
            }

            main.post {
                if (id != seq.get()) return@post
                // Field changed while we waited → the reply no longer matches what the user sees.
                if (!stillThere(context, connection, target)) {
                    Log.i(TAG, "run $id: field moved while enhancing, dropped")
                    toast(context, context.getString(R.string.enhance_stale)); return@post
                }

                if (apply(context, connection, target, improved)) Log.i(TAG, "run $id: applied ${improved.length} chars")
                else {
                    Log.w(TAG, "run $id: cannot address ${target.start}..${target.end}, not applied")
                    toast(context, context.getString(R.string.enhance_no_cursor))
                }
            }
        }
    }

    /**
     * Write [replacement] over [target]'s range, keeping the original as a one-tap revert.
     * Returns false when the range cannot be addressed, so callers can say so rather than
     * silently dropping the rewrite. Main thread only.
     */
    @JvmStatic
    fun apply(context: Context, connection: RichInputConnection, target: Target, replacement: String): Boolean {
        val spanned = SpannableString(replacement).apply {
            // ponytail: the whole original as ONE revert candidate; the popup gets long for
            // long fields — split per sentence if that ever bothers anyone.
            setSpan(SuggestionSpan(context, null, arrayOf(target.text), SuggestionSpan.FLAG_EASY_CORRECT, null),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        connection.beginBatchEdit()
        // Select the range, then commit: commitText replaces the selection (InputConnection
        // contract), so one call covers both halves of the field.
        val ready = if (target.addressable) connection.setSelection(target.start, target.end) else target.fromSelection
        if (ready) connection.commitText(spanned, 1)
        connection.endBatchEdit()
        return ready
    }

    private fun toast(context: Context, msg: String) = main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}
