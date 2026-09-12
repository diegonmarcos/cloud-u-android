package com.diegonmarcos.cloudwriter

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwriter.ui.ActionRow
import com.diegonmarcos.cloudwriter.ui.BlockGap
import com.diegonmarcos.cloudwriter.ui.ChoiceRow
import com.diegonmarcos.cloudwriter.ui.CloudWriterTheme
import com.diegonmarcos.cloudwriter.ui.FeatureCard
import com.diegonmarcos.cloudwriter.ui.NoteText
import com.diegonmarcos.cloudwriter.ui.PageGutter
import com.diegonmarcos.cloudwriter.ui.SectionHeader
import com.diegonmarcos.cloudwriter.ui.WriterTextField
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
 * The status card at the top of this screen names the peer that actually answered, read out of
 * that peer's own reply rather than out of a constant here, so it cannot be wrong about which
 * application did the work.
 *
 * NO DEAD CONTROLS. Every button either does the thing or puts a sentence on screen saying why
 * not. A control that does nothing and says nothing is indistinguishable from a crash, a missing
 * permission and a network failure — which is exactly the defect the Enhance key and the three
 * translate-bar controls each shipped with once.
 *
 * ── WHAT CHANGED IN THE UI ENHANCEMENT, AND WHAT DELIBERATELY DID NOT ────────────────────────
 *
 * THE FEATURES ARE UNTOUCHED. Every call this screen makes — [WriterToolRunner.run], the scope
 * rule in [textFor], the status probe, the provider and per-tool model writes — is the same call
 * it made in the build the owner is running. What moved is the drawing: this screen used to be a
 * LinearLayout filled by addView with raw-pixel padding, four hardcoded hex colours and six
 * hand-picked text sizes, and it is now Material 3 over the type scale and colour roles.
 *
 * THE FOUR PAGES ARE CARDS, NOT BUTTONS. They were four plain stacked Buttons whose entire content
 * was the page's name, which told the owner nothing about what any of them did — and this is the
 * screen he sees every time he opens the application. Each is now an icon, the name, and a line
 * saying what it configures, in a 72dp target.
 *
 * WHY IT LOOKED THE WAY IT DID, which is the part worth keeping written down: these screens were
 * copied out of cloud-keyboard under task 272, and cloud-keyboard is an IME. An IME draws into a
 * window it does not own, cannot apply an application theme and therefore has no layout files and
 * no MaterialTheme — 0 Compose, 0 material3, 0 layout XML, by necessity. cloud-writer inherited
 * the shape of that constraint without ever having the constraint. Diverging from the keyboard's
 * UI is not a risk to the copy; per task 209 it is the copy doing its job.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var runner: WriterToolRunner

    /** For the two calls that must not run on the main thread: the status probe and its label. */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Screen state. Plain mutableStateOf held by the activity rather than a ViewModel: this screen
    // has no asynchronous loading to survive and android:configChanges already keeps the activity
    // alive across rotation and the uiMode flip, so a ViewModel would be ceremony around five
    // fields. Kept as properties so the existing Handler/Executor threading writes into them
    // exactly as it wrote into the TextViews, without introducing a coroutine dependency.
    private val status: MutableState<String?> = mutableStateOf(null)
    private val input: MutableState<TextFieldValue> = mutableStateOf(TextFieldValue(""))
    private val output: MutableState<String> = mutableStateOf("")
    private val report: MutableState<String?> = mutableStateOf(null)
    private val busy: MutableState<Boolean> = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runner = WriterToolRunner(this)

        adoptSharedText(intent)

        setContent {
            CloudWriterTheme {
                HomeScreen()
            }
        }

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
        if (!shared.isNullOrBlank()) input.value = TextFieldValue(shared)
    }

    // ── the screen ───────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen() {
        Scaffold(
            // A large title, which is the Material 3 way of saying what application this is. The
            // theme is NoActionBar and the old screen therefore had no title at all beyond the
            // launcher icon the owner had already tapped.
            topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        ) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PageGutter),
                verticalArrangement = Arrangement.spacedBy(BlockGap),
            ) {
                StatusCard()
                WorkArea()
                ModelSection()
                PagesSection()
                NoteText(stringResource(R.string.token_note))
                Spacer(Modifier.height(BlockGap))
            }
        }
    }

    /**
     * Who is actually answering, on a card rather than as a grey line above the first box.
     *
     * THE ICON IS DERIVED FROM THE STATE, not decoration: the probe has three outcomes and they are
     * three different repairs — nothing installed is an install, bound-but-silent is an update of
     * the peer, and a named peer is nothing to repair at all. While the probe is still running the
     * card shows a spinner, because "checking" and "nothing found" looked identical before.
     */
    @Composable
    private fun StatusCard() {
        val line = status.value
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(PageGutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (line == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (servingAppNamed.value) Icons.Filled.Cloud else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (servingAppNamed.value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(PageGutter))
                Text(
                    text = line ?: stringResource(R.string.status_checking),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    /** True once the probe has named a peer; drives the status card's icon and its colour. */
    private val servingAppNamed: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The writing surface: the box, the tools, the result.
     *
     * ONE CARD, so the three read as one operation rather than as three unrelated controls that
     * happen to be stacked. The tool row scrolls sideways and is built from the enum, so a tool
     * added there gets a button here and cannot be forgotten.
     */
    @Composable
    private fun WorkArea() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(PageGutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                inputField()
                OptionsSection()

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WriterTool.values().forEach { tool ->
                        FilledTonalButton(
                            onClick = { start(tool) },
                            // Disabled WHILE A RUN IS IN FLIGHT, and the runner still refuses a
                            // second call with a sentence if one arrives anyway — the button is
                            // the hint, not the guard.
                            enabled = !busy.value,
                        ) {
                            Icon(iconFor(tool), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(tool.label))
                        }
                    }
                }

                report.value?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                WriterTextField(
                    value = output.value,
                    onValueChange = { output.value = it },
                    label = stringResource(R.string.output_label),
                    supporting = stringResource(R.string.output_hint),
                    trailing = {
                        IconButton(onClick = { copyOutput() }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                // NOT null here: this one is a control with no visible label, so
                                // its description is the only thing a screen reader can announce.
                                contentDescription = stringResource(R.string.action_copy),
                            )
                        }
                    },
                )

                ActionRow {
                    TextButton(onClick = {
                        input.value = TextFieldValue("")
                        output.value = ""
                        report.value = null
                    }) { Text(stringResource(R.string.action_clear)) }
                }
            }
        }
    }

    /**
     * The input box, and the one place the migration is not a like-for-like move.
     *
     * The Text Enhance scope setting can be "only the selected text", which the old screen served
     * by reading `selectionStart`/`selectionEnd` off the EditText at the moment the tool ran. A
     * Compose text field does not expose its selection to the outside, so this field is driven by
     * a TextFieldValue, which carries the text AND the selection as one value — and [textFor]
     * reads the selection back out of it.
     *
     * ONE VALUE RATHER THAN A MIRRORED COPY, deliberately. A separate "where is the caret" state
     * updated alongside the text is two facts that can disagree; and a mirror that is simply never
     * written is worse than that, because "selection" then resolves to an empty string and refuses
     * every run while every control on the screen still looks right.
     */
    @Composable
    private fun inputField() {
        WriterTextField(
            value = input.value,
            onValueChange = { input.value = it },
            label = stringResource(R.string.input_label),
            supporting = stringResource(R.string.input_hint),
        )
    }

    /** Exhaustive over the enum WITHOUT an `else`, so adding a tool is a compile error here. */
    private fun iconFor(tool: WriterTool): ImageVector = when (tool) {
        WriterTool.ENHANCE -> Icons.Filled.AutoAwesome
        WriterTool.GRAMMAR -> Icons.Filled.Spellcheck
        WriterTool.SUMMARY -> Icons.Filled.Summarize
        WriterTool.TRANSLATE -> Icons.Filled.Translate
    }

    // ── the per-tool model choice — the owner's request, on screen ───────────

    /**
     * One picker per tool that has a model — Text Enhance, Grammar Check and Summary — which is
     * the owner's request stated exactly: "here we will define the AI model to do Summary, Grammar
     * Only, Text Enhance".
     *
     * Driven by [WriterTool.usesModel] rather than by a list written out here, so Translate cannot
     * grow a model picker that changes nothing and a fourth model-using tool cannot be forgotten.
     *
     * THE ROWS ARE THE SAME ROWS THE SETTINGS PAGES USE. They were bare Spinners, which is a
     * different control for the same job as the settings pages' tap-to-choose rows; one component
     * now serves both, so the model picked here and the model picked on AI Model Routing are
     * chosen the same way.
     */
    /**
     * The four options that shape what Enhance and Translate actually do, on the main screen,
     * under the text box — the owner's "under main boxe ads all the options for the
     * enhace/translation: Structure(Grammar and clarity), Tone, Size, Language Output".
     *
     * They are NOT new settings. Each row reads and writes the SAME key the Text Enhance page
     * writes (KEY_ENHANCE_STYLE / _TONE / _LENGTH / _LANGUAGE), so a choice made here is the one
     * that page shows and the reverse, and there is exactly one answer to "what will Enhance do".
     * A private copy for the main screen would have given the owner two places to set the tone and
     * no way to tell which one the run used — which is the defect of task 209, restated.
     *
     * Why here at all, when the page already offers them: because the page is three taps away and
     * these are per-text decisions. The language row in particular is what makes Enhance "do it
     * all": with a target language set, the composed prompt translates as it rewrites, and the
     * same row supplies Translate its target. That is why it is labelled Language OUTPUT.
     */
    @Composable
    private fun OptionsSection() {
        Column(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.options_title))
            ChoiceRow(
                stringResource(R.string.options_structure),
                null,
                WriterRegistry.styles.map { it.label to it.id },
                WriterPrefs.enhanceStyleId(this@MainActivity),
                WriterRegistry.defaultStyle,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_STYLE, it) }
            ChoiceRow(
                stringResource(R.string.options_tone),
                null,
                WriterRegistry.tones.map { it.label to it.id },
                WriterPrefs.enhanceToneId(this@MainActivity),
                WriterRegistry.defaultTone,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_TONE, it) }
            ChoiceRow(
                stringResource(R.string.options_size),
                null,
                WriterRegistry.lengths.map { it.label to it.id },
                WriterPrefs.enhanceLengthId(this@MainActivity),
                WriterRegistry.defaultLength,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_LENGTH, it) }
            ChoiceRow(
                stringResource(R.string.options_language),
                null,
                WriterRegistry.languages.map { it.label to it.id },
                WriterPrefs.enhanceLanguageId(this@MainActivity),
                WriterRegistry.defaultLanguage,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_LANGUAGE, it) }
        }
    }

    @Composable
    private fun ModelSection() {
        val provider = WriterRegistry.provider(WriterPrefs.providerId(this))
        Column(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.models_heading))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    NoteText(stringResource(R.string.models_note))
                    ChoiceRow(
                        title = stringResource(R.string.provider_label),
                        summary = null,
                        items = WriterRegistry.providers.map { it.label to it.id },
                        current = WriterPrefs.providerId(this@MainActivity),
                        fallback = WriterRegistry.defaultProvider,
                    ) {
                        WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_PROVIDER, it)
                        // The model lists belong to the provider, so the rows below are rebuilt
                        // rather than left showing the previous provider's ids — which would offer
                        // the owner a model this provider has never heard of and fail at call
                        // time. Recomposition does that here; the old screen removed the views by
                        // hand and added them back.
                        providerGeneration.value++
                        refreshStatus()
                    }

                    // Read so the rows below recompose when the provider changes.
                    providerGeneration.value
                    WriterTool.values().filter { it.usesModel }.forEach { tool ->
                        ChoiceRow(
                            title = stringResource(R.string.model_for_tool, stringResource(tool.label)),
                            summary = null,
                            items = provider.models.map { it.name to it.id },
                            current = WriterPrefs.modelFor(this@MainActivity, tool, provider.id),
                            fallback = provider.defaultModel,
                        ) { WriterPrefs.putToolModel(this@MainActivity, tool, provider.id, it) }
                    }
                }
            }
        }
    }

    /** Bumped when the provider changes, to recompose the model rows over the new provider. */
    private val providerGeneration: MutableState<Int> = mutableStateOf(0)

    // ── the four configuration pages ─────────────────────────────────────────

    /**
     * THE FOUR CONFIGURATION PAGES. Each opens an activity IN THIS APPLICATION over THIS
     * APPLICATION'S preference file. None of them is an Intent into Cloud Keyboard's settings,
     * and none of them reads a value the keyboard wrote — which is the difference between the
     * pages the owner asked for and the pages task 209 delivered.
     *
     * ONE LIST WITH FOUR FIELDS, not a list of names beside a parallel list of icons: a page and
     * its icon and its description cannot fall out of step if there is nowhere for them to drift
     * apart, and adding a page is one entry.
     */
    private class Page(
        @StringRes val label: Int,
        @StringRes val summary: Int,
        val icon: ImageVector,
        val screen: Class<out Activity>,
    )

    @Composable
    private fun PagesSection() {
        Column(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.settings_heading))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Page(
                        R.string.settings_screen_enhance,
                        R.string.settings_screen_enhance_summary,
                        Icons.Filled.AutoAwesome,
                        TextEnhanceActivity::class.java,
                    ),
                    Page(
                        R.string.settings_screen_translation,
                        R.string.settings_screen_translation_summary,
                        Icons.Filled.Translate,
                        TranslationActivity::class.java,
                    ),
                    Page(
                        R.string.settings_screen_grammar,
                        R.string.settings_screen_grammar_summary,
                        Icons.Filled.Spellcheck,
                        GrammarCheckActivity::class.java,
                    ),
                    Page(
                        R.string.settings_screen_ai_routing,
                        R.string.settings_screen_ai_routing_summary,
                        Icons.Filled.AltRoute,
                        AiRoutingActivity::class.java,
                    ),
                ).forEach { page ->
                    FeatureCard(
                        icon = page.icon,
                        title = stringResource(page.label),
                        summary = stringResource(page.summary),
                        onClick = { startActivity(Intent(this@MainActivity, page.screen)) },
                    )
                }
            }
        }
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
            main.post {
                servingAppNamed.value = snapshot != null
                status.value = line
            }
        }
    }

    // ── running a tool ───────────────────────────────────────────────────────

    /**
     * What Text Enhance is given, per "Qué se mejora" on the Text Enhancements page.
     *
     * The input box has a selection exactly as the keyboard's field does, so this setting is live
     * here rather than a copied label. "selection" with nothing selected sends nothing, and run()
     * answers that with its empty-input sentence — which is the honest reading of "only the
     * selected text" when there is none, and not the same thing as "auto".
     */
    private fun textFor(tool: WriterTool): String {
        val whole = input.value.text
        if (tool != WriterTool.ENHANCE) return whole
        // Selection.start/end are NOT ordered — dragging right-to-left puts start after end — so
        // they are normalised before they index the string. min/max rather than a reversal check
        // because a backwards selection is the ordinary way half of a sentence gets picked.
        val from = minOf(input.value.selection.start, input.value.selection.end)
        val to = maxOf(input.value.selection.start, input.value.selection.end)
        val selected = if (from in 0..to && to <= whole.length) whole.substring(from, to) else ""
        return when (WriterPrefs.enhanceScope(this)) {
            WriterPrefs.SCOPE_FIELD -> whole
            WriterPrefs.SCOPE_SELECTION -> selected
            else -> selected.ifBlank { whole }
        }
    }

    private fun start(tool: WriterTool) {
        busy.value = true
        say(getString(R.string.working, getString(tool.label)))
        runner.run(tool, textFor(tool)) { outcome ->
            busy.value = false
            val produced = outcome.text
            if (produced != null) {
                output.value = produced
                say(getString(R.string.done, getString(outcome.tool.label)))
            } else {
                // The engine's own reason, verbatim. A generic apology in its place is how a
                // provider outage, a missing key and an empty field become one unreadable state.
                say(outcome.error ?: getString(R.string.run_no_reason))
            }
        }
    }

    private fun say(line: String) {
        report.value = line
    }

    private fun copyOutput() {
        val text = output.value
        if (text.isBlank()) {
            say(getString(R.string.nothing_to_copy))
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
