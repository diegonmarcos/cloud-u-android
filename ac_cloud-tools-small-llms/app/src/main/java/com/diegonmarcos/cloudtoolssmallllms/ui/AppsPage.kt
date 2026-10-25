package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudtoolssmallllms.Decl
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.data.AppDef
import com.diegonmarcos.cloudtoolssmallllms.data.ModelsData
import com.diegonmarcos.cloudtoolssmallllms.data.Step
import com.diegonmarcos.superapp.uiicons.UiIcons

private fun gb(v: Double): String = java.lang.String.format(java.util.Locale.US, "%.1f", v)

/** Apps ([page] = grid or list): what the small and medium models can build; a tap opens the app's pipeline. */
@Composable
fun AppsPage(model: ToolsModel, page: String) {
    val data = model.models
    val open = data.apps.firstOrNull { it.id == model.appId }
    when {
        open != null -> { BackHandler { model.appId = null }; AppDetail(model, data, open) }
        page == Decl.PAGE_APPS_LIST -> AppsList(model, data)
        else -> AppsGrid(model, data)
    }
}

/** Compact totals of an app, all computed from the data: 4-bit RAM, 16-bit disk and the number of models. */
@Composable
private fun totalsCompact(data: ModelsData, a: AppDef): String = stringResource(R.string.app_totals_compact, gb(data.appRamQ4(a)), gb(data.appDiskFp16(a)), data.appModels(a).size)

@Composable
private fun AppsGrid(model: ToolsModel, data: ModelsData) {
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag(Tags.APPS), contentPadding = PaddingValues(ToolsMetrics.gutter),
        horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap),
    ) {
        items(data.apps, key = { it.id }) { a ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface)
                    .clickable { model.appId = a.id }.padding(ToolsMetrics.gutter).testTag(Tags.app(a.id)),
                verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(UiIcons.vector(a.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.weight(1f))
                    if (data.phoneCapable(a)) Text(stringResource(R.string.app_phone_icon))
                }
                Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(totalsCompact(data, a), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The pipeline as "model → model → model": "|" between the alternatives of a step, an up arrow on an escalation step. */
@Composable
private fun PipelineRow(data: ModelsData, a: AppDef) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        a.pipeline.forEachIndexed { i, s ->
            if (i > 0) Text("→", style = MaterialTheme.typography.labelMedium)
            val text = stepText(s)
            Chip(if (s.escalation) stringResource(R.string.app_escalation_mark, text) else text)
        }
    }
}

private fun stepText(s: Step): String = if (s.models.isEmpty()) s.note.orEmpty().substringBefore(':') else s.models.joinToString(if (s.alternatives) " | " else " + ")

/** Descriptive list: category headers, then per app its number, phone badge, name, pipeline and computed totals. */
@Composable
private fun AppsList(model: ToolsModel, data: ModelsData) {
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.APPS_LIST), contentPadding = PaddingValues(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        data.appCategories.forEach { c ->
            val apps = data.apps.filter { it.category == c.id }
            if (apps.isEmpty()) return@forEach
            item(key = "cat_" + c.id) {
                Row(Modifier.padding(top = ToolsMetrics.gap).testTag(Tags.category(c.id)), verticalAlignment = Alignment.CenterVertically) {
                    Icon(UiIcons.vector(c.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(ToolsMetrics.gap))
                    Text(c.label, style = MaterialTheme.typography.titleMedium)
                }
            }
            items(apps, key = { it.id }) { a ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface)
                        .clickable { model.appId = a.id }.padding(ToolsMetrics.gutter).testTag(Tags.appRow(a.id)),
                    verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${data.apps.indexOf(a) + 1}.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(ToolsMetrics.gap))
                        if (data.phoneCapable(a)) { Text(stringResource(R.string.app_phone_icon)); Spacer(Modifier.width(ToolsMetrics.small)) }
                        Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    PipelineRow(data, a)
                    Text(stringResource(R.string.app_totals, gb(data.appRamQ4(a)), gb(data.appDiskFp16(a)), data.appModels(a).size),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag(Tags.APP_TOTALS))
                    Text(stringResource(R.string.app_models_used, data.appModels(a).joinToString(", ") { it.model }),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One app: its description, the ordered steps with their model chips, the totals, the phone badge and Try in AI. */
@Composable
private fun AppDetail(model: ToolsModel, data: ModelsData, a: AppDef) {
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.APP_DETAIL), contentPadding = PaddingValues(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.vector(a.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(ToolsMetrics.gap))
                Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.appId = null }) { Text(stringResource(R.string.apps_back)) }
            }
            Text(a.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsSteps(a, data)
        item {
            val phone = data.phoneCapable(a)
            Text(stringResource(R.string.app_totals, gb(data.appRamQ4(a)), gb(data.appDiskFp16(a)), data.appModels(a).size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(Tags.APP_TOTALS))
            Text(stringResource(R.string.app_models_used, data.appModels(a).joinToString(", ") { it.model }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(if (phone) R.string.app_phone_yes else R.string.app_phone_no), selected = phone, tag = Tags.PHONE_BADGE)
            }
            val first = data.appModels(a).firstOrNull { it.chat }
            if (first != null) TextButton(onClick = { model.tryInAi(first.model) }, modifier = Modifier.testTag(Tags.TRY_IN_AI)) {
                Text(stringResource(R.string.app_try_in_ai, first.model))
            }
        }
    }
}

private fun LazyListScope.itemsSteps(a: AppDef, data: ModelsData) {
    items(a.pipeline.withIndex().toList(), key = { it.index }) { (i, s) ->
        val m = data.macro(s.macro)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface).padding(ToolsMetrics.gutter),
            verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m != null) Icon(UiIcons.vector(m.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ToolsMetrics.icon))
                Spacer(Modifier.width(ToolsMetrics.gap))
                Text("${i + 1}. ${m?.label ?: s.macro}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip(stringResource(if (data.backend(a, s) == ToolsModel.LOCAL) R.string.backend_local else R.string.backend_openrouter), tag = Tags.stepBackend(i))
            }
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
                s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (s.alternatives) Text(stringResource(R.string.app_alternatives), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.escalation) Text(stringResource(R.string.app_escalation), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                s.models.forEach { id -> Chip(id + (data.row(id)?.let { " · " + gb(it.ramQ4) + " GB" } ?: "")) }
            }
        }
    }
}
