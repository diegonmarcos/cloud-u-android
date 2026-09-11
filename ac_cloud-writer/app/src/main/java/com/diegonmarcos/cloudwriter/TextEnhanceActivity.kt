package com.diegonmarcos.cloudwriter

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PAGE 1 — "Mejoras de texto" / Text Enhancements.
 *
 * The same eight rows as cloud-keyboard's `TextEnhanceScreen`, in the same order, with the same
 * wording and the same defaults — and every one of them stored in cloud-writer's own preference
 * file. Change the tone here and the keyboard's tone does not move; change it in the keyboard and
 * this page does not move. That is what the owner asked for and what task 209 did not deliver.
 *
 *   1  Qué se mejora            list     auto | selection | field          default auto
 *   2  Estilo de mejora         list     writer_ai.styles                  default clarity
 *   3  Tono                     list     writer_ai.tones                   default keep
 *   4  Extensión                list     writer_ai.lengths                 default keep
 *   5  Idioma del resultado     list     writer_ai.languages               default keep
 *   6  Mostrar en la barra…     switch                                     default on
 *   7  La instrucción que se envía   read-only, GENERATED
 *   8  Pruébalo                 live run against the settings above
 *
 * ROW 7 IS GENERATED, NOT WRITTEN DOWN. It is `WriterPrefs.enhancePrompt(this)` — the very call
 * [WriterToolRunner] makes when the owner taps Text Enhance on the main screen — so the page cannot
 * describe a request the application does not send. It is also re-read after every pick on this
 * page, so changing the tone changes the paragraph under the owner's thumb immediately. A preview
 * that happened to match today and was a literal would be a lie a week later, and this is the third
 * time that class of defect has been paid for here.
 *
 * ROW 8 ACTUALLY RUNS. It goes through [WriterToolRunner], which is the same object and the same
 * binder call the main screen uses, so "it worked in the test box" and "it worked on the button"
 * cannot disagree.
 */
class TextEnhanceActivity : WriterSettingsActivity() {

    private lateinit var runner: WriterToolRunner
    private lateinit var prompt: TextView

    override fun pageTitle(): String = getString(R.string.settings_screen_enhance)

    override fun buildPage() {
        runner = WriterToolRunner(this)

        listRow(
            getString(R.string.enhance_scope_title),
            getString(R.string.enhance_scope_summary),
            listOf(
                getString(R.string.enhance_scope_auto) to WriterPrefs.SCOPE_AUTO,
                getString(R.string.enhance_scope_selection) to WriterPrefs.SCOPE_SELECTION,
                getString(R.string.enhance_scope_field) to WriterPrefs.SCOPE_FIELD,
            ),
            WriterPrefs.enhanceScope(this),
            WriterPrefs.DEFAULT_ENHANCE_SCOPE,
        ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_SCOPE, it) }

        listRow(
            getString(R.string.enhance_style_title),
            getString(R.string.enhance_style_summary),
            WriterRegistry.styles.map { it.label to it.id },
            WriterPrefs.enhanceStyleId(this),
            WriterRegistry.defaultStyle,
        ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_STYLE, it); refreshPrompt() }

        // Tone and length are extra prompt lines appended after the style; their "keep" entries
        // carry an empty prompt, so an untouched page sends exactly the plain style prompt.
        listRow(
            getString(R.string.enhance_tone_title),
            getString(R.string.enhance_tone_summary),
            WriterRegistry.tones.map { it.label to it.id },
            WriterPrefs.enhanceToneId(this),
            WriterRegistry.defaultTone,
        ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_TONE, it); refreshPrompt() }

        listRow(
            getString(R.string.enhance_length_title),
            getString(R.string.enhance_length_summary),
            WriterRegistry.lengths.map { it.label to it.id },
            WriterPrefs.enhanceLengthId(this),
            WriterRegistry.defaultLength,
        ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_LENGTH, it); refreshPrompt() }

        // Picking anything but "keep" turns the enhancement into a translation as well; the
        // registry lists the five starred languages first, then the rest alphabetically, and
        // JSONObject keeps insertion order, so that IS the menu order.
        listRow(
            getString(R.string.enhance_language_title),
            getString(R.string.enhance_language_summary),
            WriterRegistry.languages.map { it.label to it.id },
            WriterPrefs.enhanceLanguageId(this),
            WriterRegistry.defaultLanguage,
        ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_LANGUAGE, it); refreshPrompt() }

        switchRow(
            getString(R.string.enhance_toolbar_title),
            getString(R.string.enhance_toolbar_summary),
            WriterPrefs.flag(this, WriterPrefs.KEY_ENHANCE_TOOLBAR_KEY, WriterPrefs.DEFAULT_ENHANCE_TOOLBAR_KEY),
        ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_ENHANCE_TOOLBAR_KEY, it) }
        note(getString(R.string.enhance_toolbar_writer_note))

        prompt = readOnlyRow(
            getString(R.string.enhance_prompt_title),
            getString(R.string.enhance_prompt_summary),
            WriterPrefs.enhancePrompt(this),
        )

        addTestBox()
    }

    /**
     * Recompose the preview from the settings, through the same call a run makes.
     *
     * Not "append the new line" and not "rebuild the string from the ids I just wrote": one
     * composition rule, in [WriterRegistry.enhancePrompt], read from the store the run reads.
     */
    private fun refreshPrompt() {
        prompt.text = WriterPrefs.enhancePrompt(this)
    }

    /**
     * "Pruébalo" — the settings above, run on the owner's own text, without leaving the screen.
     *
     * THE REAL RUNNER, NOT A REHEARSAL OF ONE. [WriterToolRunner.run] resolves this application's
     * composed prompt, this application's provider and the model pinned for Text Enhance, and
     * makes the binder call to whichever peer serves ITextTools. Everything the main screen's
     * Text Enhance button does, including the refusals — an empty box and a run already in flight
     * each answer with a sentence rather than with nothing.
     */
    private fun addTestBox() {
        val input = EditText(this).apply {
            hint = getString(R.string.enhance_test_hint)
            setTextColor(BODY)
            setHintTextColor(CAPTION)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val status = TextView(this).apply {
            setTextColor(CAPTION)
            textSize = 12f
            visibility = View.GONE
        }
        val output = EditText(this).apply {
            hint = getString(R.string.enhance_test_output)
            setTextColor(BODY)
            setHintTextColor(CAPTION)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val run = Button(this).apply {
            text = getString(R.string.enhance_test_run)
            setOnClickListener {
                status.text = getString(R.string.working, getString(R.string.tool_enhance))
                status.visibility = View.VISIBLE
                runner.run(WriterTool.ENHANCE, input.text.toString()) { outcome ->
                    val produced = outcome.text
                    if (produced != null) {
                        output.setText(produced)
                        status.text = getString(R.string.done, getString(R.string.tool_enhance))
                    } else {
                        // The engine's own reason, verbatim. A generic apology in its place is how
                        // a provider outage, a missing key and an empty box become one state.
                        status.text = outcome.error ?: getString(R.string.run_no_reason)
                    }
                }
            }
        }
        page.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, PAD / 2, 0, PAD / 2)
                addView(heading(getString(R.string.enhance_test_title)))
                addView(caption(getString(R.string.enhance_test_summary)))
                addView(input)
                addView(run)
                addView(status)
                addView(output)
            }
        )
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(HEADING)
        textSize = 16f
        setPadding(0, PAD / 3, 0, PAD / 6)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(CAPTION)
        textSize = 12f
        setPadding(0, 0, 0, PAD / 3)
    }
}
