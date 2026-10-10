// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import com.diegonmarcos.superapp.autofill.AutofillSotClient
import helium314.keyboard.latin.autofill.FieldDecision
import helium314.keyboard.latin.autofill.FieldMode
import helium314.keyboard.latin.autofill.FieldPolicy
import helium314.keyboard.latin.autofill.ImeCandidates
import helium314.keyboard.latin.autofill.ImeRow
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.Snippet
import helium314.keyboard.latin.autofill.SuggestionRows
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.suggestions.SuggestionStripView
import java.util.concurrent.Executors

/**
 * Tier 3 of the fleet autofill, the IME half (a0_docs/eng-specs/autofill-3-tier.md). LatinIME calls it at
 * the four moments that matter:
 *
 *  - onStartInputView: [FieldPolicy] decides NORMAL or SUPPRESSED for the field; in SUPPRESSED the
 *    keyboard's own row (row 2) is hidden and InputAttributes keeps the field incognito, so nothing
 *    typed there is recorded or learnt. In NORMAL with a field the SOT knows (email, tel, postal…) or
 *    free text (snippets), the Cloud Account SOT is read OFF the main thread under
 *    AUTOFILL_PROFILE_READ.
 *  - setNeutralSuggestionStrip: while the field is empty, row 2 shows those candidates; a tap commits
 *    the text through the input connection (commitText(text, 1)) and teaches the dictionary nothing.
 *  - onInlineSuggestionsResponse: row 1 shows Vault's inline chips, alone, collapsing when there are none.
 *  - onFinishInputView: row 1 is cleared.
 *
 * Values are never logged and never kept beyond the field they were offered for.
 */
class ImeAutofillController(private val ime: LatinIME) {
    var decision: FieldDecision = FieldDecision(FieldMode.NORMAL)
        private set
    private var row: ImeRow = ImeRow.EMPTY
    private var profiles: List<AutofillProfile> = emptyList()
    private var snippets: List<Snippet> = emptyList()
    private var active = 0
    private var inline = 0
    private var inlineAt = 0L
    private var generation = 0
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    fun isSuppressed(): Boolean = decision.suppressed

    fun onStartInputView(info: EditorInfo?, newField: Boolean, strip: SuggestionStripView?) {
        val gen = ++generation
        val d = FieldPolicy.decide(info)
        decision = d
        row = ImeRow.EMPTY; profiles = emptyList(); snippets = emptyList(); active = 0
        // Row 1 on a NEW field: the framework may deliver that field's inline response just BEFORE the input
        // view starts, so only a response older than [STALE_MS] (the previous field's) is cleared here; an
        // empty response or onFinishInputView clears it otherwise.
        if (newField && inline > 0 && android.os.SystemClock.uptimeMillis() - inlineAt > STALE_MS) { inline = 0; strip?.setInlineSuggestions(null) }
        applyRows(strip)
        if (d.suppressed || d.key == null && !d.snippets) return
        val ctx = ime.applicationContext
        io.execute {
            if (!AutofillSotClient.installed(ctx) || !AutofillSotClient.canRead(ctx)) return@execute
            val ps = if (d.key != null) AutofillSotClient.profiles(ctx) else emptyList()
            val sn = if (d.snippets) AutofillSotClient.snippets(ctx) else emptyList()
            val r = ImeCandidates.row(d, ps, sn)
            ui.post {
                if (gen != generation || r.isEmpty) return@post
                profiles = ps; snippets = sn; row = r
                applyRows(ime.mSuggestionStripViewOrNull())
                if (fieldIsEmpty()) ime.setNeutralSuggestionStrip()
            }
        }
    }

    /**
     * From setNeutralSuggestionStrip: row 2 shows, while the field is still empty, "<profile> ▾" (when more
     * than one profile has a value — a tap switches profile), that profile's values, then snippet labels.
     */
    fun showCandidates(strip: SuggestionStripView?): Boolean {
        if (strip == null || decision.suppressed || row.isEmpty || !fieldIsEmpty()) return false
        val chips = ArrayList<Pair<String, () -> Unit>>()
        if (row.switcher) chips += (row.profiles[row.active] + " ▾") to {
            active = row.active + 1; row = ImeCandidates.row(decision, profiles, snippets, active); showCandidates(ime.mSuggestionStripViewOrNull()); Unit
        }
        row.values.forEach { v -> chips += v to { commit(v) } }
        row.snippets.forEach { s -> chips += ("✎ " + s.label.ifBlank { s.text.lineSequence().first().take(20) }) to { commit(s.text) } }
        strip.setAutofillCandidates(chips)
        return true
    }

    private fun fieldIsEmpty(): Boolean {
        val c = ime.mInputLogic.mConnection
        return c.getCodePointBeforeCursor() == Constants.NOT_A_CODE && !c.hasSelection()
    }

    private fun commit(text: String) {
        if (decision.suppressed) return
        val c = ime.mInputLogic.mConnection
        c.beginBatchEdit()
        c.commitText(text, 1)
        c.endBatchEdit()
        row = ImeRow.EMPTY
        ime.setNeutralSuggestionStrip()
    }

    /** Row 1: Vault's inline suggestions ([view] null / [count] 0 = none: the row collapses). */
    fun onInline(view: View?, count: Int, strip: SuggestionStripView?) {
        inline = if (view == null) 0 else count
        inlineAt = android.os.SystemClock.uptimeMillis()
        strip?.setInlineSuggestions(view)
        applyRows(strip)
    }

    fun onFinishInputView(strip: SuggestionStripView?) {
        inline = 0
        row = ImeRow.EMPTY
        strip?.setInlineSuggestions(null)
    }

    private fun applyRows(strip: SuggestionStripView?) {
        val rows = SuggestionRows.decide(inline, decision.mode, row.values.size + row.snippets.size)
        strip?.setOwnCandidatesSuppressed(!rows.row2)
    }

    private companion object {
        const val STALE_MS = 400L
    }
}
