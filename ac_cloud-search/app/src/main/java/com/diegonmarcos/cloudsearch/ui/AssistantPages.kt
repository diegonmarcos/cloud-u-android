package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/**
 * The mockup's one Search page: Sessions and the model on top; until the chat has a message, the
 * greeting and one box per declared engine (each opens that engine's own results in cloud-browser);
 * then the conversation. The Gemini-style input is pinned above the nav.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val chat = state.chat
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (chat.models.isEmpty()) chat.models = withContext(Dispatchers.IO) { state.services.models.models() } }
    // With the keyboard up the nav is gone (SearchShell), so the input sits right on the keyboard.
    val bottom = if (WindowInsets.isImeVisible) Metrics.small else Metrics.contentBottom
    Column(Modifier.fillMaxSize().padding(top = Metrics.contentTop, bottom = bottom)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.gutter, vertical = Metrics.small), verticalAlignment = Alignment.CenterVertically) {
            Chip(stringResource(R.string.sessions), Tags.SESSIONS, icon = R.drawable.ph_list_dashes) { state.menu = Menu.SESSIONS }
            Spacer(Modifier.weight(1f))
            ModelSelect(chat)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.session.messages.isEmpty() && !chat.sending) Welcome(v)
            else Conversation(chat)
        }
        ChatBar(
            text, { text = it }, sending = chat.sending,
            onNew = { chat.fresh() },
            onSend = {
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
        )
    }
}

/** The greeting and the four engine boxes. */
@Composable
private fun Welcome(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val g = LocalGlass.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.gutter, vertical = Metrics.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.cardPad),
    ) {
        Box(Modifier.size(Metrics.robot).clip(CircleShape).background(g.ai), contentAlignment = Alignment.Center) {
            Ph(R.drawable.ph_robot, Metrics.iconLg, g.onIsland)
        }
        Text(stringResource(R.string.assistant_hello), style = TextStyle(brush = g.ai, fontSize = Type.greeting, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Text(v.blurb, color = g.text2, style = Type.style(Type.greetingSub), textAlign = TextAlign.Center)
        state.cfg.engines.forEachIndexed { i, e ->
            var q by remember(e.id) { mutableStateOf("") }
            GlassField(
                q, { q = it },
                if (i == 0) stringResource(R.string.search_the_web) else stringResource(R.string.search_with, e.label),
                Tags.engine(e.id), icon = IconCatalog.res(e.icon), iconTint = namedColor(e.accent),
                onSearch = { if (q.isNotBlank()) { Browser.open(ctx, Templates.fill(e.url, q.trim(), state.cfg.city(state.city))); q = "" } },
            )
        }
        Text(stringResource(R.string.web_opens_in_browser), color = g.text2, style = Type.style(Type.label))
    }
}

@Composable
private fun Conversation(chat: ChatModel) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(chat.session.messages.size, chat.sending) {
        val n = chat.session.messages.size + (if (chat.sending) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bubbleMax = maxWidth * Metrics.BUBBLE_MAX_FRACTION
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gutter)) {
            items(chat.session.messages.withIndex().toList(), key = { it.index }) { (_, m) -> Bubble(m, bubbleMax) }
            if (chat.sending) item { Thinking(if (chat.web) R.string.searching_web else R.string.thinking) }
            if (chat.citations.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                    FieldLabel(stringResource(R.string.sources))
                    chat.citations.forEach { u ->
                        Row(Modifier.clickable { Browser.open(ctx, u) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
                            Ph(R.drawable.ph_globe, Metrics.iconSm, g.accent)
                            Text(u, color = g.accent, style = Type.style(Type.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            chat.error?.let { e -> item { Text(stringResource(R.string.chat_error, e), color = g.negative, style = Type.style(Type.small)) } }
        }
    }
}

/** User: a light bubble on the right with a small bottom-right corner. Assistant: the avatar and plain text. */
@Composable
private fun Bubble(m: Chat.Msg, max: androidx.compose.ui.unit.Dp) {
    val g = LocalGlass.current
    if (m.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                m.content, color = g.onUserBubble, style = Type.style(Type.chat),
                modifier = Modifier.widthIn(max = max)
                    .clip(RoundedCornerShape(Metrics.bubbleRadius, Metrics.bubbleRadius, Metrics.bubbleTail, Metrics.bubbleRadius))
                    .background(g.userBubble).padding(horizontal = Metrics.bubblePadH, vertical = Metrics.bubblePadV),
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.bubblePadV)) {
            AiAvatar()
            Text(m.content, color = g.text, style = Type.style(Type.chat), modifier = Modifier.padding(top = Metrics.chipGap))
        }
    }
}

@Composable
private fun AiAvatar() {
    val g = LocalGlass.current
    Box(Modifier.size(Metrics.aiAvatar).clip(CircleShape).background(g.ai), contentAlignment = Alignment.Center) {
        Ph(R.drawable.ph_robot, Metrics.iconSm, g.onIsland)
    }
}

/** While an answer is on its way: the avatar and "Searching the web…" (Web on) or "Thinking…". */
@Composable
private fun Thinking(label: Int) {
    val g = LocalGlass.current
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.bubblePadV), verticalAlignment = Alignment.CenterVertically) {
        AiAvatar()
        Ph(R.drawable.ph_globe, Metrics.iconSm, g.accent)
        Text(stringResource(label), color = g.accent, style = Type.style(Type.greetingSub, FontWeight.Medium))
    }
}

/** The model <select>: the live OpenRouter catalogue, native web-search models marked, and the Web switch. */
@Composable
private fun ModelSelect(chat: ChatModel) {
    val g = LocalGlass.current
    var open by remember { mutableStateOf(false) }
    val current = chat.models.firstOrNull { it.id == chat.model }
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Box {
        Row(
            Modifier.widthIn(max = Metrics.modelMaxWidth).clip(shape).background(g.field).border(Metrics.hairline, g.tileBorder, shape)
                .clickable { open = true }.testTag(Tags.MODEL).padding(horizontal = Metrics.cardPad, vertical = Metrics.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.small),
        ) {
            Text("✨ " + (current?.name ?: chat.model), color = g.text, style = Type.style(Type.label, FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Ph(R.drawable.ph_caret_down, Metrics.iconXs, g.text2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.web_search), style = Type.style(Type.small, FontWeight.SemiBold)) },
                trailingIcon = {
                    Switch(chat.web, { chat.toggleWeb() }, Modifier.testTag(Tags.WEB),
                        colors = SwitchDefaults.colors(checkedTrackColor = g.accent, uncheckedTrackColor = g.field))
                },
                onClick = { chat.toggleWeb() },
            )
            if (chat.models.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.models_unavailable), style = Type.style(Type.small)) }, onClick = { open = false })
            chat.models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(m.name + (if (m.nativeWeb) " · " + stringResource(R.string.web_native) else "") + (if (m.free) " · " + stringResource(R.string.free) else ""),
                            style = Type.style(Type.small), color = if (m.id == chat.model) g.accent else g.text)
                    },
                    onClick = { chat.pick(m.id); open = false },
                )
            }
        }
    }
}

