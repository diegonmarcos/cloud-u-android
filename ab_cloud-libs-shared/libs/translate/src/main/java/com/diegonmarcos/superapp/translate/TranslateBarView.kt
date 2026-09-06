package com.diegonmarcos.superapp.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Translate bar, hosted INSIDE the keyboard frame (LatinIME adds it above the
 * suggestion strip; onComputeInsets reserves its height so the host app
 * reflows up):
 *
 *   ┌───────────────────────────────────────────┐
 *   │ [AUTO (EN) ▾]   ⇄   [PT ▾]              ✕  │  language chips (tap = picker, recents first)
 *   │ type here…                                 │  input buffer (keys are routed here by LatinIME)
 *   │ tradução ao vivo                           │  live preview / status line
 *   │ [Insert] [Replace] [Copy] [Clear]          │  actions — Enter = the primary one (setting)
 *   └───────────────────────────────────────────┘
 *
 * Input model: while the bar is open, LatinIME routes printable keys +
 * backspace into [buffer]; nothing touches the app field until the user
 * applies (Insert at cursor / Replace selection-or-field / Copy). The
 * previous "live commit" model (translation written into the field as you
 * type, rewound by length on every change) is kept as an opt-in setting —
 * it breaks as soon as the host app touches its own text.
 *
 * Settings + recent pairs: [TranslatePrefs]. Engine: [Translator].
 * Lives in libs:translate so sync-heliboard's verbatim mirror never deletes it.
 */
class TranslateBarView(context: Context) : LinearLayout(context) {

    /** Supplies the current InputConnection (the IME's getCurrentInputConnection). */
    fun interface IcProvider { fun get(): InputConnection? }

    companion object {
        private val DEFAULT_LANGS = TranslatePrefs.FALLBACK_LANGS
        private const val DEBOUNCE_MS = 300L
        private const val SLOW_MS = 8000L
        private const val AUTO = Translator.AUTO
    }

    private val bg = 0xF21B1B20.toInt()
    private val chipColor = 0xFF34343F.toInt()
    private val chipPrimary = 0xFF3D5AFE.toInt()
    private val muted = 0xFFB0B0B8.toInt()
    private val hintColor = 0xFF6E6E78.toInt()

    private var icp: IcProvider? = null
    private var onClose: Runnable? = null
    private var keyboardLang = "en"
    private var fromTag = AUTO
    private var toTag = "en"
    private var detectedTag: String? = null
    private var translated: String? = null

    private val buffer = StringBuilder()
    private var lastOutput = ""          // live-commit mode: what we've committed to the app field
    private var liveCommit = TranslatePrefs.DEFAULT_LIVE_COMMIT
    private var applyMode = TranslatePrefs.DEFAULT_APPLY_MODE

