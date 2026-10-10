package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.Templates
import com.diegonmarcos.cloudsearch.data.sp
import com.diegonmarcos.cloudsearch.models.ModelCataloguePage
import com.diegonmarcos.superapp.searchpage.SearchChatPage
import com.diegonmarcos.superapp.searchpage.SearchPageColors
import com.diegonmarcos.superapp.searchpage.SearchPageIcons
import com.diegonmarcos.superapp.searchpage.SearchPageMetrics
import com.diegonmarcos.superapp.searchpage.SearchPageStrings
import com.diegonmarcos.superapp.searchpage.SearchPageTheme
import com.diegonmarcos.superapp.searchpage.SearchPageType
import com.diegonmarcos.superapp.searchpage.SpEngine
import java.time.ZoneId

/**
 * The mockup's one Search page (renderUnifiedSearchChat), drawn by libs:search-page (#823, shared
 * with Cloud Browser): Sessions and the model on top; until the chat has a message, its
 * #initial-search-view - the greeting and one box per declared engine (each opens that engine's
 * own results in cloud-browser) - then the conversation in the same place. The Gemini-style input
 * is pinned above the nav the whole time. There is no second page: the search vertical declares
 * this one subpage (test-search-shell.sh S12). This app hands it the glass theme and its words.
 */
@Composable
fun AssistantPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    // With the keyboard up the nav is gone (SearchShell), so the input sits right on the keyboard.
    val bottom = if (imeOpen()) Metrics.small else Metrics.contentBottom
    // The model chip opens the model catalogue (models/ModelCataloguePage) in the chat's place; back returns.
    var catalogue by rememberSaveable { mutableStateOf(false) }
    if (catalogue) {
        ModelCataloguePage(state, onClose = { catalogue = false }, modifier = Modifier.padding(top = Metrics.contentTop, bottom = bottom))
        return
    }
    SearchChatPage(
        state.chat,
        engines = state.cfg.engines.map { SpEngine(it.id, it.label, it.icon, it.accent) },
        query = { e, q -> state.cfg.engines.firstOrNull { it.id == e.id }?.let { Templates.fill(it.url, q, state.cfg.city(state.city)) } },
        theme = searchPageTheme(),
        modifier = Modifier.padding(top = Metrics.contentTop, bottom = bottom),
        onModel = { catalogue = true },
    ) {
        Chip(stringResource(R.string.sessions), Tags.SESSIONS, icon = R.drawable.ph_list_dashes) { state.menu = Menu.SESSIONS }
    }
}

/** The glass theme, Metrics, Type, Phosphor icons and this app's strings, as libs:search-page takes them. */
@Composable
fun searchPageTheme(): SearchPageTheme {
    val g = LocalGlass.current
    return SearchPageTheme(
        colors = SearchPageColors(
            text = g.text, text2 = g.text2, accent = g.accent, negative = g.negative,
            field = g.field, tile = g.tile, tileBorder = g.tileBorder, chatBar = g.chatBar, glassBorder = g.glassBorder,
            userBubble = g.userBubble, onUserBubble = g.onUserBubble, onAi = g.onIsland, ai = g.ai,
        ),
        metrics = SearchPageMetrics(
            hairline = Metrics.hairline, tiny = Metrics.tiny, small = Metrics.small, gap = Metrics.gap, gutter = Metrics.gutter,
            cardRadius = Metrics.cardRadius, cardPad = Metrics.cardPad, chipGap = Metrics.chipGap,
            aiAvatar = Metrics.aiAvatar, robot = Metrics.robot, engineGap = Metrics.engineGap,
            chatBarRadius = Metrics.chatBarRadius, sendButton = Metrics.sendButton,
            bubbleRadius = Metrics.bubbleRadius, bubbleTail = Metrics.bubbleTail, bubblePadH = Metrics.bubblePadH, bubblePadV = Metrics.bubblePadV,
            bubbleMaxFraction = Metrics.BUBBLE_MAX_FRACTION, modelMaxWidth = Metrics.modelMaxWidth,
            iconXs = Metrics.iconXs, iconSm = Metrics.iconSm, icon = Metrics.icon, iconLg = Metrics.iconLg,
        ),
        type = SearchPageType(label = Type.label, small = Type.small, chat = Type.chat, greeting = Type.greeting,
            greetingSub = Type.greetingSub, chatInput = Type.chatInput),
        strings = SearchPageStrings(
            hello = stringResource(R.string.assistant_hello), helloSub = stringResource(R.string.assistant_hello_sub),
            searchTheWeb = stringResource(R.string.search_the_web), searchWith = stringResource(R.string.search_with),
            webSearch = stringResource(R.string.web_search), webNative = stringResource(R.string.web_native),
            free = stringResource(R.string.free), modelsUnavailable = stringResource(R.string.models_unavailable),
            newChat = stringResource(R.string.new_chat), searchingWeb = stringResource(R.string.searching_web),
            thinking = stringResource(R.string.thinking), sources = stringResource(R.string.sources),
            chatError = stringResource(R.string.chat_error), askAnything = stringResource(R.string.ask_anything),
            send = stringResource(R.string.send),
        ),
        icon = { name, size, tint, description -> Ph(phosphor(name), size, tint, description) },
        // .search-container.rounded-2xl.shadow-lg, input !border-none !bg-gray-800/40: an engine box.
        engineField = { e, value, onValue, placeholder, tag, onSearch ->
            GlassField(
                value, onValue, placeholder, tag, Modifier.shadow(Metrics.engineShadow, RoundedCornerShape(Metrics.engineRadius)),
                icon = IconCatalog.res(e.icon), iconTint = namedColor(e.accent),
                radius = Metrics.engineRadius, fill = g.tile, bordered = false, onSearch = onSearch,
            )
        },
    )
}

/** The page's icon names, drawn from this app's Phosphor set. */
private fun phosphor(name: String): Int = when (name) {
    SearchPageIcons.ROBOT -> R.drawable.ph_robot
    SearchPageIcons.GLOBE -> R.drawable.ph_globe
    SearchPageIcons.CARET_DOWN -> R.drawable.ph_caret_down
    SearchPageIcons.PLUS_CIRCLE -> R.drawable.ph_plus_circle
    else -> R.drawable.ph_arrow_up
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
                    .clickable { state.chat.open(s.sp()); state.menu = null }.padding(Metrics.cardPad + Metrics.tiny),
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
