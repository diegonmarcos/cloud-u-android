// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.setToolbarKeyEnabled
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// SuperApp addition — "Text Enhancements": what the ENHANCE toolbar key rewrites, the style,
// tone and length of the rewrite it asks the AI Model Routing provider for, whether that key is
// on the toolbar, and a box to try the whole lot on your own text. Styles, tones and lengths all
// come from build.json::keyboard_ai (AiRouter.styles/tones/lengths).

fun createTextEnhanceSettings(context: Context): List<Setting> = listOf(
    Setting(context, Settings.PREF_ENHANCE_SCOPE, R.string.enhance_scope_title, R.string.enhance_scope_summary) { setting ->
        val items = listOf(
            context.getString(R.string.enhance_scope_auto) to "auto",
            context.getString(R.string.enhance_scope_selection) to "selection",
            context.getString(R.string.enhance_scope_field) to "field",
        )
        ListPreference(setting, items, Defaults.PREF_ENHANCE_SCOPE)
    },
    Setting(context, Settings.PREF_ENHANCE_STYLE, R.string.enhance_style_title, R.string.enhance_style_summary) { setting ->
        ListPreference(setting, AiRouter.styles.map { it.label to it.id }, AiRouter.defaultStyle)
    },
    // Tone and length are extra prompt lines appended after the style; their "keep" entries
    // carry an empty prompt, so the untouched screen sends exactly the plain style prompt.
    Setting(context, Settings.PREF_ENHANCE_TONE, R.string.enhance_tone_title, R.string.enhance_tone_summary) { setting ->
        ListPreference(setting, AiRouter.tones.map { it.label to it.id }, AiRouter.defaultTone)
    },
    Setting(context, Settings.PREF_ENHANCE_LENGTH, R.string.enhance_length_title, R.string.enhance_length_summary) { setting ->
        ListPreference(setting, AiRouter.lengths.map { it.label to it.id }, AiRouter.defaultLength)
    },
    // Not a stored pref of its own: it reads/writes the ENHANCE entry of the toolbar-keys
    // pref, so this switch and Settings → Toolbar never disagree.
    Setting(context, Settings.PREF_ENHANCE_TOOLBAR_KEY, R.string.enhance_toolbar_title, R.string.enhance_toolbar_summary) { setting ->
        val ctx = LocalContext.current
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        val prefs = ctx.prefs()
        val enabled = ToolbarKey.ENHANCE in getEnabledToolbarKeys(prefs)
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = { setToolbarKeyEnabled(prefs, ToolbarKey.ENHANCE, !enabled) },
        ) {
            Switch(checked = enabled, onCheckedChange = { setToolbarKeyEnabled(prefs, ToolbarKey.ENHANCE, it) })
        }
    },
    // Stores nothing — the key only exists so the row can live in the settings registry.
    Setting(context, Settings.PREF_ENHANCE_TEST, R.string.enhance_test_title, R.string.enhance_test_summary) { setting ->
        EnhanceTestBox(setting)
    },
)

/**
 * Try the settings above without leaving the screen: the text typed here goes through the very
 * same [AiRouter.enhanceStyle] prompt the ENHANCE toolbar key builds, so what shows up below is
 * what the keyboard would have committed into the field.
 */
@Composable
private fun EnhanceTestBox(setting: Setting) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp)) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        setting.description?.let {
            Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            minLines = 3,
            placeholder = { Text(stringResource(R.string.enhance_test_hint)) },
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    busy = true
                    output = ""
                    scope.launch {
                        val style = AiRouter.enhanceStyle(ctx)
                        output = withContext(Dispatchers.IO) {
                            runCatching { AiRouter.complete(ctx, style.prompt, input) }.getOrElse {
                                ctx.getString(R.string.enhance_failed, it.message ?: it.javaClass.simpleName)
                            }
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.enhance_test_run)) }
            Text(AiRouter.provider(ctx).label, Modifier.padding(start = 12.dp), color = dim,
                style = MaterialTheme.typography.bodySmall)
        }
        if (busy)
            Text(stringResource(R.string.enhance_test_running, AiRouter.provider(ctx).label),
                Modifier.padding(top = 8.dp), color = dim, style = MaterialTheme.typography.bodyMedium)
        else if (output.isNotEmpty())
            SelectionContainer { Text(output, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun TextEnhanceScreen(onClickBack: () -> Unit) {
    val items = listOf(
        Settings.PREF_ENHANCE_SCOPE,
        Settings.PREF_ENHANCE_STYLE,
        Settings.PREF_ENHANCE_TONE,
        Settings.PREF_ENHANCE_LENGTH,
        Settings.PREF_ENHANCE_TOOLBAR_KEY,
        Settings.PREF_ENHANCE_TEST,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_enhance),
        settings = items,
    )
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TextEnhanceScreen {}
        }
    }
}
