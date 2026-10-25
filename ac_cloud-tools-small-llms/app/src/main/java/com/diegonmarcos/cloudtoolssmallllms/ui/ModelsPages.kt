package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.data.ModelRow
import com.diegonmarcos.cloudtoolssmallllms.data.ModelsData
import com.diegonmarcos.superapp.uiicons.UiIcons

/** The four views of the Models section, picked by the view switcher. */
enum class ModelsView(val icon: String, val label: Int, val tag: String) {
    TABLE("table_chart", R.string.view_table, "table"),
    GRID("apps", R.string.view_grid, "grid"),
    DETAIL("description", R.string.view_detail, "detail"),
    MACRO("account_tree", R.string.view_macro, "macro"),
}

private fun gb(v: Double): String = java.lang.String.format(java.util.Locale.US, "%.1f", v)

/** The line every view starts with: the count and the ~20% overhead assumption of the RAM figures. */
@Composable
private fun Assumption(data: ModelsData, count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny)) {
        Text(stringResource(R.string.models_count, count), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.models_overhead, data.overheadPct), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(Tags.OVERHEAD))
    }
}

/** The view switcher (icon + label chips) over the page's content: Table | Grid | Details | Macro. */
@Composable
private fun ViewSwitcher(view: ModelsView, onPick: (ModelsView) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ToolsMetrics.gutter, vertical = ToolsMetrics.gap), horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        ModelsView.values().forEach { v ->
            val sel = v == view
            val fg = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier.clip(RoundedCornerShape(ToolsMetrics.corner)).background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(v) }.padding(horizontal = ToolsMetrics.gap, vertical = ToolsMetrics.small).testTag(Tags.view(v.tag)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(UiIcons.vector(v.icon), contentDescription = null, tint = fg, modifier = Modifier.size(ToolsMetrics.icon))
                Spacer(Modifier.width(ToolsMetrics.small))
                Text(stringResource(v.label), style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
            }
        }
    }
}

/** One Models page ([page] = small | medium | retrieval): the view switcher, then its rows as the picked view. */
@Composable
fun ModelsPage(data: ModelsData, page: String, view: ModelsView, onView: (ModelsView) -> Unit) {
    val rows = data.page(page)
    Column(Modifier.fillMaxSize()) {
        ViewSwitcher(view, onView)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                ModelsView.TABLE -> ModelsTable(data, page, rows)
                ModelsView.GRID -> ModelsGrid(data, rows)
                ModelsView.DETAIL -> ModelsDetail(data, rows)
                ModelsView.MACRO -> ModelsMacro(data, rows)
            }
        }
    }
}

private enum class Col(val label: Int, val width: Int, val key: (ModelRow) -> Comparable<*>) {
    ROLE(R.string.col_role, 150, { it.role }),
    MACRO(R.string.col_macro, 110, { it.macro }),
    MODEL(R.string.col_model, 210, { it.model }),
    TASK(R.string.col_task, 260, { it.task.orEmpty() }),
    PARAMS(R.string.col_params, 64, { it.paramsB }),
    DISK(R.string.col_disk, 72, { it.diskFp16 }),
    RAM16(R.string.col_ram16, 72, { it.ramFp16 }),
    RAM8(R.string.col_ram8, 72, { it.ramQ8 }),
    RAM4(R.string.col_ram4, 72, { it.ramQ4 }),
}

private fun Col.cell(r: ModelRow): String = when (this) {
    Col.ROLE -> r.role
    Col.MACRO -> r.macro
    Col.MODEL -> r.model
    Col.TASK -> r.task.orEmpty()
    Col.PARAMS -> r.params
    Col.DISK -> gb(r.diskFp16)
    Col.RAM16 -> gb(r.ramFp16)
    Col.RAM8 -> gb(r.ramQ8)
    Col.RAM4 -> gb(r.ramQ4)
}