/** The Gemini-style input: new chat, a field that grows to six lines, the gradient send button. */
@Composable
private fun ChatBar(text: String, onText: (String) -> Unit, sending: Boolean, onNew: () -> Unit, onSend: () -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Metrics.chatBarRadius)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Metrics.small, vertical = Metrics.gap)
            .clip(shape).background(g.chatBar).border(Metrics.hairline, g.glassBorder, shape).padding(Metrics.chipGap),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Metrics.small),
    ) {
        Box(Modifier.size(Metrics.sendButton).clip(CircleShape).clickable(onClick = onNew).testTag(Tags.NEW_CHAT), contentAlignment = Alignment.Center) {
            Ph(R.drawable.ph_plus_circle, Metrics.icon, g.text2, stringResource(R.string.new_chat))
        }
        BasicTextField(
            value = text, onValueChange = onText, minLines = 1, maxLines = 6,
            textStyle = TextStyle(color = g.text, fontSize = Type.chatInput),
            cursorBrush = SolidColor(g.accent),
            modifier = Modifier.weight(1f).padding(vertical = Metrics.cardPad).testTag(Tags.CHAT_INPUT),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(stringResource(R.string.ask_anything), color = g.text2, style = TextStyle(fontSize = Type.chatInput))
                    inner()
                }
            },
        )
        Box(
            Modifier.size(Metrics.sendButton).clip(CircleShape).background(g.ai)
                .clickable(enabled = !sending, onClick = onSend).testTag(Tags.CHAT_SEND),
            contentAlignment = Alignment.Center,
        ) { Ph(R.drawable.ph_arrow_up, Metrics.icon, g.onIsland, stringResource(R.string.send)) }
    }
}

/** Chat Sessions: grouped Today / Previous 7 Days / Older, newest first. */
@Composable
fun SessionsMenu(state: SearchState) {
    val g = LocalGlass.current
    val sessions = remember(state.menu) { state.services.sessions.all() }
    val groups = remember(sessions) { Chat.group(sessions, System.currentTimeMillis(), ZoneId.systemDefault()) }
    MenuTitle(stringResource(R.string.chat_sessions), { state.menu = null }, stringResource(R.string.close))
    if (groups.isEmpty()) Text(stringResource(R.string.no_sessions), color = g.text2, style = Type.style(Type.small))
    groups.forEach { (bucket, list) ->
        Text(stringResource(bucketLabel(bucket)), color = g.text2, style = Type.style(Type.menuItem, FontWeight.SemiBold), modifier = Modifier.padding(top = Metrics.gap))
        list.forEach { s ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.cardRadius)).background(g.field)
                    .clickable { state.chat.open(s); state.menu = null }.padding(Metrics.cardPad + Metrics.tiny),
            ) {
                Text(s.title.ifBlank { stringResource(R.string.untitled) }, color = g.text, style = Type.style(Type.menuItem, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.n_messages, s.messages.size), color = g.text2, style = Type.style(Type.label), modifier = Modifier.padding(top = Metrics.small))
            }
        }
    }
}

fun bucketLabel(b: Chat.Bucket): Int = when (b) {
    Chat.Bucket.TODAY -> R.string.today
    Chat.Bucket.PREVIOUS_7_DAYS -> R.string.previous_7_days
    Chat.Bucket.OLDER -> R.string.older
}
