package com.diegonmarcos.cloudsearch.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.Section
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.Shown
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogueRepository.Origin
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogueRepository.Priced
import com.diegonmarcos.cloudsearch.ui.IconBtn
import com.diegonmarcos.cloudsearch.ui.LocalGlass
import com.diegonmarcos.cloudsearch.ui.Metrics
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tags the catalogue is driven by (ModelCatalogueShellTest). */
object ModelTags {
    const val PAGE = "model_catalogue"
    const val CLOSE = "model_catalogue_close"
    const val WEB = "model_catalogue_web"
    const val AS_OF = "model_catalogue_as_of"
    fun section(id: String) = "model_section_$id"
    fun row(section: String, id: String) = "model_row_${section}_$id"
    fun reference(section: String) = "model_reference_$section"
}

/**
 * Chat › Search › the model chip: the whole model catalogue as a page. A) Text, B) Search, C) Audio &
 * Speech, D) Visual Media; each section a dense table (Provider · Model · OpenRouter Model ID ·
 * Architecture / Params · License · Input $/1M · Output $/1M (Floor)), Anthropic first as the
 * reference, then one row per provider ranked by price, low to high. A tap on a chat model picks it
 * for the chat (persisted, as the dropdown did) and returns; embeddings, rerank, audio and image rows
 * are browsable and greyed. Prices: the cached ones at once, a refresh when they are a day old.
 */
@Composable
fun ModelCataloguePage(state: SearchState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val chat = state.chat
    BackHandler(onBack = onClose)
    var source by remember { mutableStateOf<ModelCatalogueSource?>(null) }
    var priced by remember { mutableStateOf<Priced?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.IO) { ModelCatalogueSource.of(state.services) }
        val repo = s.prices
        priced = repo?.let { withContext(Dispatchers.IO) { it.stored() } }
        source = s
        if (repo != null && priced?.origin != Origin.CACHED) {
            refreshing = true
            priced = withContext(Dispatchers.IO) { repo.load() }
            refreshing = false
        }
    }
    Column(modifier.fillMaxSize().testTag(ModelTags.PAGE)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.gutter, vertical = Metrics.small), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.models_title), Modifier.weight(1f), color = g.text, style = Type.style(Type.menuTitle, FontWeight.Bold))
            Text(stringResource(R.string.web_search), color = g.text2, style = Type.style(Type.small))
            Switch(chat.web, { chat.toggleWeb() }, Modifier.padding(horizontal = Metrics.small).testTag(ModelTags.WEB),
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent, uncheckedTrackColor = g.field))
            IconBtn(R.drawable.ph_x, stringResource(R.string.close), ModelTags.CLOSE, onClose)
        }
        val cat = source?.catalogue
        val p = priced
        val repo = source?.prices
        Column(Modifier.padding(horizontal = Metrics.gutter)) {
            Text(stringResource(R.string.models_current, chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model),
                color = g.text, style = Type.style(Type.small, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null && repo != null) {
                val origin = stringResource(originLabel(p.origin))
                Text(stringResource(R.string.models_prices_as_of, repo.asOf(p), origin) + if (refreshing) " · " + stringResource(R.string.models_refreshing) else "",
                    color = g.text2, style = Type.style(Type.label), modifier = Modifier.testTag(ModelTags.AS_OF))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.gutter, vertical = Metrics.gap),
            verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            if (source != null && cat == null) Text(stringResource(R.string.models_catalogue_unreadable), color = g.negative, style = Type.style(Type.small))
            cat?.groups?.forEach { group ->
                Text("${group.id}) ${group.label}", color = g.text, style = Type.style(Type.cardTitle, FontWeight.Bold), modifier = Modifier.padding(top = Metrics.gap))
                group.sections.forEach { s ->
                    SectionTable(s, ModelCatalogue.ordered(s, p?.prices, p?.missing.orEmpty()), chat.model) { shown ->
                        if (ModelCatalogue.choose(s, shown) { chat.pick(it) }) onClose()
                    }
                }
            }
            if (cat != null) Text(stringResource(R.string.models_footer), color = g.text2, style = Type.style(Type.label), modifier = Modifier.padding(top = Metrics.gap))
        }
    }
}

