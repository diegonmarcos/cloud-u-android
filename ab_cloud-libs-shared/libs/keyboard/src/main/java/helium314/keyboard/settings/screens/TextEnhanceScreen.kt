// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.R
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

// SuperApp addition — "Text Enhancements": style of the rewrite the ENHANCE toolbar key
// asks the AI Model Routing provider for, and whether that key is on the toolbar.
// Styles come from build.json::keyboard_ai.styles (AiRouter.styles).

fun createTextEnhanceSettings(context: Context): List<Setting> = listOf(
    Setting(context, Settings.PREF_ENHANCE_STYLE, R.string.enhance_style_title, R.string.enhance_style_summary) { setting ->
        ListPreference(setting, AiRouter.styles.map { it.label to it.id }, AiRouter.defaultStyle)
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
)

@Composable
fun TextEnhanceScreen(onClickBack: () -> Unit) {
    val items = listOf(
        Settings.PREF_ENHANCE_STYLE,
        Settings.PREF_ENHANCE_TOOLBAR_KEY,
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
