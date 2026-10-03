package com.diegonmarcos.superapp.searchpage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The page's colours: the consumer's own tokens (Cloud Search: its glass theme; the browser: its kit palette). */
@Immutable
data class SearchPageColors(
    val text: Color, val text2: Color, val accent: Color, val negative: Color,
    val field: Color, val tile: Color, val tileBorder: Color, val chatBar: Color, val glassBorder: Color,
    val userBubble: Color, val onUserBubble: Color, val onAi: Color,
    /** The assistant's avatar, send button and greeting. */
    val ai: Brush,
)

/** Sizes, defaulting to Cloud Search's mockup (1 px = 1 dp). */
@Immutable
data class SearchPageMetrics(
    val hairline: Dp = 1.dp, val tiny: Dp = 2.dp, val small: Dp = 4.dp, val gap: Dp = 8.dp, val gutter: Dp = 16.dp,
    val cardRadius: Dp = 12.dp, val cardPad: Dp = 10.dp, val chipGap: Dp = 6.dp,
    val aiAvatar: Dp = 32.dp, val robot: Dp = 64.dp, val engineGap: Dp = 12.dp,
    val chatBarRadius: Dp = 28.dp, val sendButton: Dp = 40.dp,
    val bubbleRadius: Dp = 24.dp, val bubbleTail: Dp = 4.dp, val bubblePadH: Dp = 16.dp, val bubblePadV: Dp = 12.dp,
    val bubbleMaxFraction: Float = 0.85f, val modelMaxWidth: Dp = 160.dp,
    val iconXs: Dp = 11.dp, val iconSm: Dp = 14.dp, val icon: Dp = 18.dp, val iconLg: Dp = 30.dp,
)

/** The type scale, defaulting to Cloud Search's (rem × 16 = sp). */
@Immutable
data class SearchPageType(
    val label: TextUnit = 9.sp, val small: TextUnit = 10.sp, val chat: TextUnit = 15.sp,
    val greeting: TextUnit = 24.sp, val greetingSub: TextUnit = 14.sp, val chatInput: TextUnit = 14.sp,
) {
    fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size, fontWeight = weight)
}

/** The words, defaulting to English; a consumer with its own resources passes them. */
@Immutable
data class SearchPageStrings(
    val hello: String = "Hi, I'm your AI Assistant",
    val helloSub: String = "Search the web below or ask me a complex question.",
    val searchTheWeb: String = "Search the Web…",
    /** "%1$s" is the engine's label. */
    val searchWith: String = "Search %1\$s…",
    val webSearch: String = "Web search",
    val webNative: String = "web",
    val free: String = "free",
    val modelsUnavailable: String = "Model list unavailable (offline?)",
    val newChat: String = "New chat",
    val searchingWeb: String = "Searching the web…",
    val thinking: String = "Thinking…",
    val sources: String = "Sources",
    /** "%1$s" is the reason. */
    val chatError: String = "No answer: %1\$s",
    val askAnything: String = "Ask anything…",
    val send: String = "Send",
)

/** The icons the page draws, by name; the consumer maps each to its own icon set. */
object SearchPageIcons {
    const val ROBOT = "robot"
    const val GLOBE = "globe"
    const val CARET_DOWN = "caret_down"
    const val PLUS_CIRCLE = "plus_circle"
    const val ARROW_UP = "arrow_up"
    val ALL = listOf(ROBOT, GLOBE, CARET_DOWN, PLUS_CIRCLE, ARROW_UP)
}

/**
 * How the page looks: colours, sizes, words, an icon drawer, and the engine box (the consumer's
 * own search field; [engineField] gets the box's tag and must apply it).
 */
@Immutable
data class SearchPageTheme(
    val colors: SearchPageColors,
    val metrics: SearchPageMetrics = SearchPageMetrics(),
    val type: SearchPageType = SearchPageType(),
    val strings: SearchPageStrings = SearchPageStrings(),
    val icon: @Composable (name: String, size: Dp, tint: Color, description: String?) -> Unit,
    val engineField: @Composable (engine: SpEngine, value: String, onValue: (String) -> Unit, placeholder: String, tag: String, onSearch: () -> Unit) -> Unit,
)

/**
 * The Search page (the mockup's renderUnifiedSearchChat): [header] (Sessions, in Cloud Search) and
 * the model on top; until the chat has a message, the greeting and one box per [engines] entry
 * (each opens [query]'s URL through the host); then the conversation in the same place. The input
 * is pinned at the bottom the whole time.
 */
