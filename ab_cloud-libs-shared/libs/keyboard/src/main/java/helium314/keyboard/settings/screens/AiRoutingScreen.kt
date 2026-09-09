// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// SuperApp addition — "AI Model Routing": the ONE provider/token/model choice shared by
// Text Enhancements and Grammar check (mode "ai"). Everything here is built from
// build.json::keyboard_ai (AiRouter.providers) — add a provider = one JSON entry.
//
// The translate bar does NOT read this screen: it goes through libs:translate's
// TranslateEngines.client, which each app registers for itself. Nothing here reaches it.

/** `ai_token_<id>_title` / `ai_model_<id>_title` if the provider has its own string, else the generic one. */
private fun Context.titleFor(prefix: String, id: String, generic: Int): Int =
    resources.getIdentifier("$prefix${id}_title", "string", packageName).takeIf { it != 0 } ?: generic

/**
 * Picker/table label: the registry's short readable name, suffixed for open-weight models. The
 * exact id is not lost — it is the table's last column, which is what you copy when something
 * needs the literal identifier. Both the picker and the table use this, so a model reads the
 * same in the list you choose from and the table you compare in.
 */
private fun Context.labelFor(m: AiRouter.Model) = m.name + if (m.open) getString(R.string.ai_model_open_suffix) else ""

/** A provider gets a price table when it has anything to show: a live catalog or baked prices. */
private fun AiRouter.Provider.hasPricing() = catalogUrl != null || models.any { it.baked != null }

fun createAiRoutingSettings(context: Context): List<Setting> = listOf(
    Setting(context, Settings.PREF_AI_PROVIDER, R.string.ai_provider_title, R.string.ai_provider_summary) { setting ->
        ListPreference(setting, AiRouter.providers.map { it.label to it.id }, AiRouter.defaultProvider)
    },
    // The two preambles every feature routed through here prepends, shown on the page that owns the
    // routing. Stores nothing. Text Enhancement and Text Resume each show their OWN composed prompt;
    // this row is the part they have in common and neither of them chose.
    Setting(context, Settings.PREF_AI_PREAMBLE, R.string.ai_preamble_title, R.string.ai_preamble_summary) { setting ->
        PromptPreview(
            setting,
            context.getString(R.string.ai_preamble_rewrite) + "\n\n" + AiRouter.rewritePreamble +
                "\n\n" + context.getString(R.string.ai_preamble_summary_label) + "\n\n" + AiRouter.summaryPreamble,
        )
    },
) + AiRouter.providers.flatMap { p ->
    listOf(
        Setting(context, Settings.PREF_AI_TOKEN_PREFIX + p.id, context.titleFor("ai_token_", p.id, R.string.ai_token_title),
            if (p.needsToken) R.string.ai_token_summary else R.string.ai_token_optional_summary) { setting ->
            TextInputPreference(setting, "")
        },
        Setting(context, Settings.PREF_AI_MODEL_PREFIX + p.id, context.titleFor("ai_model_", p.id, R.string.ai_model_title)) { setting ->
            ListPreference(setting, p.models.sortedWith(byModelSize).map { context.labelFor(it) to it.id }, p.defaultModel)
        },
    ) + if (p.hasPricing()) listOf(
        Setting(context, Settings.PREF_AI_PRICING_PREFIX + p.id, R.string.ai_pricing_title, R.string.ai_pricing_summary) { setting ->
            AiPricingTable(setting, p)
        }
    ) else emptyList()
}

/**
 * The registry and [AiRouter.refreshPricing] both hold prices in USD per million tokens, because
 * that is the unit OpenRouter publishes. The table prints US CENTS per million instead. At the two
 * decimals this column uses, dollars lie: the two cheapest models in the registry, $0.05 and
 * $0.065 per million, both render "0.07" — the same cell for a 30 % price difference — and a model
 * an order of magnitude cheaper would render "0.00", i.e. free. In cents they are 5.00 and 6.50,
 * and the unit still has two decimal places of headroom before it rounds anything real to zero.
 */
private const val CENTS_PER_USD = 100

/** Always two decimals, always a dot: the column's shape is asserted, so it cannot follow the locale. */
private fun cents(v: Double) = String.format(Locale.US, "%.2f", v * CENTS_PER_USD)

/**
 * Presentation order, and ONLY presentation: size, then quantisation, then name. Routing resolves
 * the model id stored in the prefs ([AiRouter.model]), never a row position, so re-sorting here
 * cannot change which model Enhance or Grammar calls.
 *
 * Models whose id publishes no size sort last. Size is the comparison the open-weight rows are
 * here for; the sizeless rows are the closed hosted models, where no parameter count exists to
 * compare against. Sorting them as 0 would put them at the top and imply they are the smallest.
 */
private val byModelSize = compareBy<AiRouter.Model>({ sizeKey(it) }, { quantKey(it) }, { it.name })
private fun sizeKey(m: AiRouter.Model) = m.paramsB ?: Int.MAX_VALUE
/** The lowest precision the model is served at — the worst answer the provider may route you to. */
private fun quantKey(m: AiRouter.Model) = m.quant.minOfOrNull { bitsOf(it) } ?: Int.MAX_VALUE
private fun bitsOf(q: String) = q.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

