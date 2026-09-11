package com.diegonmarcos.cloudwriter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * The shape of a cloud-writer configuration page, and the rows it is built from.
 *
 * WHAT THIS IS A COPY OF. cloud-keyboard draws its settings with Compose — `SearchSettingsScreen`
 * over `ListPreference`, `SwitchPreference`, `TextInputPreference`, `PreferenceCategory`. This file
 * is the same four widgets and the same page shape in the plain Android views cloud-writer already
 * uses everywhere else. A row is a title, an optional grey explanation under it, and the current
 * value; tapping a list row opens a single-choice dialog, exactly as the keyboard's does.
 *
 * WHY NOT LITERALLY THE KEYBOARD'S COMPOSABLES. Two reasons and neither is taste. The first is the
 * task: importing them is the LINK that made task 209 — one screen over the other application's
 * store, nothing editable. The second is that they are not importable in any case; every one of
 * them resolves `helium314.keyboard.latin.R`, reads `SettingsActivity.settingsContainer` and writes
 * the keyboard's own device-protected preference file. Taking the widgets would have taken the
 * store with them.
 *
 * NOTHING IN THIS FILE, OR IN ANY PAGE BUILT ON IT, READS OR WRITES ANYTHING OUTSIDE THIS
 * APPLICATION. Every value goes through [WriterPrefs], which is one private SharedPreferences file
 * in cloud-writer's own data directory. There is no `createPackageContext`, no ContentProvider, no
 * `sharedUserId` and no Intent into the keyboard's settings anywhere in this application — that is
 * asserted by test-cloud-writer-settings-pages.sh, which is what makes "same" mean "same and mine".
 */
abstract class WriterSettingsActivity : AppCompatActivity() {

    /** The column every row is appended to. */
    protected lateinit var page: LinearLayout
        private set

    /** The page's own title, drawn as a heading — the theme is NoActionBar. */
    protected abstract fun pageTitle(): String

