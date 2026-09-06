// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
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
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.TextInputPreference

// SuperApp addition — "AI Model Routing": the ONE provider/token/model choice shared by
// Text Enhancements, Grammar check (mode "ai") and translation. Everything here is built
// from build.json::keyboard_ai (AiRouter.providers) — add a provider = one JSON entry.

/** `ai_token_<id>_title` / `ai_model_<id>_title` if the provider has its own string, else the generic one. */
private fun Context.titleFor(prefix: String, id: String, generic: Int): Int =
    resources.getIdentifier("$prefix${id}_title", "string", packageName).takeIf { it != 0 } ?: generic

fun createAiRoutingSettings(context: Context): List<Setting> = listOf(
    Setting(context, Settings.PREF_AI_PROVIDER, R.string.ai_provider_title, R.string.ai_provider_summary) { setting ->
        ListPreference(setting, AiRouter.providers.map { it.label to it.id }, AiRouter.defaultProvider)
    },
) + AiRouter.providers.flatMap { p ->
    listOf(
        Setting(context, Settings.PREF_AI_TOKEN_PREFIX + p.id, context.titleFor("ai_token_", p.id, R.string.ai_token_title),
            if (p.needsToken) R.string.ai_token_summary else R.string.ai_token_optional_summary) { setting ->
            TextInputPreference(setting, "")
        },
        Setting(context, Settings.PREF_AI_MODEL_PREFIX + p.id, context.titleFor("ai_model_", p.id, R.string.ai_model_title)) { setting ->
            ListPreference(setting, p.models.map { it to it }, p.defaultModel)
        },
    )
}

@Composable
fun AiRoutingScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // Only the selected provider's key + model are shown; switching provider re-composes.
    val p = AiRouter.provider(ctx)
    val items = listOf(
        Settings.PREF_AI_PROVIDER,
        Settings.PREF_AI_TOKEN_PREFIX + p.id,
        Settings.PREF_AI_MODEL_PREFIX + p.id,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_ai_routing),
        settings = items,
    )
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AiRoutingScreen {}
        }
    }
}
