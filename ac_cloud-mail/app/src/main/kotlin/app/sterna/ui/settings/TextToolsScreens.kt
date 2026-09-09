package app.sterna.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.ui.text.MailAiRegistry
import app.sterna.ui.text.MailTextToolsPrefs
import app.sterna.ui.text.textToolsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configs ▸ Text — cloud-mail's OWN AI Routing, Text Enhancement, Text Resume and Translation.
 *
 * THESE ARE SCREENS IN THIS APP, and that is the whole change. They used to be intents into the
 * Cloud Keyboard's settings: one copy of each page existed on the device, it belonged to the
 * keyboard, and every value on it was the keyboard's. Which meant that from cloud-mail the owner
 * could see their text-tool settings and could not change them — changing one changed the keyboard
 * everywhere instead. Every control below writes to [MailTextToolsPrefs], which is cloud-mail's
 * own SharedPreferences file under cloud-mail's own uid. Editing here changes cloud-mail. It does
 * not, and cannot, change the keyboard.
 *
 * WHAT IS NOT COPIED HERE: the engine. There is still one HTTP client, one summary budget, one
 * bullet enforcement and one API key, all in the keyboard's process behind ITextTools. These pages
 * decide WHAT to send; that engine is HOW it is sent, and duplicating it would mean fixing every
 * future bug in it twice.
 *
 * The menus are DATA — [MailAiRegistry] over `build.json::mail_ai` — so adding a style or a model
 * is a registry edit rather than a Compose edit, and the prompt each page sends can be shown on
 * the page instead of being restated in Kotlin.
 */

/** One selectable option: what the user reads, and the id actually stored. */
internal class TextToolOption(val id: String, val label: String)

/**
 * A page that is one list of choices over one preference key, plus an optional preview of what the
 * choice actually sends.
 *
 * All four Text pages are this shape, so they are this function rather than four near-copies. The
 * radio row is deliberately plain: a page whose job is "let the owner change this" earns nothing
 * from a cleverer control, and the previous version of this feature failed by being unchangeable,
 * not by being ugly.
 */