    /** Fill [page]. Called on create and again by [rebuild]. */
    protected abstract fun buildPage()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(PAD, PAD, PAD, PAD)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(page, FrameLayout.LayoutParams(MATCH, WRAP))
            }
        )
        title = pageTitle()
        addTitle(pageTitle())
        buildPage()
    }

    /**
     * Redraw the whole page.
     *
     * Used by the one setting whose choice changes what the rows below it contain: the provider on
     * AI Model Routing owns the model list and the price table, and leaving those showing the
     * previous provider's rows would offer the owner a model this provider has never heard of.
     * Every other row updates its own value line in place.
     */
    protected fun rebuild() {
        page.removeAllViews()
        addTitle(pageTitle())
        buildPage()
    }

    private fun addTitle(text: String) {
        page.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(HEADING)
                textSize = 20f
                setPadding(0, 0, 0, PAD / 2)
            }
        )
    }

    // ── the rows, one per keyboard preference widget ─────────────────────────

    /** `PreferenceCategory` — a section heading, e.g. "Correcciones". */
    protected fun category(text: String) {
        page.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(HEADING)
                textSize = 14f
                setPadding(0, PAD, 0, PAD / 4)
            }
        )
    }

    /**
     * `ListPreference` — title, explanation, current value; a tap opens a single-choice dialog.
     *
     * [items] is (label to id) in MENU ORDER, which is the registry's own order. [current] is the
     * stored id. The dialog writes through [onPick] and the value line is refreshed from the same
     * list, so what the row shows is always a label [items] actually holds — a stored id the
     * registry has dropped shows the fallback rather than an empty line.
     */
    protected fun listRow(
        title: String,
        summary: String?,
        items: List<Pair<String, String>>,
        current: String,
        fallback: String,
        onPick: (String) -> Unit,
    ) {
        val labels = items.map { it.first }
        fun labelOf(id: String) =
            items.firstOrNull { it.second == id }?.first
                ?: items.firstOrNull { it.second == fallback }?.first
                ?: items.firstOrNull()?.first.orEmpty()

        var chosen = current
        val value = valueLine(labelOf(chosen))
        val row = rowBody(title, summary, value)
        row.setOnClickListener {
            val at = items.indexOfFirst { it.second == chosen }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels.toTypedArray(), at) { dialog, which ->
                    chosen = items[which].second
                    onPick(chosen)
                    value.text = labelOf(chosen)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        page.addView(row)
    }

    /** `SwitchPreference` — title, explanation, and a switch on the right. */
    protected fun switchRow(title: String, summary: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val box = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onToggle(on) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, PAD / 3, 0, PAD / 3)
            addView(
                textBlock(title, summary),
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            addView(box)
            setOnClickListener { box.toggle() }
        }
        page.addView(row)
    }

    /** `TextInputPreference` — title, explanation, current text; a tap opens an editable dialog. */
    protected fun textRow(title: String, summary: String?, current: String, hint: String, onSet: (String) -> Unit) {
        var stored = current
        val value = valueLine(stored.ifBlank { hint })
        val row = rowBody(title, summary, value)
        row.setOnClickListener {
            val field = EditText(this).apply {
                setText(stored)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(BODY)
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(field)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    stored = field.text.toString().trim()
                    onSet(stored)
                    value.text = stored.ifBlank { hint }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        page.addView(row)
    }

    /**
     * `PromptPreview` — READ-ONLY text the page shows but does not let you edit, with a Copy
     * button.
     *
     * [text] must be handed in ALREADY COMPOSED by the same function the run calls. Re-assembling
     * it here would be a second copy of the composition rule, and the day the two disagree this
     * row is lying about what the application sends — which is the whole reason the row exists.
     *
     * RETURNS the view holding [text] so a page can re-point it when one of the settings that
     * built it changes. Text Enhancements does exactly that: pick another tone and the preview is
     * recomposed from [WriterPrefs] on the spot. A preview that only updated on reopening would be
     * indistinguishable from a hardcoded one for as long as the owner stayed on the page.
     */
    protected fun readOnlyRow(title: String, summary: String?, text: String): TextView {
        val block = textBlock(title, summary)
        val body = TextView(this).apply {
            this.text = text
            setTextColor(BODY)
            textSize = 13f
            setPadding(PAD / 3, PAD / 3, PAD / 3, PAD / 3)
            setBackgroundColor(FIELD)
            setTextIsSelectable(true)
        }
        val copy = android.widget.Button(this).apply {
            this.text = getString(R.string.enhance_test_copy)
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText(title, text))
                android.widget.Toast.makeText(this@WriterSettingsActivity, R.string.enhance_test_copied,
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        page.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, PAD / 3, 0, PAD / 3)
                addView(block)
                addView(body)
                addView(copy)
            }
        )
        return body
    }

    /** A grey sentence on its own, for the notes that say what a row on this page does NOT do. */
    protected fun note(text: String) {
        page.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(CAPTION)
                textSize = 12f
                setPadding(0, PAD / 3, 0, PAD / 3)
            }
        )
    }

    /**
     * ONE HORIZONTALLY SCROLLING TABLE: a header row and ONE ROW PER MODEL, never two.
     *
     * Eight columns do not fit a phone and the answer is to scroll, not to fold. Every cell is
     * single-line without exception — one cell wrapping is enough to take a model onto a second
     * line, which is the layout this table exists to replace.
     *
     * THE SCROLLER WRAPS THE WHOLE TABLE rather than each row, so the columns cannot drift out
     * from under their headings however far it is dragged: there is one scroll position because
     * there is one scroller. Sideways belongs to the table and vertically belongs to the page,
     * which are perpendicular, so no gesture is contested.
     *
     * [widthsDp] is each column's longest value, not a share of the screen. Widening one costs
     * nothing but a little more sideways travel.
     */
    protected fun table(headers: List<String>, rows: List<List<String>>, widthsDp: List<Int>, dimFrom: Int) {
        require(headers.size == widthsDp.size) { "a column without a width would draw at zero and vanish" }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tableRow(headers, widthsDp, dimFrom = 0))
        rows.forEach { body.addView(tableRow(it, widthsDp, dimFrom)) }
        page.addView(
            HorizontalScrollView(this).apply {
                setPadding(0, PAD / 3, 0, PAD / 3)
                addView(body, FrameLayout.LayoutParams(WRAP, WRAP))
            }
        )
    }

    private fun tableRow(cells: List<String>, widthsDp: List<Int>, dimFrom: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, PAD / 6, 0, PAD / 6)
            cells.forEachIndexed { at, text ->
                addView(
                    TextView(this@WriterSettingsActivity).apply {
                        this.text = text
                        setTextColor(if (at >= dimFrom) CAPTION else BODY)
                        textSize = 12f
                        maxLines = 1
                        setSingleLine(true)
                    },
                    LinearLayout.LayoutParams(dp(widthsDp[at]), WRAP)
                )
            }
        }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun rowBody(title: String, summary: String?, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(0, PAD / 3, 0, PAD / 3)
            addView(textBlock(title, summary))
            addView(value)
        }

    private fun textBlock(title: String, summary: String?): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@WriterSettingsActivity).apply {
                    text = title
                    setTextColor(BODY)
                    textSize = 15f
                }
            )
            if (summary != null) {
                addView(
                    TextView(this@WriterSettingsActivity).apply {
                        text = summary
                        setTextColor(CAPTION)
                        textSize = 12f
                    }
                )
            }
        }

    private fun valueLine(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(HEADING)
        textSize = 14f
        setPadding(0, PAD / 6, 0, 0)
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    protected companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PAD = 36

        // The same palette MainActivity uses, so a page and the screen that opened it read as one app.
        val BACKGROUND: Int = Color.parseColor("#0b0e14")
        val HEADING: Int = Color.parseColor("#78c8ff")
        val BODY: Int = Color.parseColor("#e6edf3")
        val CAPTION: Int = Color.parseColor("#c8d4e0")
        val FIELD: Int = Color.parseColor("#101520")
    }
}