/**
 * One table cell.
 *
 * Single line, never wrapped, for every cell in the table without exception: the ROW is the model,
 * and one cell wrapping is enough to take that model onto a second line — which is the layout this
 * table has just left. Content wider than the cell is reached by scrolling the row sideways, which
 * is why the widths below are each column's longest value and not a share of the screen.
 */
@Composable
private fun Cell(text: String, modifier: Modifier, color: Color, style: TextStyle, align: TextAlign = TextAlign.Start) =
    Text(text, modifier, color = color, style = style, textAlign = align, maxLines = 1, softWrap = false)

/**
 * Column widths, each the longest value that column can hold, at roughly 7 dp per character for the
 * table's text size. They are read back out of this file by test-keyboard-ai-routing.sh and checked
 * against the registry, so a note or a category list that outgrows its column fails there rather
 * than being silently clipped on a phone. Widening a column costs nothing but sideways scrolling.
 *
 * The model code is last and is a widthIn floor rather than a fixed width: it is the string you copy
 * when something needs the exact identifier, so it is the one column that must never be cut, and
 * nothing sits to its right for an over-long id to push out of line.
 */
private val colName = Modifier.width(208.dp)
private val colPrice = Modifier.width(72.dp)
private val colSize = Modifier.width(56.dp)
private val colQuant = Modifier.width(128.dp)
private val colTrained = Modifier.width(880.dp)
private val colNote = Modifier.width(576.dp)
private val colSlug = Modifier.widthIn(min = 280.dp)

/**
 * The routing table for [p]. Prices are the live catalog's when fetched (refreshed on open once the
 * cache is older than keyboard_ai.catalog_ttl_ms), else the registry's baked ones with their as-of
 * date; every other column is baked and has no runtime refresh (see build.json _doc_refresh).
 *
 * ONE LINE PER MODEL. Eight columns do not fit a phone and the answer is to scroll, not to fold:
 * every model is a single row holding name, both prices, size, quantisation, what it is trained
 * for, the observation and the model code, and the row runs off the right-hand edge.
 *
 * The header and every row are separate Rows sharing ONE scroll state, so the columns stay under
 * their headings however far the table is scrolled. There is exactly one horizontal scroller per
 * row and none inside it: the model code column used to be described as scrollable, but it never
 * had a scroller of its own — it is simply the widest column, at the end of the strip that scrolls.
 * That strip now carries the whole row, so no gesture is contested. Sideways drags belong to the
 * row; vertical drags belong to the settings list, which is the perpendicular axis. What would
 * break that is a scroller nested on the SAME axis, which is why the row is a horizontalScroll and
 * never a LazyRow.
 *
 * Nothing is pinned. A frozen leading column is buildable here — a fixed cell outside the scroller
 * inside the same Row — but the name column's own longest value is 208 dp of a phone's ~336 dp of
 * usable width, so freezing it would leave the other seven columns a third of the screen to scroll
 * in. Unfrozen, a row at rest already shows name, in and out, which is the comparison the page
 * exists for.
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
    // The theme's own content colour — literally what Text uses when no colour is passed. Name and
    // both prices keep it while every other cell is dimmed, so the comparison the page exists for
    // reads first. Never a literal: the dark and Samsung-black themes each supply their own.
    val plain = Color.Unspecified
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // Shown wherever the registry has no value. An explicit mark, never an empty cell: blank in a
    // price or size column reads as zero, and "unknown size" and "0B" are not the same claim.
    val unknown = stringResource(R.string.ai_table_unknown)
    // ONE state shared by the header and every row, or the columns scroll apart from their headings.
    val scroll = rememberScrollState()
    val label = MaterialTheme.typography.labelMedium
    val body = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp)) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        setting.description?.let { Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(scroll)) {
            Cell(stringResource(R.string.ai_pricing_col_model), colName, dim, label)
            Cell(stringResource(R.string.ai_pricing_col_in), colPrice, dim, label, TextAlign.End)
            Cell(stringResource(R.string.ai_pricing_col_out), colPrice, dim, label, TextAlign.End)
            Cell(stringResource(R.string.ai_pricing_col_size), colSize, dim, label)
            Cell(stringResource(R.string.ai_pricing_col_quant), colQuant, dim, label)
            Cell(stringResource(R.string.ai_pricing_col_trained), colTrained, dim, label)
            Cell(stringResource(R.string.ai_pricing_col_note), colNote, dim, label)
            Cell(stringResource(R.string.ai_pricing_col_slug), colSlug, dim, label)
        }
        p.models.sortedWith(byModelSize).forEach { m ->
            val pr = prices?.get(m.id) ?: m.baked
            // Name and both prices stay at full contrast and the rest is dimmed: they are what the
            // page is for, and they are what a row shows before it is scrolled.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(scroll)) {
                Cell(ctx.labelFor(m), colName, plain, body)
                Cell(pr?.prompt?.let { cents(it) } ?: unknown, colPrice, plain, body, TextAlign.End)
                Cell(pr?.completion?.let { cents(it) } ?: unknown, colPrice, plain, body, TextAlign.End)
                Cell(m.paramsB?.let { "${it}B" } ?: unknown, colSize, dim, body)
                Cell(m.quant.joinToString("/").ifEmpty { unknown }, colQuant, dim, body)
                Cell(m.trainedFor.joinToString(", ").ifEmpty { unknown }, colTrained, dim, body)
                Cell(m.note ?: unknown, colNote, dim, body)
                Cell(m.id, colSlug, dim, body)
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
        Settings.PREF_AI_PREAMBLE,
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