@Composable
private fun TextToolChoiceGroup(
    title: String,
    options: List<TextToolOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    // The whole row is the target, not just the button: a 20dp radio is a miss
                    // waiting to happen and this app's other option rows already work this way.
                    .clickable { onSelect(option.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option.id == selectedId, onClick = { onSelect(option.id) })
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/**
 * What the current choices actually send to the model.
 *
 * READ-ONLY, AND THAT IS NOT THE READ-ONLY THE OWNER COMPLAINED ABOUT. The settings above are
 * editable; this box is a mirror of them, built by the very function a run calls, so the page
 * cannot claim one prompt while the engine sends another. Editing the composed text here would be
 * editing a derived value — the way to change it is to change a choice above, or the registry.
 */
@Composable
private fun ComposedPromptPreview(prompt: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(R.string.settings_text_prompt_preview_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                prompt.ifBlank { stringResource(R.string.settings_text_prompt_preview_empty) },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/**
 * Bring the owner's existing configuration in before the page draws, once ever.
 *
 * Without this the first visit to any of these pages would show factory defaults over settings the
 * owner had already chosen, which reads as their configuration having been thrown away. Blocking
 * binder work, so it goes off the composition thread; the page renders defaults for the frame or
 * two it takes and then recomposes on the seeded values.
 */
@Composable
private fun SeedTextToolSettings() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            MailTextToolsPrefs.seedFromKeyboard(context, textToolsClient(context))
        }
    }
}

/**
 * A settings page's stored value, as state that survives being written.
 *
 * SharedPreferences is not observable by itself, so the screen holds the value it wrote and the
 * store holds the value that is read back on the next visit. Both are updated together in
 * [set] — writing one without the other is how a radio button snaps back a frame after being
 * tapped, which would look exactly like the unchangeable page this replaces.
 */
@Composable
private fun rememberStoredChoice(key: String, initial: () -> String): Pair<String, (String) -> Unit> {
    val context = LocalContext.current.applicationContext
    var value by remember(key) { mutableStateOf(initial()) }
    // Pair(...) rather than `value to { ... }`: an infix `to` followed by a brace reads as a
    // trailing lambda, and the two parses differ. Spelled out, there is nothing to guess.
    return Pair(
        value,
        { chosen: String ->
            value = chosen
            MailTextToolsPrefs.put(context, key, chosen)
        },
    )
}

private fun List<MailAiRegistry.Style>.asOptions(): List<TextToolOption> =
    map { TextToolOption(it.id, it.label) }

/** Configs ▸ Text ▸ AI Model Routing — which provider, and which of its models. */
@Composable
internal fun MailAiRoutingScreen(onBack: () -> Unit) {
    SeedTextToolSettings()
    val context = LocalContext.current.applicationContext
    DetailScaffold(stringResource(R.string.settings_text_ai_routing_title), onBack) { padding ->
        val (providerId, setProvider) = rememberStoredChoice(MailTextToolsPrefs.KEY_PROVIDER) {
            MailTextToolsPrefs.providerId(context)
        }
        val provider = MailAiRegistry.provider(providerId)
        // Keyed on the provider so switching provider re-reads THAT provider's chosen model
        // rather than showing the previous provider's, which would not be one of the rows below.
        val (modelId, setModel) = rememberStoredChoice(MailTextToolsPrefs.KEY_MODEL_PREFIX + provider.id) {
            MailTextToolsPrefs.modelId(context, provider.id)
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.settings_text_ai_routing_note),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            TextToolChoiceGroup(
                title = stringResource(R.string.settings_text_provider),
                options = MailAiRegistry.providers.map { TextToolOption(it.id, it.label) },
                selectedId = provider.id,
                onSelect = setProvider,
            )
            HorizontalDivider()
            TextToolChoiceGroup(
                title = stringResource(R.string.settings_text_model),
                options = provider.models.map { TextToolOption(it.id, it.name) },
                selectedId = modelId,
                onSelect = setModel,
            )
            // The API key is deliberately absent. It belongs to the Cloud Keyboard, which spends it
            // on this app's behalf and never hands it over; a second copy of a credential is a
            // second place it can leak from, and the owner asked for their settings, not their key.
            Text(
                stringResource(R.string.settings_text_key_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Configs ▸ Text ▸ Text Enhancement — the style, and the three lines appended after it. */
@Composable
internal fun MailTextEnhanceScreen(onBack: () -> Unit) {
    SeedTextToolSettings()
    val context = LocalContext.current.applicationContext
    DetailScaffold(stringResource(R.string.settings_text_enhance_title), onBack) { padding ->
        val (styleId, setStyle) = rememberStoredChoice(MailTextToolsPrefs.KEY_ENHANCE_STYLE) {
            MailTextToolsPrefs.enhanceStyleId(context)
        }
        val (toneId, setTone) = rememberStoredChoice(MailTextToolsPrefs.KEY_ENHANCE_TONE) {
            MailTextToolsPrefs.enhanceToneId(context)
        }
        val (lengthId, setLength) = rememberStoredChoice(MailTextToolsPrefs.KEY_ENHANCE_LENGTH) {
            MailTextToolsPrefs.enhanceLengthId(context)
        }
        val (languageId, setLanguage) = rememberStoredChoice(MailTextToolsPrefs.KEY_ENHANCE_LANGUAGE) {
            MailTextToolsPrefs.enhanceLanguageId(context)
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            TextToolChoiceGroup(
                title = stringResource(R.string.settings_text_enhance_style),
                options = MailAiRegistry.styles.asOptions(),
                selectedId = styleId,
                onSelect = setStyle,
            )
            HorizontalDivider()
            TextToolChoiceGroup(
                title = stringResource(R.string.settings_text_enhance_tone),
                options = MailAiRegistry.tones.asOptions(),
                selectedId = toneId,
                onSelect = setTone,
            )
            HorizontalDivider()
            TextToolChoiceGroup(
                title = stringResource(R.string.settings_text_enhance_length),
                options = MailAiRegistry.lengths.asOptions(),
                selectedId = lengthId,
                onSelect = setLength,
            )
            HorizontalDivider()
            TextToolChoiceGroup(
                title = stringResource(R.string.settings_text_enhance_language),
                options = MailAiRegistry.languages.asOptions(),
                selectedId = languageId,
                onSelect = setLanguage,
            )
            HorizontalDivider()
            ComposedPromptPreview(MailAiRegistry.enhancePrompt(styleId, toneId, lengthId, languageId))
        }
    }
}

/**
 * Configs ▸ Text ▸ Text Resume — which summary shape.
 *
 * "Resume" is the owner's product name for SUMMARISE and is kept exactly: not a curriculum vitae,
 * not resuming a paused operation. The reader's AI Resume button sends whatever is chosen here.
 */
@Composable
internal fun MailTextResumeScreen(onBack: () -> Unit) {
    SeedTextToolSettings()
    val context = LocalContext.current.applicationContext
    DetailScaffold(stringResource(R.string.settings_text_resume_title), onBack) { padding ->
        val (summaryId, setSummary) = rememberStoredChoice(MailTextToolsPrefs.KEY_SUMMARY_STYLE) {
            MailTextToolsPrefs.summaryStyleId(context)
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            TextToolChoiceGroup(
                title = stringResource(R.string.settings_text_resume_shape),
                options = MailAiRegistry.summaries.asOptions(),
                selectedId = summaryId,
                onSelect = setSummary,
            )
            HorizontalDivider()
            ComposedPromptPreview(MailAiRegistry.summaryPrompt(summaryId))
        }
    }
}

/**
 * Configs ▸ Text ▸ Translation — the default target language for Translate on a message.
 *
 * A FREE-TEXT TAG RATHER THAN A MENU, and deliberately. The list of languages a translation engine
 * can reach is the ENGINE's, discovered at runtime on the device, not registry data this app could
 * bake — so a menu here would either be a second, staler copy of that list or would need the engine
 * bound just to render. An empty field means "the engine's own default", which is what this app
 * sent before it had a setting at all.
 */
@Composable
internal fun MailTranslationScreen(onBack: () -> Unit) {
    SeedTextToolSettings()
    val context = LocalContext.current.applicationContext
    DetailScaffold(stringResource(R.string.settings_text_translation_title), onBack) { padding ->
        var target by remember { mutableStateOf(MailTextToolsPrefs.translateTarget(context)) }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.settings_text_translation_note),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            OutlinedTextField(
                value = target,
                onValueChange = {
                    // Written on every keystroke rather than on a Save button: this app's other
                    // free-text settings behave that way, and a field that needs confirming is a
                    // field an owner can leave thinking they changed something.
                    target = it
                    MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_TRANSLATE_TARGET, it.trim())
                },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_text_translation_target)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}
