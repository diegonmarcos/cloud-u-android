package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.Costs
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/** Costs: today's and this month's totals (worker + router, from OpenRouter's usage), this month by task, and every paid call. */
@Composable
fun CostsPage(model: ToolsModel) {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val l = model.ledger
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(ToolsMetrics.gutter),
        verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap),
    ) {
        item {
            Text(stringResource(R.string.costs_title), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
                Total(stringResource(R.string.costs_today), Costs.usd(l.day(now, zone)), Modifier.weight(1f))
                Total(stringResource(R.string.costs_month), Costs.usd(l.month(now, zone)), Modifier.weight(1f))
            }
        }
        val byTask = l.byTask(now, zone)
        if (byTask.isNotEmpty()) item { Text(stringResource(R.string.costs_by_task), style = MaterialTheme.typography.titleSmall) }
        items(byTask, key = { "t-" + it.first }) { (task, calls, usd) ->
            Row(Modifier.fillMaxWidth()) {
                Text(model.cfg.task(task)?.label ?: task, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.costs_calls, calls), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(Costs.usd(usd), style = MaterialTheme.typography.labelMedium)
            }
        }
        item { Text(stringResource(R.string.costs_history), style = MaterialTheme.typography.titleSmall) }
        if (l.entries.isEmpty()) item { Text(stringResource(R.string.costs_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(l.entries.asReversed().take(HISTORY_ROWS)) { e ->
            Row(Modifier.fillMaxWidth()) {
                Text(fmt.format(Date(e.at)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(model.cfg.task(e.task)?.label ?: e.task, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(short(e.model), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f), maxLines = 1)
                Text(Costs.usd(e.usd), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (l.entries.isNotEmpty()) item { TextButton(onClick = { model.clearLedger() }) { Text(stringResource(R.string.costs_clear)) } }
    }
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface).padding(ToolsMetrics.gutter)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private const val HISTORY_ROWS = 200
