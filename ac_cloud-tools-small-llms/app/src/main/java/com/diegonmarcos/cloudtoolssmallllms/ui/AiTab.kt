package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiStrings
import com.diegonmarcos.superapp.ai.materialAiTheme

/**
 * The AI tab: libs:ai's chat page over this app's own router backend ([ToolsModel.router]'s turn: the router
 * picks the task and the small model, the worker answers, every answer names its task, model and cost).
 * The page is the lib's; what stays here is the task row above the input (Auto = the router decides, a task
 * pins the next send to it) and the missing-token note.
 */
@Composable
fun AiTab(model: ToolsModel) {
    val theme = materialAiTheme(AiStrings(
        hello = stringResource(R.string.chat_empty_title), helloSub = stringResource(R.string.chat_empty_body),
        askAnything = stringResource(R.string.chat_hint), send = stringResource(R.string.chat_send),
        thinking = stringResource(R.string.chat_working), chatError = stringResource(R.string.chat_failed),
    ))
    AiChatPage(model.chat, theme, Modifier.imePadding(), showModel = false, aboveInput = { TaskRow(model) })
}

@Composable
private fun TaskRow(model: ToolsModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ToolsMetrics.gap), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        if (!model.tokenSet) Text(stringResource(R.string.chat_no_token), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
            item { Chip(stringResource(R.string.chat_task_auto), selected = model.pinned == null, tag = Tags.task("auto")) { model.pinned = null } }
            items(model.cfg.tasks) { t ->
                Chip(t.label, selected = model.pinned == t.id, icon = t.icon) { model.pinned = if (model.pinned == t.id) null else t.id }
            }
        }
    }
}

/** "mistralai/mistral-small-3.2-24b-instruct" → "mistral-small-3.2-24b-instruct": the chip's model name. */
fun short(model: String): String = model.substringAfter('/').ifBlank { model }
