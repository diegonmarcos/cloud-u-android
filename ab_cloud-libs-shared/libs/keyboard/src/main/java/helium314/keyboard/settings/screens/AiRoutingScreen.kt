// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.util.Date

// SuperApp addition — "AI Model Routing": the ONE provider/token/model choice shared by
// Text Enhancements, Grammar check (mode "ai") and translation. Everything here is built
// from build.json::keyboard_ai (AiRouter.providers) — add a provider = one JSON entry.

/** `ai_token_<id>_title` / `ai_model_<id>_title` if the provider has its own string, else the generic one. */
private fun Context.titleFor(prefix: String, id: String, generic: Int): Int =
    resources.getIdentifier("$prefix${id}_title", "string", packageName).takeIf { it != 0 } ?: generic

/** Picker/table label: the exact model id, suffixed for open-weight models. */
private fun Context.labelFor(m: AiRouter.Model) = m.id + if (m.open) getString(R.string.ai_model_open_suffix) else ""

/** A provider gets a price table when it has anything to show: a live catalog or baked prices. */
private fun AiRouter.Provider.hasPricing() = catalogUrl != null || models.any { it.baked != null }

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
            ListPreference(setting, p.models.map { context.labelFor(it) to it.id }, p.defaultModel)
        },
    ) + if (p.hasPricing()) listOf(
        Setting(context, Settings.PREF_AI_PRICING_PREFIX + p.id, R.string.ai_pricing_title, R.string.ai_pricing_summary) { setting ->
            AiPricingTable(setting, p)
        }
    ) else emptyList()
}

/** "$0.075", "$2.5", "$1" — up to 3 decimals, no trailing zeros; "–" when unknown. */
private fun usd(v: Double?) = v?.let { "$" + BigDecimal(it).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() } ?: "–"

/**
 * Per-model prompt/completion $/M for [p]: live catalog prices when fetched (refreshed on open once
 * the cache is older than keyboard_ai.catalog_ttl_ms), else the registry's baked prices with their as-of date.
 */
@Composable
private fun AiPricingTable(setting: Setting, p: AiRouter.Provider) {
    val ctx = LocalContext.current
    var live by remember(p.id) { mutableStateOf(AiRouter.livePricing(ctx, p)) }
    LaunchedEffect(p.id) {
        if (AiRouter.pricingStale(ctx, p)) {
            withContext(Dispatchers.IO) { runCatching { AiRouter.refreshPricing(ctx, p) } }
                .onFailure { Log.w("AiRouter", "catalog refresh failed: ${it.message}") }
            live = AiRouter.livePricing(ctx, p)
        }
    }
    val prices = live?.second
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val col = Modifier.width(64.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp)) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        setting.description?.let { Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.padding(top = 6.dp)) {
            Text(stringResource(R.string.ai_pricing_col_model), Modifier.weight(1f), color = dim, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.ai_pricing_col_in), col, color = dim, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            Text(stringResource(R.string.ai_pricing_col_out), col, color = dim, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        p.models.forEach { m ->
            val pr = prices?.get(m.id) ?: m.baked
            Row(Modifier.padding(top = 2.dp)) {
                Text(ctx.labelFor(m), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(usd(pr?.prompt), col, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                Text(usd(pr?.completion), col, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        }
        val note = live?.first?.let { stringResource(R.string.ai_pricing_live, p.label, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
            ?: stringResource(R.string.ai_pricing_baked, p.pricingAsOf ?: "?", p.label)
        Text(note, Modifier.padding(top = 6.dp), color = dim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AiRoutingScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // Only the selected provider's key, model and prices are shown; switching provider re-composes.
    val p = AiRouter.provider(ctx)
    val items = listOf(
        Settings.PREF_AI_PROVIDER,
        Settings.PREF_AI_TOKEN_PREFIX + p.id,
        Settings.PREF_AI_MODEL_PREFIX + p.id,
    ) + if (p.hasPricing()) listOf(Settings.PREF_AI_PRICING_PREFIX + p.id) else emptyList()
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
