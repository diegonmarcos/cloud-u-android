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

    private val executor = Executors.newSingleThreadExecutor()
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
    }

    /** What the ENHANCE key would rewrite right now — [EnhanceBarView] shows and applies this. */
    @JvmStatic
    fun target(context: Context, connection: RichInputConnection): Target? {
        connection.finishComposingText()
        connection.tryFixIncorrectCursorPosition()
        return target(connection, scope(context), AiRouter.maxChars)
    }

    private fun target(connection: RichInputConnection, scope: Scope, limit: Int): Target? {
        val selectionStart = connection.expectedSelectionStart
        val selectionEnd = connection.expectedSelectionEnd
        val selected = connection.getSelectedText(0)?.toString() ?: ""
        if (scope != Scope.FIELD && selected.isNotBlank())
            return Target(selected, selectionStart, selectionEnd, true)
        if (scope == Scope.SELECTION) return null
        // Whole field: getTextBeforeCursor stops at the selection start and getTextAfterCursor
        // resumes at the selection end, so before + selected + after is the field whatever is
        // selected inside it. Both are capped at `limit`, and the range is derived from the very
        // same lengths — so on a field longer than the model's input budget we rewrite exactly
        // the window we sent, never more.
        val before = connection.getTextBeforeCursor(limit, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(limit, 0)?.toString() ?: ""
        return Target(before + selected + after, selectionStart - before.length, selectionEnd + after.length, false)
    }

    @JvmStatic
    fun enhance(context: Context, connection: RichInputConnection) = run(context, connection, AiRouter.enhanceStyle(context))

    /** Whole-field / selection enhancement with an explicit [style] (GrammarChecker passes "grammar"). */
    @JvmStatic
    fun run(context: Context, connection: RichInputConnection, style: AiRouter.Style) {
        connection.finishComposingText()
        // Apps that do not report the cursor leave the expected position at -1; this is the
        // engine's own repair for that, and without it the range below cannot be computed.
        connection.tryFixIncorrectCursorPosition()
        val limit = AiRouter.maxChars
        val scope = scope(context)
        val target = target(connection, scope, limit)
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
                AiRouter.complete(context, style.prompt, target.text)
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
                if (!target.sameAs(target(connection, scope, limit))) {
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
        val selectable = target.start >= 0 && target.end >= target.start
        val ready = if (selectable) connection.setSelection(target.start, target.end) else target.fromSelection
        if (ready) connection.commitText(spanned, 1)
        connection.endBatchEdit()
        return ready
    }

    private fun toast(context: Context, msg: String) = main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}
