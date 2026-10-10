package com.diegonmarcos.superapp.modelcataloguepage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.Section
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.Shown
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository.Origin
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository.Priced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tags the catalogue is driven by (Cloud Search's ModelCatalogueShellTest). CLOSE and WEB tag the host's own actions. */
object ModelTags {
    const val PAGE = "model_catalogue"
    const val CLOSE = "model_catalogue_close"
    const val WEB = "model_catalogue_web"
    const val AS_OF = "model_catalogue_as_of"
    fun section(id: String) = "model_section_$id"
    fun row(section: String, id: String) = "model_row_${section}_$id"
    fun reference(section: String) = "model_reference_$section"
}

/** How the page looks: the host's colours, type and spacing (Cloud Search hands it its glass theme). */
@Immutable
data class CatalogueTheme(
    val text: Color, val text2: Color, val accent: Color, val field: Color, val card: Color, val border: Color, val negative: Color,
    val title: TextStyle, val groupTitle: TextStyle, val sectionTitle: TextStyle, val body: TextStyle, val label: TextStyle,
    val gutter: Dp, val small: Dp, val gap: Dp, val tiny: Dp, val cardRadius: Dp, val cardPad: Dp, val tileRadius: Dp, val hairline: Dp,
)

/** What the page says: the host's own strings (its language policy, its resources). */
class CatalogueWords(
    val title: String,
    val current: (String) -> String,
    val pricesAsOf: (date: String, origin: String) -> String,
    val origin: (Origin) -> String,
    val refreshing: String,
    val unreadable: String,
    val footer: String,
    val notChat: String,
    val noAnthropic: String,
    val none: String,
    val notListed: String,
    /** Provider · Model · OpenRouter Model ID · Architecture / Params · License · Input · Output (Floor). */
    val columns: List<String>,
)

/**
 * The model catalogue as a page (moved here from Cloud Search, which hosts it unchanged). A) Text,
 * B) Search, C) Audio & Speech, D) Visual Media — or only the [sections] a host asks for; each
 * section a dense table (Provider · Model · OpenRouter Model ID · Architecture / Params · License ·
 * Input $/1M · Output $/1M (Floor)), Anthropic first as the reference, then one row per provider
 * ranked by price, low to high. A tap on a chat model hands its id to [onPick]; embeddings, rerank,
 * audio and image rows are browsable and greyed. Prices: the cached ones at once, a refresh when they
 * are a day old. [ready] false = the host is still reading the bundled selection; [catalogue] null
 * once ready = it could not, which the page says rather than drawing an empty table.
 */
@Composable
fun ModelCataloguePage(
    catalogue: ModelCatalogue.Catalogue?,
    repo: ModelCatalogueRepository?,
    ready: Boolean,
    current: String,
    currentName: String,
    theme: CatalogueTheme,
    words: CatalogueWords,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    sections: Set<String>? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val g = theme
    var priced by remember(repo) { mutableStateOf<Priced?>(null) }
    var refreshing by remember(repo) { mutableStateOf(false) }
    LaunchedEffect(repo) {
        if (repo == null) return@LaunchedEffect
        priced = withContext(Dispatchers.IO) { repo.stored() }
        if (priced?.origin != Origin.CACHED) {
            refreshing = true
            priced = withContext(Dispatchers.IO) { repo.load() }
            refreshing = false
        }
    }
    val cat = remember(catalogue, sections) { catalogue?.only(sections) }
    Column(modifier.fillMaxSize().testTag(ModelTags.PAGE)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = g.gutter, vertical = g.small), verticalAlignment = Alignment.CenterVertically) {
            Text(words.title, Modifier.weight(1f), color = g.text, style = g.title.copy(fontWeight = FontWeight.Bold))
            actions()
        }
        val p = priced
        Column(Modifier.padding(horizontal = g.gutter)) {
            Text(words.current(currentName), color = g.text, style = g.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null && repo != null) {
                Text(words.pricesAsOf(repo.asOf(p), words.origin(p.origin)) + if (refreshing) " · " + words.refreshing else "",
                    color = g.text2, style = g.label, modifier = Modifier.testTag(ModelTags.AS_OF))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = g.gutter, vertical = g.gap),
            verticalArrangement = Arrangement.spacedBy(g.gap)) {
            if (ready && cat == null) Text(words.unreadable, color = g.negative, style = g.body)
            cat?.groups?.forEach { group ->
                Text("${group.id}) ${group.label}", color = g.text, style = g.groupTitle.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = g.gap))
                group.sections.forEach { s ->
                    SectionTable(g, words, s, ModelCatalogue.ordered(s, p?.prices, p?.missing.orEmpty()), current) { shown ->
                        ModelCatalogue.choose(s, shown, onPick)
                    }
                }
            }
            if (cat != null) Text(words.footer, color = g.text2, style = g.label, modifier = Modifier.padding(top = g.gap))
        }
    }
}

