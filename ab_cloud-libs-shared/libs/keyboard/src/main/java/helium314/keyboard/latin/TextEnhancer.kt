// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SuperApp addition — "Text Enhancements": send the selection (or the whole field)
 * through [AiRouter] with the configured style prompt and replace it with the reply.
 * Triggered by the ENHANCE toolbar key; [GrammarChecker] mode "ai" reuses it with the
 * "grammar" style.
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
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()

    @JvmStatic
    fun enhance(context: Context, connection: RichInputConnection) = run(context, connection, AiRouter.style(context))

    /** Whole-field / selection enhancement with an explicit [style] (GrammarChecker passes "grammar"). */
    @JvmStatic
    fun run(context: Context, connection: RichInputConnection, style: AiRouter.Style) {
        connection.finishComposingText()
        val limit = AiRouter.maxChars
        val selected = connection.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val before = if (selected == null) connection.getTextBeforeCursor(limit, 0)?.toString() ?: return else ""
        val after = if (selected == null) connection.getTextAfterCursor(limit, 0)?.toString() ?: "" else ""
        val original = selected ?: (before + after)
        if (original.isBlank()) return
        val id = seq.incrementAndGet()
        toast(context, context.getString(R.string.enhance_in_progress, AiRouter.provider(context).label))

        executor.execute {
            val improved = try {
                AiRouter.complete(context, style.prompt, original)
            } catch (e: AiRouter.NoTokenException) {
                toast(context, context.getString(R.string.ai_no_token, e.provider.label)); return@execute
            } catch (e: Exception) {
                toast(context, context.getString(R.string.enhance_failed, e.message ?: e.javaClass.simpleName)); return@execute
            }
            if (id != seq.get()) return@execute // a newer run superseded this one
            if (improved.isEmpty() || improved == original) { toast(context, context.getString(R.string.enhance_unchanged)); return@execute }

            main.post {
                if (id != seq.get()) return@post
                // Field changed while we waited → the reply no longer matches what the user sees.
                val stillSame = if (selected != null) connection.getSelectedText(0)?.toString() == selected
                else connection.getTextBeforeCursor(limit, 0)?.toString() == before && (connection.getTextAfterCursor(limit, 0)?.toString() ?: "") == after
                if (!stillSame) { toast(context, context.getString(R.string.enhance_stale)); return@post }

                val replacement = SpannableString(improved).apply {
                    // ponytail: the whole original as ONE revert candidate; the popup gets long for
                    // long fields — split per sentence if that ever bothers anyone.
                    setSpan(SuggestionSpan(context, null, arrayOf(original), SuggestionSpan.FLAG_EASY_CORRECT, null),
                        0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                connection.beginBatchEdit()
                if (selected == null) {
                    val start = connection.expectedSelectionStart - before.length
                    if (after.isNotEmpty() && start >= 0) connection.setSelection(start, start + before.length + after.length)
                    else connection.deleteTextBeforeCursor(before.length)
                }
                connection.commitText(replacement, 1) // commitText replaces the selection (InputConnection contract)
                connection.endBatchEdit()
            }
        }
    }

    private fun toast(context: Context, msg: String) = main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}
