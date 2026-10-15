package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.Costs
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue

/**
 * Tasks - "What I can do": every declared task as a dense row with its one-line description, an
 * example, its worker model, that model's $/1M in and out from the shared catalogue and the
 * estimated cost of a typical call. Try puts the example in the chat box, pinned to that task.
 */
@Composable
fun TasksPage(model: ToolsModel) {
    @Suppress("UNUSED_VARIABLE") val v = model.pricesVersion
    val reg = model.registry()
    val problems = reg.problems()
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(ToolsMetrics.gutter),
        verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap),
    ) {
        item {
            Text(stringResource(R.string.tasks_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.tasks_prices_as_of, model.asOf.ifBlank { model.catalogue.asOf() }, model.cfg.typicalIn, model.cfg.typicalOut),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (problems.isNotEmpty()) Text(stringResource(R.string.tasks_problems, problems.joinToString("; ")),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        items(reg.entries, key = { it.task.id }) { e ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface)
                    .clickable { model.chat.draft = e.task.example; model.pinned = e.task.id; model.section = "ai" }
                    .padding(ToolsMetrics.gutter).testTag(Tags.task(e.task.id)),
                verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(IconCatalog.vector(e.task.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ToolsMetrics.icon))
                    Spacer(Modifier.width(ToolsMetrics.gap))
                    Text(e.task.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        if (e.estimate != null) stringResource(R.string.tasks_estimate, Costs.usd(e.estimate)) else stringResource(R.string.tasks_unpriced),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Text(e.task.blurb, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.tasks_example, e.task.example), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(short(e.worker), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    e.price?.let {
                        Text(stringResource(R.string.tasks_price, ModelCatalogue.usd(it.inPerM), ModelCatalogue.usd(it.outPerM)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