private fun originLabel(o: Origin): Int = when (o) {
    Origin.LIVE -> R.string.models_origin_live
    Origin.CACHED -> R.string.models_origin_cached
    Origin.STALE -> R.string.models_origin_stale
    Origin.SNAPSHOT -> R.string.models_origin_snapshot
}

/** One section: its title and note at the screen's width, then its table, scrolled sideways on its own. */
@Composable
private fun SectionTable(s: Section, rows: List<Shown>, current: String, onPick: (Shown) -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Column(Modifier.fillMaxWidth().clip(shape).background(g.card).border(Metrics.hairline, g.tileBorder, shape).padding(Metrics.cardPad).testTag(ModelTags.section(s.id))) {
        Text("${s.id} ${s.label}" + if (s.chat) "" else " · " + stringResource(R.string.models_not_chat),
            color = if (s.chat) g.text else g.text2, style = Type.style(Type.menuItem, FontWeight.SemiBold))
        if (s.note.isNotBlank()) Text(s.note, color = g.text2, style = Type.style(Type.label), modifier = Modifier.padding(top = Metrics.tiny))
        Box(Modifier.fillMaxWidth().padding(top = Metrics.small).horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(TABLE)) {
                Cells(header(), FontWeight.Bold, g.text2)
                if (s.needsReferenceRow) Box(Modifier.alpha(DIM).testTag(ModelTags.reference(s.id))) {
                    Cells(listOf("Anthropic", stringResource(R.string.models_no_anthropic), "", "", "", "", ""), FontWeight.Normal, g.text2)
                }
                if (rows.isEmpty()) Text(stringResource(R.string.models_none), color = g.text2, style = Type.style(Type.small), modifier = Modifier.padding(vertical = Metrics.small))
                rows.forEach { r -> ModelRow(s, r, r.row.id == current && s.chat, onPick) }
            }
        }
    }
}

@Composable
private fun header(): List<String> = listOf(
    stringResource(R.string.models_col_provider), stringResource(R.string.models_col_model), stringResource(R.string.models_col_id),
    stringResource(R.string.models_col_params), stringResource(R.string.models_col_license),
    stringResource(R.string.models_col_input), stringResource(R.string.models_col_output),
)

@Composable
private fun ModelRow(s: Section, r: Shown, current: Boolean, onPick: (Shown) -> Unit) {
    val g = LocalGlass.current
    val name = r.row.name + if (r.listed) "" else " · " + stringResource(R.string.models_not_listed)
    val cells = listOf(
        r.row.provider, name, r.row.id,
        ModelCatalogue.est(r.row.params, r.row.paramsEst), ModelCatalogue.est(r.row.license, r.row.licenseEst),
        ModelCatalogue.inputCell(s, r.price), ModelCatalogue.outputCell(s, r.price),
    )
    Box(
        Modifier.width(TABLE).clip(RoundedCornerShape(Metrics.tileRadius))
            .then(if (current) Modifier.background(g.field) else Modifier)
            .clickable(enabled = r.selectable) { onPick(r) }
            .alpha(if (r.selectable) 1f else DIM)
            .testTag(ModelTags.row(s.id, r.row.id)),
    ) {
        Cells(cells, if (current || r.row.anthropic) FontWeight.SemiBold else FontWeight.Normal, if (current) g.accent else g.text)
    }
}

/** One line of the table: each column its fixed width, one line each, so the columns line up row after row. */
@Composable
private fun Cells(values: List<String>, weight: FontWeight, color: Color) {
    Row(Modifier.width(TABLE).padding(vertical = Metrics.small), horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
        values.forEachIndexed { i, v ->
            Text(v, Modifier.width(COLUMNS[i]), color = color, style = Type.style(Type.small, weight), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Provider · Model · ID · Arch / Params · License · Input · Output (Floor). */
private val COLUMNS: List<Dp> = listOf(78.dp, 130.dp, 190.dp, 120.dp, 110.dp, 70.dp, 130.dp)
private val TABLE: Dp = COLUMNS.fold(0.dp) { a, b -> a + b } + 4.dp * (COLUMNS.size - 1)
private const val DIM = 0.45f