@Composable
fun SearchChatPage(
    chat: SearchChatState,
    engines: List<SpEngine>,
    query: (SpEngine, String) -> String?,
    theme: SearchPageTheme,
    modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit = {},
) {
    val m = theme.metrics
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (chat.models.isEmpty()) chat.models = withContext(Dispatchers.IO) { runCatching { chat.host().models() }.getOrDefault(emptyList()) } }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = m.small), verticalAlignment = Alignment.CenterVertically) {
            header()
            Spacer(Modifier.weight(1f))
            ModelSelect(chat, theme)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.session.messages.isEmpty() && !chat.sending) Welcome(engines, query, chat.host(), theme)
            else Conversation(chat, theme)
        }
        ChatBar(
            text, { text = it }, sending = chat.sending, theme = theme,
            onNew = { chat.fresh() },
            onSend = {
                val ask = text.trim()
                val before = chat.begin(ask)
                if (before != null) {
                    text = ""
                    scope.launch {
                        val out = try {
                            withContext(Dispatchers.IO) { chat.host().send(before, ask, chat.model, chat.web, System.currentTimeMillis()) }
                        } catch (e: Exception) {
                            SpOutcome(chat.session, e.message ?: e.javaClass.simpleName, emptyList())
                        }
                        chat.finish(out)
                    }
                }
            },
        )
    }
}