/** Table view: every column, sortable by tapping a header (again = reverse). */
@Composable
private fun ModelsTable(data: ModelsData, page: String, rows: List<ModelRow>) {
    var sort by rememberSaveable(page) { mutableStateOf(Col.PARAMS.name) }
    var desc by rememberSaveable(page) { mutableStateOf(false) }
    val col = Col.valueOf(sort)
    @Suppress("UNCHECKED_CAST")
    val sorted = rows.sortedWith(compareBy<ModelRow> { col.key(it) as Comparable<Any> }.let { if (desc) it.reversed() else it })
    val h = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.TABLE), contentPadding = PaddingValues(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        item { Assumption(data, rows.size) }
        item {
            Row(Modifier.horizontalScroll(h)) {
                Col.values().forEach { c ->
                    val arrow = if (c == col) (if (desc) " v" else " ^") else ""
                    Text(stringResource(c.label) + arrow, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = if (c == col) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(c.width.dp).clickable { if (c == col) desc = !desc else { sort = c.name; desc = false } }
                            .testTag(Tags.header(c.name.lowercase())).padding(vertical = ToolsMetrics.gap))
                }
            }
        }
        items(sorted, key = { it.model }) { r ->
            Row(Modifier.horizontalScroll(h).fillMaxWidth().testTag(Tags.row(r.model)).padding(vertical = ToolsMetrics.small)) {
                Col.values().forEach { c -> Text(c.cell(r), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(c.width.dp)) }
            }
        }
    }
}

/** One grid card: role icon, model, role, parameters and 4-bit RAM. */
@Composable
private fun GridCard(data: ModelsData, r: ModelRow) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface)
            .padding(ToolsMetrics.gutter).testTag(Tags.card(r.model)),
        verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny),
    ) {
        Icon(UiIcons.vector(data.role(r.role).icon), contentDescription = r.role, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Text(r.model, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(r.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.card_params_ram, r.params, gb(r.ramQ4)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/** Icon grid view: one card per model. */
@Composable
private fun ModelsGrid(data: ModelsData, rows: List<ModelRow>) {
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag(Tags.GRID), contentPadding = PaddingValues(ToolsMetrics.gutter),
        horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Assumption(data, rows.size) }
        items(rows, key = { it.model }) { r -> GridCard(data, r) }
    }
}

/** One descriptive card: role, what it is for, every memory figure and the quantization guidance. */
@Composable
private fun DetailCard(data: ModelsData, r: ModelRow) {
    val role = data.role(r.role)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface)
            .padding(ToolsMetrics.gutter).testTag(Tags.card(r.model)),
        verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(UiIcons.vector(role.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(ToolsMetrics.gap))
            Column(Modifier.weight(1f)) {
                Text(r.model, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(r.role + " · " + r.params, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(r.task ?: role.purpose, style = MaterialTheme.typography.bodySmall)
        if (r.task != null) Text(role.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.detail_memory, gb(r.diskFp16), gb(r.ramFp16), gb(r.ramQ8), gb(r.ramQ4)), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.detail_quant, gb(r.ramQ4), gb(r.ramQ8)) + " " + data.guidance(r.tier),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Descriptive view: one descriptive card per model. */
@Composable
private fun ModelsDetail(data: ModelsData, rows: List<ModelRow>) {
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.DETAIL), contentPadding = PaddingValues(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        item { Assumption(data, rows.size) }
        items(rows, key = { it.model }) { r -> DetailCard(data, r) }
    }
}

/** A macro task's header inside a list: its icon, label and one-line meaning. */
private fun LazyListScope.macroHeader(data: ModelsData, id: String) {
    val m = data.macro(id) ?: return
    item(key = "macro_$id") {
        Row(Modifier.padding(top = ToolsMetrics.gap).testTag(Tags.macro(id)), verticalAlignment = Alignment.CenterVertically) {
            Icon(UiIcons.vector(m.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(ToolsMetrics.gap))
            Column {
                Text(m.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(m.blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Macro view: the cards grouped by macro task, in the order build data declares the macros. */
@Composable
private fun ModelsMacro(data: ModelsData, rows: List<ModelRow>) {
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.MACRO), contentPadding = PaddingValues(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        item { Assumption(data, rows.size) }
        data.macros.forEach { m ->
            val group = rows.filter { it.macro == m.id }
            if (group.isNotEmpty()) {
                macroHeader(data, m.id)
                items(group, key = { it.model }) { r -> DetailCard(data, r) }
            }
        }
    }
}