    private val fromChip: TextView
    private val toChip: TextView
    private val inputView: TextView
    private val previewView: TextView
    private val insertBtn: TextView
    private val replaceBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var slow: Runnable? = null

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(8))
        setBackgroundColor(bg)

        // ── Row 1: From  ⇄  To ............................. ✕ ───────────────
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fromChip = chip("") { showLangMenu(fromChip, true) }
        toChip = chip("") { showLangMenu(toChip, false) }
        row.addView(fromChip)
        row.addView(TextView(context).apply {
            text = "  ⇄  "; setTextColor(muted); textSize = 15f
            isClickable = true; setOnClickListener { swap() }
        })
        row.addView(toChip)
        row.addView(View(context), LayoutParams(0, 1, 1f)) // spacer
        row.addView(TextView(context).apply {
            text = "✕"; setTextColor(muted); textSize = 16f
            setPadding(dp(10), dp(2), dp(4), dp(2)); isClickable = true
            setOnClickListener { onClose?.run() }
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 2: input buffer ──────────────────────────────────────────────
        inputView = TextView(context).apply {
            textSize = 16f; setTextColor(Color.WHITE); maxLines = 3; setPadding(0, dp(6), 0, 0)
        }
        addView(inputView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 3: live preview / status ─────────────────────────────────────
        previewView = TextView(context).apply {
            textSize = 15f; setTextColor(muted); maxLines = 3; setPadding(0, dp(4), 0, dp(6))
        }
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 4: actions ───────────────────────────────────────────────────
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        insertBtn = chip("Insert") { apply(TranslatePrefs.APPLY_INSERT) }
        replaceBtn = chip("Replace") { apply(TranslatePrefs.APPLY_REPLACE) }
        actions.addView(insertBtn); actions.gap()
        actions.addView(replaceBtn); actions.gap()
        actions.addView(chip("Copy") { copy() }); actions.gap()
        actions.addView(chip("Clear") { clear() })
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        renderInput(); renderChips(); highlightPrimary()
    }

    /** Wire the bar to the IME. [keyboardLang] = active subtype language (detection fallback + default target). */
    fun bind(provider: IcProvider, keyboardLang: String, onCloseAction: Runnable) {
        icp = provider
        onClose = onCloseAction
        this.keyboardLang = keyboardLang.ifEmpty { "en" }
    }

    /** Called by LatinIME each time the bar is shown — fresh session, settings re-read. */
    fun onShown() {
        buffer.setLength(0); lastOutput = ""; translated = null; detectedTag = null
        liveCommit = TranslatePrefs.liveCommit(context)
        applyMode = TranslatePrefs.applyMode(context)
        val langs = toLangs()
        toTag = TranslatePrefs.defaultTarget(context).takeIf { langs.contains(it) }
            ?: keyboardLang.takeIf { langs.contains(it) } ?: "en"
        fromTag = if (TranslatePrefs.autoDetect(context)) AUTO else (keyboardLang.takeIf { langs.contains(it) } ?: AUTO)
        if (fromTag == toTag) {
            toTag = TranslatePrefs.recentPairs(context).firstOrNull { it.second != fromTag && langs.contains(it.second) }?.second
                ?: DEFAULT_LANGS.firstOrNull { it != fromTag && langs.contains(it) } ?: toTag
        }
        // Live-commit writes straight into the field — Insert/Replace would double it.
        insertBtn.visibility = if (liveCommit) View.GONE else View.VISIBLE
        replaceBtn.visibility = insertBtn.visibility
        highlightPrimary(); renderInput(); renderChips(); showStatus("")
    }

    // ── key routing entry points (called from LatinIME.onEvent) ──────────────
    fun appendCodePoint(cp: Int) {
        if (cp == '\n'.code) { apply(applyMode); return }   // Enter = primary action
        buffer.appendCodePoint(cp); onChanged()
    }

    fun backspace() {
        if (buffer.isNotEmpty()) {
            val last = buffer.length - 1
            // drop a surrogate pair as one character
            if (last > 0 && Character.isLowSurrogate(buffer[last]) && Character.isHighSurrogate(buffer[last - 1]))
                buffer.setLength(last - 1) else buffer.setLength(last)
        }
        onChanged()
    }

    private fun onChanged() {
        renderInput()
        pending?.let { ui.removeCallbacks(it) }
        slow?.let { ui.removeCallbacks(it) }
        translated = null
        val text = buffer.toString()
        if (text.isBlank()) { showStatus(""); if (liveCommit) pushOutput(""); return }
        val job = Runnable {
            showStatus("Translating…")
            val slowJob = Runnable { showStatus("Still translating… first use downloads the language model (needs network once)") }
            slow = slowJob; ui.postDelayed(slowJob, SLOW_MS)
            Translator.liveTranslate(text, fromTag, toTag, keyboardLang) { r ->
                if (buffer.toString() != text) return@liveTranslate   // stale
                slow?.let { ui.removeCallbacks(it) }
                onResult(r)
            }
        }
        pending = job
        ui.postDelayed(job, DEBOUNCE_MS)
    }

    private fun onResult(r: Translator.Result) {
        val out = r.text
        if (out == null) { translated = null; showStatus(r.error ?: "Translate failed"); return }
        translated = out
        val det = r.detected
        if (fromTag == AUTO && det != null && det != "und") { detectedTag = det; renderChips() }
        previewView.text = out
        previewView.setTextColor(Color.WHITE)
        previewView.setTypeface(null, Typeface.NORMAL)
        if (liveCommit) pushOutput(out)
    }

    // ── actions ──────────────────────────────────────────────────────────────
    private fun apply(mode: String) {
        val out = translated
        if (out == null) { if (buffer.isNotEmpty()) toast("Wait for the translation…"); return }
        val ic = icp?.get() ?: return
        if (liveCommit) {
            lastOutput = ""   // already in the field — just end the session
        } else if (mode == TranslatePrefs.APPLY_REPLACE) {
            val hadSelection = !ic.getSelectedText(0).isNullOrEmpty()
            Translator.replaceInField(ic, hadSelection, out)
        } else {
            ic.commitText(out, 1)
        }
        TranslatePrefs.pushRecentPair(context, if (fromTag == AUTO) (detectedTag ?: AUTO) else fromTag, toTag)
        pending?.let { ui.removeCallbacks(it) }
        buffer.setLength(0); translated = null
        renderInput(); showStatus("")
    }

    private fun copy() {
        val out = translated ?: return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("translation", out))
        toast("Copied")
    }

    private fun clear() { buffer.setLength(0); onChanged() }

    private fun swap() {
        val f = if (fromTag == AUTO) (detectedTag ?: return) else fromTag
        fromTag = toTag; toTag = f; detectedTag = null
        renderChips(); onChanged()
    }

    /** Live-commit mode only: replace the previously-committed translation in the app field with [out]. */
    private fun pushOutput(out: String) {
        val ic = icp?.get() ?: return
        ic.beginBatchEdit()
        if (lastOutput.isNotEmpty()) ic.deleteSurroundingText(lastOutput.length, 0)
        if (out.isNotEmpty()) ic.commitText(out, 1)
        lastOutput = out
        ic.endBatchEdit()
    }

    // ── rendering ────────────────────────────────────────────────────────────
    private fun renderInput() {
        if (buffer.isEmpty()) {
            inputView.text = "Type to translate…"
            inputView.setTextColor(hintColor)
            inputView.setTypeface(null, Typeface.ITALIC)
        } else {
            inputView.text = buffer
            inputView.setTextColor(Color.WHITE)
            inputView.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun showStatus(msg: String) {
        previewView.text = msg
        previewView.setTextColor(hintColor)
        previewView.setTypeface(null, Typeface.ITALIC)
    }

    private fun renderChips() {
        fromChip.text = if (fromTag == AUTO) "AUTO" + (detectedTag?.let { " (${it.uppercase()})" } ?: "") + " ▾"
                        else fromTag.uppercase() + " ▾"
        toChip.text = toTag.uppercase() + " ▾"
    }

    private fun highlightPrimary() {
        insertBtn.background = chipBg(if (applyMode == TranslatePrefs.APPLY_INSERT) chipPrimary else chipColor)
        replaceBtn.background = chipBg(if (applyMode == TranslatePrefs.APPLY_REPLACE) chipPrimary else chipColor)
    }

    // ── language picker ──────────────────────────────────────────────────────
    // Every language the engine can translate — computed fresh on each read: the
    // AIDL client binds asynchronously, so a cached snapshot taken before the bind
    // would freeze the list at the fallback forever.
    private fun toLangs(): List<String> =
        (TranslateEngines.client?.supportedLanguages()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LANGS)
            .sortedBy { Locale(it).displayLanguage }

    /** Tap a chip → picker: recent pairs first (set both sides), then Auto-detect (From only), then every language. */
    private fun showLangMenu(anchor: View, isFrom: Boolean) {
        val codes = if (isFrom) listOf(AUTO) + toLangs() else toLangs()
        val recents = TranslatePrefs.recentPairs(context)
        val labels = recents.map { "${it.first.uppercase()} → ${it.second.uppercase()}   (recent)" } +
            codes.map { if (it == AUTO) "Auto-detect" else langName(it) }
        val lpw = ListPopupWindow(context)
        lpw.anchorView = anchor
        lpw.isModal = true
        lpw.width = dp(260)
        lpw.height = dp(320)
        lpw.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
        lpw.setOnItemClickListener { _, _, pos, _ ->
            if (pos < recents.size) {
                fromTag = recents[pos].first; toTag = recents[pos].second
            } else {
                val sel = codes[pos - recents.size]
                if (isFrom) fromTag = sel else toTag = sel
            }
            detectedTag = null
            lpw.dismiss(); renderChips(); onChanged()
        }
        lpw.show()
    }

    private fun langName(code: String): String =
        Locale(code).displayLanguage.let { n -> if (n.equals(code, ignoreCase = true)) code.uppercase() else "$n  ($code)" }

    // ── view helpers ─────────────────────────────────────────────────────────
    private fun chip(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f
        setPadding(dp(12), dp(3), dp(12), dp(3))
        background = chipBg(chipColor)
        isClickable = true; setOnClickListener { onTap() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }
    private fun chipBg(color: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
    private fun LinearLayout.gap() = addView(View(context), LayoutParams(dp(6), 1))
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