/** #initial-search-view: the greeting and the engine boxes; it gives way once a message is sent. */
@Composable
private fun Welcome(engines: List<SpEngine>, query: (SpEngine, String) -> String?, host: SearchPageHost, theme: SearchPageTheme) {
    val c = theme.colors
    val m = theme.metrics
    val s = theme.strings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.cardPad),
    ) {
        Box(Modifier.size(m.robot).clip(CircleShape).background(c.ai), contentAlignment = Alignment.Center) {
            theme.icon(SearchPageIcons.ROBOT, m.iconLg, c.onAi, null)
        }
        Text(s.hello, style = TextStyle(brush = c.ai, fontSize = theme.type.greeting, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Text(s.helloSub, color = c.text2, style = theme.type.style(theme.type.greetingSub), textAlign = TextAlign.Center)
        // .search-container: one box per declared engine, in declared order.
        Column(Modifier.padding(top = m.gap), verticalArrangement = Arrangement.spacedBy(m.engineGap)) {
            engines.forEachIndexed { i, e ->
                var q by remember(e.id) { mutableStateOf("") }
                theme.engineField(
                    e, q, { q = it },
                    if (i == 0) s.searchTheWeb else s.searchWith.format(e.label),
                    SearchPageTags.engine(e.id),
                ) { if (q.isNotBlank()) { query(e, q.trim())?.let(host::openUrl); q = "" } }
            }
        }
    }
}

@Composable
private fun Conversation(chat: SearchChatState, theme: SearchPageTheme) {
    val c = theme.colors
    val m = theme.metrics
    val list = rememberLazyListState()
    LaunchedEffect(chat.session.messages.size, chat.sending) {
        val n = chat.session.messages.size + (if (chat.sending) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bubbleMax = maxWidth * m.bubbleMaxFraction
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(m.gutter), verticalArrangement = Arrangement.spacedBy(m.gutter)) {
            items(chat.session.messages.withIndex().toList(), key = { it.index }) { (_, msg) -> Bubble(msg, bubbleMax, theme) }
            if (chat.sending) item { Thinking(if (chat.web) theme.strings.searchingWeb else theme.strings.thinking, theme) }
            if (chat.citations.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(m.small)) {
                    Text(theme.strings.sources.uppercase(), color = c.text2, style = theme.type.style(theme.type.label, FontWeight.Bold))
                    chat.citations.forEach { u ->
                        Row(Modifier.clickable { chat.host().openUrl(u) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.small)) {
                            theme.icon(SearchPageIcons.GLOBE, m.iconSm, c.accent, null)
                            Text(u, color = c.accent, style = theme.type.style(theme.type.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            chat.error?.let { e -> item { Text(theme.strings.chatError.format(e), color = c.negative, style = theme.type.style(theme.type.small)) } }
        }
    }
}

/** User: a light bubble on the right with a small bottom-right corner. Assistant: the avatar and plain text. */
@Composable
private fun Bubble(msg: SpMsg, max: Dp, theme: SearchPageTheme) {
    val c = theme.colors
    val m = theme.metrics
    if (msg.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                msg.content, color = c.onUserBubble, style = theme.type.style(theme.type.chat),
                modifier = Modifier.widthIn(max = max)
                    .clip(RoundedCornerShape(m.bubbleRadius, m.bubbleRadius, m.bubbleTail, m.bubbleRadius))
                    .background(c.userBubble).padding(horizontal = m.bubblePadH, vertical = m.bubblePadV),
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(m.bubblePadV)) {
            AiAvatar(theme)
            Text(msg.content, color = c.text, style = theme.type.style(theme.type.chat), modifier = Modifier.padding(top = m.chipGap))
        }
    }
}

@Composable
private fun AiAvatar(theme: SearchPageTheme) {
    Box(Modifier.size(theme.metrics.aiAvatar).clip(CircleShape).background(theme.colors.ai), contentAlignment = Alignment.Center) {
        theme.icon(SearchPageIcons.ROBOT, theme.metrics.iconSm, theme.colors.onAi, null)
    }
}

/** While an answer is on its way: the avatar and "Searching the web…" (Web on) or "Thinking…". */
@Composable
private fun Thinking(label: String, theme: SearchPageTheme) {
    Row(horizontalArrangement = Arrangement.spacedBy(theme.metrics.bubblePadV), verticalAlignment = Alignment.CenterVertically) {
        AiAvatar(theme)
        theme.icon(SearchPageIcons.GLOBE, theme.metrics.iconSm, theme.colors.accent, null)
        Text(label, color = theme.colors.accent, style = theme.type.style(theme.type.greetingSub, FontWeight.Medium))
    }
}

/** The model <select>: the host's catalogue, native web-search models marked, and the Web switch. */
@Composable
private fun ModelSelect(chat: SearchChatState, theme: SearchPageTheme) {
    val c = theme.colors
    val m = theme.metrics
    val s = theme.strings
    val t = theme.type
    var open by remember { mutableStateOf(false) }
    val current = chat.models.firstOrNull { it.id == chat.model }
    val shape = RoundedCornerShape(m.cardRadius)
    Box {
        Row(
            Modifier.widthIn(max = m.modelMaxWidth).clip(shape).background(c.field).border(m.hairline, c.tileBorder, shape)
                .clickable { open = true }.testTag(SearchPageTags.MODEL).padding(horizontal = m.cardPad, vertical = m.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.small),
        ) {
            Text("✨ " + (current?.name ?: chat.model), color = c.text, style = t.style(t.label, FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            theme.icon(SearchPageIcons.CARET_DOWN, m.iconXs, c.text2, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(s.webSearch, style = t.style(t.small, FontWeight.SemiBold)) },
                trailingIcon = {
                    Switch(chat.web, { chat.toggleWeb() }, Modifier.testTag(SearchPageTags.WEB),
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field))
                },
                onClick = { chat.toggleWeb() },
            )
            if (chat.models.isEmpty()) DropdownMenuItem(text = { Text(s.modelsUnavailable, style = t.style(t.small)) }, onClick = { open = false })
            chat.models.forEach { mod ->
                DropdownMenuItem(
                    text = {
                        Text(mod.name + (if (mod.nativeWeb) " · " + s.webNative else "") + (if (mod.free) " · " + s.free else ""),
                            style = t.style(t.small), color = if (mod.id == chat.model) c.accent else c.text)
                    },
                    onClick = { chat.pick(mod.id); open = false },
                )
            }
        }
    }
}

/** The Gemini-style input: new chat, a field that grows to six lines, the gradient send button. */
@Composable
private fun ChatBar(text: String, onText: (String) -> Unit, sending: Boolean, theme: SearchPageTheme, onNew: () -> Unit, onSend: () -> Unit) {
    val c = theme.colors
    val m = theme.metrics
    val shape = RoundedCornerShape(m.chatBarRadius)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = m.small, vertical = m.gap)
            .clip(shape).background(c.chatBar).border(m.hairline, c.glassBorder, shape).padding(m.chipGap),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(m.small),
    ) {
        Box(Modifier.size(m.sendButton).clip(CircleShape).clickable(onClick = onNew).testTag(SearchPageTags.NEW_CHAT), contentAlignment = Alignment.Center) {
            theme.icon(SearchPageIcons.PLUS_CIRCLE, m.icon, c.text2, theme.strings.newChat)
        }
        BasicTextField(
            value = text, onValueChange = onText, minLines = 1, maxLines = 6,
            textStyle = TextStyle(color = c.text, fontSize = theme.type.chatInput),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).padding(vertical = m.cardPad).testTag(SearchPageTags.CHAT_INPUT),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(theme.strings.askAnything, color = c.text2, style = TextStyle(fontSize = theme.type.chatInput))
                    inner()
                }
            },
        )
        Box(
            Modifier.size(m.sendButton).clip(CircleShape).background(c.ai)
                .clickable(enabled = !sending, onClick = onSend).testTag(SearchPageTags.CHAT_SEND),
            contentAlignment = Alignment.Center,
        ) { theme.icon(SearchPageIcons.ARROW_UP, m.icon, c.onAi, theme.strings.send) }
    }
}
