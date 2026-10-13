package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.Costs
import com.diegonmarcos.cloudtoolssmallllms.data.Msg

/**
 * Chat: the conversation, each answer with its chip (task · model · cost, and the router's own
 * cost), a question back with candidate chips when the router is unsure, and the input with the
 * task row above it (Auto = the router decides; a task pins the next send to it).
 */
@Composable
fun ChatPage(model: ToolsModel) {
    val list = rememberLazyListState()
    LaunchedEffect(model.messages.size) { if (model.messages.isNotEmpty()) list.animateScrollToItem(model.messages.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model.messages.isEmpty()) Empty(model)
            LazyColumn(
                Modifier.fillMaxSize(), state = list,
                contentPadding = PaddingValues(ToolsMetrics.gutter),
                verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap),
            ) {
                itemsIndexed(model.messages, key = { _, m -> m.id }) { i, m -> Line(model, m, i) }
                if (model.busy) item { Working() }
            }
        }
        Input(model)
    }
}

@Composable
private fun Empty(model: ToolsModel) {
    Column(Modifier.fillMaxWidth().padding(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.chat_empty_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!model.tokenSet) Text(stringResource(R.string.chat_no_token), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Working() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        CircularProgressIndicator(Modifier.padding(ToolsMetrics.tiny).widthIn(max = ToolsMetrics.icon), strokeWidth = ToolsMetrics.tiny)
        Text(stringResource(R.string.chat_working), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(model: ToolsModel, m: Msg, index: Int) {
    when (m.kind) {
        Msg.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Bubble(m.text, MaterialTheme.colorScheme.primaryContainer)
        }
        Msg.ANSWER -> Answer(model, m, index)
        Msg.CLARIFY -> Clarify(model, m)
        else -> Column {
            Text(stringResource(R.string.chat_failed, m.text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (m.routerCost > 0) Text(stringResource(R.string.chip_router, short(m.routerModel), Costs.usd(m.routerCost)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Bubble(text: String, color: androidx.compose.ui.graphics.Color) {
    SelectionContainer {
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = ToolsMetrics.bubbleMax).clip(RoundedCornerShape(ToolsMetrics.corner)).background(color)
                .padding(horizontal = ToolsMetrics.gutter, vertical = ToolsMetrics.gap),
        )
    }
}

@Composable
private fun Answer(model: ToolsModel, m: Msg, index: Int) {
    val clip = LocalClipboardManager.current
    val label = model.cfg.task(m.task)?.label ?: m.task
    Column(Modifier.testTag(Tags.answer(index)), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        Bubble(m.text, MaterialTheme.colorScheme.surfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
            Chip(stringResource(R.string.chip_cost, label, short(m.model), Costs.usd(m.cost)), icon = model.cfg.task(m.task)?.icon)
            if (m.routerModel.isNotEmpty()) {
                Text(
                    stringResource(R.string.chip_router, short(m.routerModel), Costs.usd(m.routerCost)) + " · " +
                        stringResource(R.string.chip_confidence, Math.round(m.confidence * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { clip.setText(AnnotatedString(m.text)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.chat_copy), modifier = androidx.compose.ui.Modifier.widthIn(max = ToolsMetrics.icon))
            }
        }
    }
}

@Composable
private fun Clarify(model: ToolsModel, m: Msg) {
    var all by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        Text(m.text, style = MaterialTheme.typography.bodyMedium)
        val ids = if (all) model.cfg.tasks.map { it.id } else m.candidates
        LazyRow(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
            items(ids) { id ->
                val t = model.cfg.task(id) ?: return@items
                Chip(t.label, icon = t.icon, tag = Tags.task(id)) { model.pick(m.request, id) }
            }
            if (!all) item { Chip(stringResource(R.string.chat_pick_other)) { all = true } }
        }
        if (m.routerCost > 0) Text(stringResource(R.string.chip_router, short(m.routerModel), Costs.usd(m.routerCost)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Input(model: ToolsModel) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(ToolsMetrics.gap), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
            item { Chip(stringResource(R.string.chat_task_auto), selected = model.pinned == null, tag = Tags.task("auto")) { model.pinned = null } }
            items(model.cfg.tasks) { t ->
                Chip(t.label, selected = model.pinned == t.id, icon = t.icon) { model.pinned = if (model.pinned == t.id) null else t.id }
            }
            item {
                IconButton(onClick = { model.clearChat() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.chat_clear))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = model.draft, onValueChange = { model.draft = it },
                modifier = Modifier.weight(1f).testTag(Tags.INPUT),
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium, maxLines = 6,
            )
            IconButton(onClick = { model.send() }, enabled = !model.busy && model.draft.isNotBlank(), modifier = Modifier.testTag(Tags.SEND)) {
                Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}

/** "mistralai/mistral-small-3.2-24b-instruct" → "mistral-small-3.2-24b-instruct": the chip's model name. */
fun short(model: String): String = model.substringAfter('/').ifBlank { model }
