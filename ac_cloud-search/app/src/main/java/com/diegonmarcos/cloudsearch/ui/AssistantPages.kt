@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.Templates
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.cloudsearch.data.ChatFlow
import com.diegonmarcos.cloudsearch.data.Services
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** The AI chat's live state: the open session, what is being sent, the last error, the catalogue. */
class ChatModel(private val services: Services) {
    var session by mutableStateOf(ChatFlow.newSession(services.prefs.model, System.currentTimeMillis()))
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var citations by mutableStateOf<List<String>>(emptyList())
    var models by mutableStateOf<List<Chat.Model>>(emptyList())
    var model by mutableStateOf(services.prefs.model)
    var web by mutableStateOf(services.prefs.web)

    fun pick(id: String) { model = id; services.prefs.model = id }
    fun toggleWeb() { web = !web; services.prefs.web = web }
    fun fresh() { session = ChatFlow.newSession(model, System.currentTimeMillis()); error = null; citations = emptyList() }
    fun open(s: Chat.Session) { session = s; error = null; citations = emptyList(); if (s.model.isNotBlank()) model = s.model }
}

/** One box, four engines: each opens the engine's own results in cloud-browser. */
@Composable
fun WebPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Metrics.gutter),
        verticalArrangement = Arrangement.spacedBy(Metrics.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.assistant_hello), style = MaterialTheme.typography.headlineSmall)
        Text(v.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth().testTag(Tags.SEARCH_BOX),
            placeholder = { Text(stringResource(R.string.search_the_web)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true, shape = RoundedCornerShape(Metrics.corner),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                state.cfg.engines.firstOrNull()?.let { e -> if (q.isNotBlank()) Browser.open(ctx, Templates.fill(e.url, q.trim(), state.cfg.city(state.city))) }
            }),
        )
        state.cfg.engines.forEach { e ->
            OutlinedButton(
                onClick = { if (q.isNotBlank()) Browser.open(ctx, Templates.fill(e.url, q.trim(), state.cfg.city(state.city))) },
                enabled = q.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag(Tags.engine(e.id)),
            ) {
                Icon(Icons.Filled.Language, contentDescription = null)
                Text(stringResource(R.string.search_with, e.label), Modifier.padding(start = Metrics.gap))
            }
        }
        Text(stringResource(R.string.web_opens_in_browser), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ChatPage() {
    val state = LocalState.current
    val chat = state.chat
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (chat.models.isEmpty()) chat.models = withContext(Dispatchers.IO) { state.services.models.models() } }
    LaunchedEffect(chat.session.messages.size) { if (chat.session.messages.isNotEmpty()) list.animateScrollToItem(chat.session.messages.size - 1) }
    val current = chat.models.firstOrNull { it.id == chat.model }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            AssistChip(onClick = { state.sheet = Sheet.SESSIONS }, label = { Text(stringResource(R.string.sessions)) },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) }, modifier = Modifier.testTag(Tags.SESSIONS))
            Box(Modifier.weight(1f)) {
                AssistChip(
                    onClick = { picker = true },
                    label = { Text((current?.name ?: chat.model) + if (current?.nativeWeb == true) " · " + stringResource(R.string.web_native) else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag(Tags.MODEL),
                )
                DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                    if (chat.models.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.models_unavailable)) }, onClick = { picker = false })
                    chat.models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.name + (if (m.nativeWeb) " · " + stringResource(R.string.web_native) else "") + (if (m.free) " · " + stringResource(R.string.free) else "")) },
                            onClick = { chat.pick(m.id); picker = false },
                        )
                    }
                }
            }
            FilterChip(selected = chat.web, onClick = { chat.toggleWeb() }, label = { Text(stringResource(R.string.web)) })
            IconButton(onClick = { chat.fresh() }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_chat)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            if (chat.session.messages.isEmpty()) item {
                Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(chat.session.messages.withIndex().toList(), key = { it.index }) { (_, m) -> Bubble(m) }
            if (chat.citations.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                    Text(stringResource(R.string.sources), style = MaterialTheme.typography.labelMedium)
                    chat.citations.forEach { u ->
                        Row(Modifier.clickable { Browser.open(ctx, u) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null)
                            Text(u, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Metrics.small))
                        }
                    }
                }
            }
            chat.error?.let { e -> item { Text(stringResource(R.string.chat_error, e), color = MaterialTheme.colorScheme.error) } }
        }
        if (chat.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(Metrics.gutter), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Auto-resizing: grows with the text up to six lines, then scrolls.
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f).testTag(Tags.CHAT_INPUT),
                placeholder = { Text(stringResource(R.string.ask_anything)) },
                minLines = 1, maxLines = 6, shape = RoundedCornerShape(Metrics.corner),
            )
            IconButton(
                onClick = {
                    val ask = text.trim()
                    if (ask.isNotEmpty() && !chat.sending) {
                        text = ""
                        chat.sending = true
                        chat.error = null
                        val before = chat.session
                        chat.session = before.copy(messages = before.messages + Chat.Msg("user", ask))
                        scope.launch {
                            try {
                                val out = withContext(Dispatchers.IO) {
                                    ChatFlow.send(ctx, state.services, before, ask, chat.model, chat.web, System.currentTimeMillis())
                                }
                                chat.session = out.session
                                chat.error = out.error
                                chat.citations = out.citations
                            } finally {
                                chat.sending = false
                            }
                        }
                    }
                },
                enabled = !chat.sending,
                modifier = Modifier.testTag(Tags.CHAT_SEND),
            ) { Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.send)) }
        }
    }
}

@Composable
private fun Bubble(m: Chat.Msg) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            m.content,
            Modifier
                .widthIn(max = Metrics.thumb * 5)
                .clip(RoundedCornerShape(Metrics.corner))
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(Metrics.gutter),
            color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Chat sessions grouped Today / Previous 7 Days / Older, newest first. */
@Composable
fun SessionsSheet(state: SearchState) {
    val sessions = remember { state.services.sessions.all() }
    val groups = remember(sessions) { Chat.group(sessions, System.currentTimeMillis(), ZoneId.systemDefault()) }
    ModalBottomSheet(onDismissRequest = { state.sheet = null }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            item { Text(stringResource(R.string.chat_sessions), style = MaterialTheme.typography.titleLarge) }
            if (groups.isEmpty()) item { Text(stringResource(R.string.no_sessions), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            groups.forEach { (bucket, list) ->
                item(key = bucket.name) { Text(stringResource(bucketLabel(bucket)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                items(list, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.gap)).clickable { state.chat.open(s); state.sheet = null }.padding(Metrics.gap)) {
                        Text(s.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.n_messages, s.messages.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun bucketLabel(b: Chat.Bucket): Int = when (b) {
    Chat.Bucket.TODAY -> R.string.today
    Chat.Bucket.PREVIOUS_7_DAYS -> R.string.previous_7_days
    Chat.Bucket.OLDER -> R.string.older
}
