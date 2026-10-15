package com.diegonmarcos.superapp.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One line of an assistant panel's transcript: [role] is [USER], [ASSISTANT] or [NOTE] (a status line: an error, a denial, a route). */
data class AiPanelLine(val role: String, val text: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val NOTE = "note"
    }
}

/** The test tags of an assistant panel; a host that already has tags (Cloud Browser's "browser:agent…") passes them. */
data class AiPanelTags(
    val panel: String = "ai_panel",
    val newChat: String = "ai_panel_new",
    val close: String = "ai_panel_close",
    val summarize: String = "ai_panel_summarize",
    val input: String = "ai_panel_input",
    val send: String = "ai_panel_send",
    val consent: String = AiTags.CONSENT,
    val allow: String = AiTags.CONSENT_ALLOW,
    val deny: String = AiTags.CONSENT_DENY,
)

/**
 * The assistant as a side panel over a page (Cloud Browser): the transcript of an agent that acts on the page, a
 * field, an optional Summarize, New chat and Close, and the consent card. The host owns the conversation (its
 * agent loop decides what a line is); this draws it. Nothing new is sent while an action waits: the consent card
 * is the ONLY place it is decided.
 */
@Composable
fun AiAssistantPanel(
    lines: List<AiPanelLine>,
    busy: Boolean,
    pending: AiConsentRequest?,
    onSend: (String) -> Unit,
    onDecide: (Boolean) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
    theme: AiTheme,
    modifier: Modifier = Modifier,
    onSummarize: (() -> Unit)? = null,
    hint: String = "Ask about this page, or ask it to do something. It asks before it clicks, types, navigates or changes tabs.",
    summarizeLabel: String = "Summarize",
    tags: AiPanelTags = AiPanelTags(),
) {
    val c = theme.colors
    val s = theme.strings
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, pending, busy) {
        val n = lines.size + (if (busy) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    Column(
        modifier.fillMaxSize().background(c.chatBar).border(1.dp, c.glassBorder).padding(12.dp).testTag(tags.panel),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, color = c.text, style = theme.type.style(theme.type.greeting, FontWeight.Bold), modifier = Modifier.weight(1f))
            TextButton(onNewChat, Modifier.testTag(tags.newChat), enabled = !busy && pending == null) { Text(s.newChat) }
            TextButton(onClose, Modifier.testTag(tags.close)) { Text("✕") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty() && pending == null && !busy)
                Text(hint, color = c.text2, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(lines) { _, l -> Line(l, theme) }
                if (busy) item { Text(s.thinking, color = c.accent) }
            }
        }
        pending?.let { req ->
            AiConsentCard(req.sentence, onAllow = { onDecide(true) }, onDeny = { onDecide(false) }, theme = theme, enabled = !busy,
                tag = tags.consent, allowTag = tags.allow, denyTag = tags.deny)
        }
        if (onSummarize != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onSummarize, Modifier.testTag(tags.summarize), enabled = !busy) { Text(summarizeLabel, color = c.text) }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).testTag(tags.input), maxLines = 5,
                placeholder = { Text(s.askAnything) })
            Button({ val t = text.trim(); if (t.isNotEmpty()) { text = ""; onSend(t) } }, Modifier.testTag(tags.send),
                enabled = !busy && pending == null) { Text(s.send) }
        }
    }
}

@Composable
private fun Line(l: AiPanelLine, theme: AiTheme) {
    val c = theme.colors
    when (l.role) {
        AiPanelLine.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(l.text, color = c.onUserBubble, modifier = Modifier.widthIn(max = 280.dp)
                .background(c.userBubble, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(10.dp))
        }
        AiPanelLine.ASSISTANT -> Text(l.text, color = c.text)
        else -> Text(l.text, color = c.text2, style = theme.type.style(theme.type.small))
    }
}