/** One section: its title and note at the screen's width, then its table, scrolled sideways on its own. */
@Composable
private fun SectionTable(g: CatalogueTheme, words: CatalogueWords, s: Section, rows: List<Shown>, current: String, onPick: (Shown) -> Unit) {
    val shape = RoundedCornerShape(g.cardRadius)
    Column(Modifier.fillMaxWidth().clip(shape).background(g.card).border(g.hairline, g.border, shape).padding(g.cardPad).testTag(ModelTags.section(s.id))) {
        Text("${s.id} ${s.label}" + if (s.chat) "" else " · " + words.notChat,
            color = if (s.chat) g.text else g.text2, style = g.sectionTitle.copy(fontWeight = FontWeight.SemiBold))
        if (s.note.isNotBlank()) Text(s.note, color = g.text2, style = g.label, modifier = Modifier.padding(top = g.tiny))
        Box(Modifier.fillMaxWidth().padding(top = g.small).horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(TABLE)) {
                Cells(g, words.columns, FontWeight.Bold, g.text2)
                if (s.needsReferenceRow) Box(Modifier.alpha(DIM).testTag(ModelTags.reference(s.id))) {
                    Cells(g, listOf("Anthropic", words.noAnthropic, "", "", "", "", ""), FontWeight.Normal, g.text2)
                }
                if (rows.isEmpty()) Text(words.none, color = g.text2, style = g.body, modifier = Modifier.padding(vertical = g.small))
                rows.forEach { r -> ModelRow(g, words, s, r, r.row.id == current && s.chat, onPick) }
            }
        }
    }
}

@Composable
private fun ModelRow(g: CatalogueTheme, words: CatalogueWords, s: Section, r: Shown, current: Boolean, onPick: (Shown) -> Unit) {
    val name = r.row.name + if (r.listed) "" else " · " + words.notListed
    val cells = listOf(
        r.row.provider, name, r.row.id,
        ModelCatalogue.est(r.row.params, r.row.paramsEst), ModelCatalogue.est(r.row.license, r.row.licenseEst),
        ModelCatalogue.inputCell(s, r.price), ModelCatalogue.outputCell(s, r.price),
    )
    Box(
        Modifier.width(TABLE).clip(RoundedCornerShape(g.tileRadius))
            .then(if (current) Modifier.background(g.field) else Modifier)
            .clickable(enabled = r.selectable) { onPick(r) }
            .alpha(if (r.selectable) 1f else DIM)
            .testTag(ModelTags.row(s.id, r.row.id)),
    ) {
        Cells(g, cells, if (current || r.row.anthropic) FontWeight.SemiBold else FontWeight.Normal, if (current) g.accent else g.text)
    }
}

/** One line of the table: each column its fixed width, one line each, so the columns line up row after row. */
@Composable
private fun Cells(g: CatalogueTheme, values: List<String>, weight: FontWeight, color: Color) {
    Row(Modifier.width(TABLE).padding(vertical = g.small), horizontalArrangement = Arrangement.spacedBy(g.small)) {
        values.forEachIndexed { i, v ->
            Text(v, Modifier.width(COLUMNS[i]), color = color, style = g.body.copy(fontWeight = weight), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Provider · Model · ID · Arch / Params · License · Input · Output (Floor). */
private val COLUMNS: List<Dp> = listOf(78.dp, 130.dp, 190.dp, 120.dp, 110.dp, 70.dp, 130.dp)
private val TABLE: Dp = COLUMNS.fold(0.dp) { a, b -> a + b } + 4.dp * (COLUMNS.size - 1)
private const val DIM = 0.45f
