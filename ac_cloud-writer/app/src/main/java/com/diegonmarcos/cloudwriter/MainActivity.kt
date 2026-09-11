package com.diegonmarcos.cloudwriter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Cloud Writer, v1 — the application the owner asked for.
 *
 * A text goes in the top box, one of four tools runs on it, the result lands in the bottom box and
 * that box is EDITABLE, because a rewrite is a suggestion and the last word belongs to whoever
 * wrote the sentence.
 *
 * WHAT THIS APPLICATION IS, PRECISELY. It ROUTES: it holds its own prompts, its own provider, its
 * own model per tool, and it sends all of that to whichever peer serves `ITextTools`. It does not
 * SERVE, it holds no provider key, and it opens no socket — see AndroidManifest.xml, where the
 * absence of a service under the ITextTools action is the most load-bearing thing in the file.
 * The status line at the top of this screen names the peer that actually answered, read out of
 * that peer's own reply rather than out of a constant here, so it cannot be wrong about which
 * application did the work.
 *
 * NO DEAD CONTROLS. Every button either does the thing or puts a sentence on screen saying why
 * not. A control that does nothing and says nothing is indistinguishable from a crash, a missing
 * permission and a network failure — which is exactly the defect the Enhance key and the three
 * translate-bar controls each shipped with once.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var runner: WriterToolRunner
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var output: EditText
    private lateinit var report: TextView
    private lateinit var modelSection: LinearLayout

    /** For the two calls that must not run on the main thread: the status probe and its label. */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runner = WriterToolRunner(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(PAD, PAD, PAD, PAD)
        }

        status = caption(getString(R.string.status_checking))
        page.addView(status)

        page.addView(heading(getString(R.string.input_label)))
        input = textBox(getString(R.string.input_hint))
        page.addView(input)

        page.addView(toolRow())

        report = caption("")
        report.visibility = View.GONE
        page.addView(report)

        page.addView(heading(getString(R.string.output_label)))
        output = textBox(getString(R.string.output_hint))
        page.addView(output)

        page.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(getString(R.string.action_copy)) { copyOutput() })
                addView(button(getString(R.string.action_clear)) {
                    input.setText("")
                    output.setText("")
                    report.visibility = View.GONE
                })
            }
        )

        page.addView(heading(getString(R.string.models_heading)))
        page.addView(caption(getString(R.string.models_note)))
        modelSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(providerRow())
        page.addView(modelSection)
        buildToolModelRows()

        page.addView(caption(getString(R.string.token_note)))

        // THE FOUR CONFIGURATION PAGES. Each opens an activity IN THIS APPLICATION over THIS
        // APPLICATION'S preference file. None of them is an Intent into Cloud Keyboard's settings,
        // and none of them reads a value the keyboard wrote — which is the difference between the
        // pages the owner asked for and the pages task 209 delivered.
        page.addView(heading(getString(R.string.settings_heading)))
        listOf(
            R.string.settings_screen_enhance to TextEnhanceActivity::class.java,
            R.string.settings_screen_translation to TranslationActivity::class.java,
            R.string.settings_screen_grammar to GrammarCheckActivity::class.java,
            R.string.settings_screen_ai_routing to AiRoutingActivity::class.java,
        ).forEach { (label, screen) ->
            page.addView(button(getString(label)) { startActivity(Intent(this, screen)) })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            // FrameLayout.LayoutParams, named for the class that declares it:
            // ScrollView inherits it rather than owning one, and a scroll child
            // that wrapped its width would draw every box a word wide.
            addView(page, FrameLayout.LayoutParams(MATCH, WRAP))
        })

        adoptSharedText(intent)
        refreshStatus()
    }

    /**
     * Text shared in from another application, or selected in one and sent here by PROCESS_TEXT.
     *
     * Read in onCreate and nowhere else, because this activity uses the DEFAULT launch mode: a
     * share starts a fresh instance and its intent arrives here. singleTask would deliver it to
     * the running instance through onNewIntent instead, which is one more override to keep
     * correct for a behaviour the owner cannot tell apart.
     */
    private fun adoptSharedText(from: Intent?) {
        if (from == null) return
        val shared = when (from.action) {
            Intent.ACTION_SEND -> from.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> from.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!shared.isNullOrBlank()) input.setText(shared)
    }

    // ── status ───────────────────────────────────────────────────────────────

    /**
     * Who is actually answering, named from THEIR reply and not from a constant here.
     *
     * `aiRoutingSnapshot()` carries `app`, the serving application's own package name, which is
     * the whole reason the fleet console can survive the tools moving house: the answer says who
     * gave it. A constant on this side would keep reading "Cloud Keyboard" on the day something
     * else began serving.
     *
     * THREE STATES, NAMED SEPARATELY, because they are three different repairs: nothing installed
     * is an install, bound-but-silent is an update of the peer, and a named peer is nothing to
     * repair at all. Reporting them as one is what sends an owner to install an application they
     * already have.
     *
     * Every call in here BLOCKS, so all of it runs on [worker].
     */
    private fun refreshStatus() {
        worker.execute {
            val installed = runner.isServingAppInstalled()
            val snapshot = if (installed) runner.aiRoutingApp() else null
            val provider = if (installed) runner.providerLabel() else null
            val line = when {
                !installed -> getString(R.string.status_no_serving_app)
                snapshot == null -> getString(R.string.status_serving_app_too_old)
                else -> getString(
                    R.string.status_served_by,
                    snapshot,
                    provider ?: getString(R.string.status_provider_unknown),
                )
            }
            main.post { status.text = line }
        }
    }

    // ── running a tool ───────────────────────────────────────────────────────

    private fun toolRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Built from the enum, so a tool added there gets a button here and cannot be forgotten.
        WriterTool.values().forEach { tool ->
            row.addView(button(getString(tool.label)) { start(tool) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /**
     * What Text Enhance is given, per "Qué se mejora" on the Text Enhancements page.
     *
     * The input box has a selection exactly as the keyboard's field does, so this setting is live
     * here rather than a copied label. "selection" with nothing selected sends nothing, and run()
     * answers that with its empty-input sentence — which is the honest reading of "only the
     * selected text" when there is none, and not the same thing as "auto".
     */
    private fun textFor(tool: WriterTool): String {
        val whole = input.text.toString()
        if (tool != WriterTool.ENHANCE) return whole
        val from = input.selectionStart
        val to = input.selectionEnd
        val selected = if (from in 0..to && to <= whole.length) whole.substring(from, to) else ""
        return when (WriterPrefs.enhanceScope(this)) {
            WriterPrefs.SCOPE_FIELD -> whole
            WriterPrefs.SCOPE_SELECTION -> selected
            else -> selected.ifBlank { whole }
        }
    }

    private fun start(tool: WriterTool) {
        say(getString(R.string.working, getString(tool.label)))
        runner.run(tool, textFor(tool)) { outcome ->
            val produced = outcome.text
            if (produced != null) {
                output.setText(produced)
                say(getString(R.string.done, getString(outcome.tool.label)))
            } else {
                // The engine's own reason, verbatim. A generic apology in its place is how a
                // provider outage, a missing key and an empty field become one unreadable state.
                say(outcome.error ?: getString(R.string.run_no_reason))
            }
        }
    }

    private fun say(line: String) {
        report.text = line
        report.visibility = View.VISIBLE
    }

    private fun copyOutput() {
        val text = output.text.toString()
        if (text.isBlank()) {
            say(getString(R.string.nothing_to_copy))
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ── the per-tool model choice — the owner's request, on screen ───────────

    private fun providerRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(caption(getString(R.string.provider_label)))
        val providers = WriterRegistry.providers
        row.addView(
            spinner(providers.map { it.label }, providers.indexOfFirst { it.id == WriterPrefs.providerId(this) }) { at ->
                WriterPrefs.put(this, WriterPrefs.KEY_PROVIDER, providers[at].id)
                // The model lists belong to the provider, so they are rebuilt rather than left
                // showing the previous provider's ids — which would offer the owner a model this
                // provider has never heard of and fail at call time.
                buildToolModelRows()
                refreshStatus()
            }
        )
        return row
    }

    /**
     * One picker per tool that has a model — Text Enhance, Grammar Check and Summary — which is
     * the owner's request stated exactly: "here we will define the AI model to do Summary, Grammar
     * Only, Text Enhance".
     *
     * Driven by [WriterTool.usesModel] rather than by a list written out here, so Translate cannot
     * grow a model picker that changes nothing and a fourth model-using tool cannot be forgotten.
     */
    private fun buildToolModelRows() {
        modelSection.removeAllViews()
        val provider = WriterRegistry.provider(WriterPrefs.providerId(this))
        val names = provider.models.map { it.name }
        WriterTool.values().filter { it.usesModel }.forEach { tool ->
            modelSection.addView(caption(getString(R.string.model_for_tool, getString(tool.label))))
            val current = WriterPrefs.modelFor(this, tool, provider.id)
            modelSection.addView(
                spinner(names, provider.models.indexOfFirst { it.id == current }) { at ->
                    WriterPrefs.putToolModel(this, tool, provider.id, provider.models[at].id)
                }
            )
        }
    }

    // ── plain views, built in code: no layout XML, no R.id to keep in step ───

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(HEADING)
        textSize = 16f
        setPadding(0, PAD, 0, PAD / 3)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(CAPTION)
        textSize = 13f
        setPadding(0, PAD / 3, 0, PAD / 3)
    }

    private fun textBox(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(BODY)
        setHintTextColor(CAPTION)
        textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        minLines = 5
        // Multi-line free text, and the output box is deliberately the same kind of box as the
        // input: a result you cannot edit is a result you have to copy out to change.
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun button(text: String, onTap: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onTap() }
    }

    /**
     * [selected] may be -1 when the stored id is not in this provider's list; the spinner then
     * starts at the first row, which is the same fallback [WriterPrefs.modelFor] applies, so the
     * picker and the run agree.
     *
     * THE FIRST CALLBACK IS DISCARDED, and that is a correctness fix rather than tidiness.
     * AdapterView delivers exactly one selection of its own when the adapter is first laid out,
     * before the owner has touched anything. Treated as a choice it would write the currently
     * shown model into [WriterPrefs.putToolModel] on the first frame — turning "this tool has no
     * override, follow the provider default" into "this tool is pinned to this model", silently
     * and for every tool at once. The owner would then stay on a withdrawn model after the
     * registry moved on, with a picker that showed nothing wrong.
     */
    private fun spinner(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
        val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        this.adapter = adapter
        if (selected >= 0) setSelection(selected)
        var settling = true
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (settling) {
                    settling = false
                    return
                }
                onPick(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PAD = 36

        // The same palette the other Cloud panels use, so the fleet reads as one system.
        val BACKGROUND: Int = Color.parseColor("#0b0e14")
        val HEADING: Int = Color.parseColor("#78c8ff")
        val BODY: Int = Color.parseColor("#e6edf3")
        val CAPTION: Int = Color.parseColor("#c8d4e0")
    }
}
