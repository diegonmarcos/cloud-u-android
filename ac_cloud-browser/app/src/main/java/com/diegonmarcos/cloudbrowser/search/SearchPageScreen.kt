package com.diegonmarcos.cloudbrowser.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.superapp.browser.BrowserSearchPageHost
import com.diegonmarcos.superapp.searchpage.SearchChatPage
import com.diegonmarcos.superapp.searchpage.SearchChatState
import com.diegonmarcos.superapp.searchpage.SearchPageColors
import com.diegonmarcos.superapp.searchpage.SearchPageHost
import com.diegonmarcos.superapp.searchpage.SearchPageIcons
import com.diegonmarcos.superapp.searchpage.SearchPageTags
import com.diegonmarcos.superapp.searchpage.SearchPageTheme
import com.diegonmarcos.superapp.searchpage.SpEngine
import com.diegonmarcos.superapp.searchpage.SpModel
import com.diegonmarcos.superapp.searchpage.SpMsg
import com.diegonmarcos.superapp.searchpage.SpOutcome
import com.diegonmarcos.superapp.searchpage.SpSession
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.util.UUID

/**
 * #823 the Search add-on's on-screen page: Cloud Search's own Search page (libs:search-page) over
 * the browser, in the browser's kit palette. Its engines are cloud-search's declaration (baked
 * SEARCH_CONFIG_B64, never restated), a result opens as a tab, and the chat is [SearchAddon.send]:
 * cloud-search's protocol with the fleet Account's token read per send, never stored or shown.
 */
class BrowserSearchHost(private val app: Context, private val search: SearchAddon) : SearchPageHost {
    /** Where a URL goes: the screen's "open as a tab", refreshed each time the page is shown. */
    @Volatile var open: (String) -> Unit = {}

    override var model: String = search.cfg.ai.defaultModel
    override var web: Boolean = false

    override fun newSession(model: String, now: Long) = SpSession(UUID.randomUUID().toString(), "", model, now, emptyList())

    /** The declared model; the browser keeps no catalogue of its own (cloud-search's page lists the live one). */
    override fun models() = listOf(SpModel(search.cfg.ai.defaultModel, search.cfg.ai.defaultModel, nativeWeb = false, free = false))

    override fun send(session: SpSession, text: String, model: String, web: Boolean, now: Long): SpOutcome {
        val o = search.send(session.id, text, model, web) { SearchAddon.accountToken(app, search.cfg.ai.accountProvider) }
        return SpOutcome(o.session.sp(), o.error, o.citations)
    }

    override fun openUrl(url: String) = open(url)

    private fun Chat.Session.sp() = SpSession(id, title, model, updated, messages.map { SpMsg(it.role, it.content) })
}

object SearchPageScreen {
    @Volatile private var host: BrowserSearchHost? = null
    /** The conversation outlives closing the page (the add-on's sessions are this run's). */
    @Volatile private var chat: SearchChatState? = null

    /** Called once at start-up when the Search add-on is declared. */
    fun install(app: Context, search: SearchAddon) {
        BrowserSearchPageHost.page = { open, close ->
            val h = host ?: BrowserSearchHost(app.applicationContext, search).also { host = it }
            h.open = open
            val c = chat ?: SearchChatState(h).also { chat = it }
            val p = LocalKitPalette.current
            Box(Modifier.fillMaxSize().background(p.surface).testTag(SearchPageTags.PAGE)) {
                SearchChatPage(
                    c,
                    engines = search.cfg.engines.map { SpEngine(it.id, it.label, it.icon, it.accent) },
                    query = { e, q -> search.searchUrl(q, e.id) },
                    theme = kitTheme(p),
                ) {
                    TextButton(close, Modifier.testTag("browser:search:close")) { Text("✕ Close") }
                }
            }
        }
    }

    /** The page in the browser's palette; icons are glyphs (the browser ships no icon set of cloud-search's). */
    @Composable
    private fun kitTheme(p: KitPalette) = SearchPageTheme(
        colors = SearchPageColors(
            text = p.textPrimary, text2 = p.textSecondary, accent = p.accent, negative = MaterialTheme.colorScheme.error,
            field = p.surfaceSelected, tile = p.surfaceSelected, tileBorder = p.hairline, chatBar = p.surfaceSelected,
            glassBorder = p.hairline, userBubble = p.textPrimary, onUserBubble = p.tileInk, onAi = p.surface,
            ai = SolidColor(p.accent),
        ),
        icon = { name, size, tint, description ->
            Text(glyph(name), color = tint, fontSize = size.value.sp,
                modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        },
        engineField = { _, value, onValue, placeholder, tag, onSearch ->
            OutlinedTextField(value, onValue, Modifier.fillMaxWidth().testTag(tag), singleLine = true,
                placeholder = { Text(placeholder) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }))
        },
    )

    private fun glyph(name: String) = when (name) {
        SearchPageIcons.ROBOT -> "✦"
        SearchPageIcons.GLOBE -> "◎"
        SearchPageIcons.CARET_DOWN -> "▾"
        SearchPageIcons.PLUS_CIRCLE -> "+"
        else -> "↑"
    }
}
