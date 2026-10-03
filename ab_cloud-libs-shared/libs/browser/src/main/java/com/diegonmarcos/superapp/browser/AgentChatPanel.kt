package com.diegonmarcos.superapp.browser

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #823 (deferred #802 I9) the assistant as a Compose side panel over the page, replacing the
 * ask dialog + answer panel: the conversation, a field, Summarize, New chat — and the consent
 * card. The confirm-before-mutate policy is unchanged: a mutating tool stops the turn
 * ([AgentLoop]); the card names the exact action and site; nothing runs until he taps Allow,
 * and Deny tells the model "denied". There is still no allow outside this card.
 */

/** The panel's state; the host keeps one so the conversation survives closing the panel. */
class AgentPanelState {
    val lines = mutableStateListOf<AgentLine>()
    var pending by mutableStateOf<AgentPending?>(null)
    var busy by mutableStateOf(false)
}

@Composable
fun AgentChatPanel(
    state: AgentPanelState,
    onSend: (String) -> Unit,
    onDecide: (AgentPending, Boolean) -> Unit,
    onSummarize: () -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.lines.size, state.pending, state.busy) {
        val n = state.lines.size + (if (state.busy) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    Column(
        Modifier.fillMaxSize().background(p.surface).border(1.dp, p.hairline).padding(12.dp).testTag("browser:agent"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Assistant", color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onNewChat, Modifier.testTag("browser:agent:new"), enabled = !state.busy && state.pending == null) { Text("New chat") }
            TextButton(onClose, Modifier.testTag("browser:agent:close")) { Text("✕") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.lines.isEmpty() && state.pending == null && !state.busy)
                Text("Ask about this page, or ask it to do something. It asks before it clicks, types, navigates or changes tabs.",
                    color = p.textSecondary, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.lines) { l -> Line(l) }
                if (state.busy) item { Text("Thinking…", color = p.accent) }
            }
        }
        state.pending?.let { pend ->
            Column(
                Modifier.fillMaxWidth().background(p.surfaceSelected, RoundedCornerShape(12.dp)).padding(12.dp).testTag("browser:agent:confirm"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Allow this action?", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(pend.sentence, color = p.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ onDecide(pend, false) }, Modifier.testTag("browser:agent:deny"), enabled = !state.busy) { Text("Deny") }
                    Button({ onDecide(pend, true) }, Modifier.testTag("browser:agent:allow"), enabled = !state.busy) { Text("Allow") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onSummarize, Modifier.testTag("browser:agent:summarize"), enabled = !state.busy) { Text("Summarize") }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).testTag("browser:agent:input"), maxLines = 5,
                placeholder = { Text("Ask the assistant") })
            Button({ val t = text.trim(); if (t.isNotEmpty()) { text = ""; onSend(t) } }, Modifier.testTag("browser:agent:send"),
                enabled = !state.busy && state.pending == null) { Text("Send") }
        }
    }
}

@Composable
private fun Line(l: AgentLine) {
    val p = LocalKitPalette.current
    when (l.role) {
        AgentLine.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(l.text, color = p.tileInk, modifier = Modifier.widthIn(max = 280.dp)
                .background(p.textPrimary, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(10.dp))
        }
        AgentLine.ASSISTANT -> Text(l.text, color = p.textPrimary)
        else -> Text(l.text, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
