package com.diegonmarcos.superapp.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Test tags of the one search panel. */
object SearchPanelTags {
    const val RESULTS = "search:results"
    const val DROPDOWN = "search:dropdown"
    const val SHEET = "search:sheet"
    const val CHIPS = "search:chips"
    const val NOTICE = "search:notice"
    const val CLOUD_SEARCH = "search:cloud-search"
    const val EMPTY = "search:empty"
    const val COMMANDS = "search:commands"
    fun chip(scopeId: String) = "search:chip:$scopeId"
    fun section(scopeId: String) = "search:section:$scopeId"
    fun row(scopeId: String) = "search:row:$scopeId"
}

/**
 * THE search, as state: one per place it is shown ([entry]), all running libs:search's
 * [SearchEngine] over the app's [SearchSource]. The query is never saved (leaving, launching or
 * relaunching shows the page again); the scope chips are, per entry ([SearchScopePrefs]).
 */
class SearchController(
    val entry: SearchEntry,
    private val source: SearchSource,
    private val prefs: SearchScopePrefs?,
    declared: List<SearchScope> = SearchScopes.fromBuildConfig(),
) {
    val scopes: List<SearchScope> = SearchEngine.scopesOrAll(declared)

    var query by mutableStateOf("")
    /** The bar holds focus: an inline search then shows its chips before anything is typed. */
    var focused by mutableStateOf(false)
    var selected by mutableStateOf(entry.selection(scopes, prefs?.load(entry)))
        private set

    /** The live scopes' last answer and the query it answered. */
    var live by mutableStateOf(LiveResult.EMPTY)
        private set
    private var liveFor by mutableStateOf("")

    /** Built once, on first use: the index the scopes' builders make (not the live ones). */
    private val index: List<SearchHit> by lazy {
        scopes.filter { it.kind !in SearchKinds.LIVE }.flatMap { runCatching { source.hitsFor(it) }.getOrDefault(emptyList()) }
    }
    val commands: List<SearchCommand> by lazy { runCatching { source.commands() }.getOrDefault(emptyList()) }

    val commandMode: Boolean get() = SearchEngine.isCommandMode(query, commands)

    fun toggle(scopeId: String) {
        selected = SearchEngine.toggle(selected, scopeId)
        prefs?.save(entry, selected)
    }

    fun liveScopes(): List<SearchScope> = scopes.filter { it.id in selected && it.kind in SearchKinds.LIVE }

    /** BLOCKING (another app's provider): off the main thread. */
    fun fetchLive(q: String): LiveResult = source.live(liveScopes(), q)

    fun setLive(q: String, r: LiveResult) { live = r; liveFor = q.trim() }

    /** The sections for the current query. A live answer for an older query keeps only the rows
     *  that still match, so the browser rows do not blink out between two letters. */
    fun sections(): List<SearchEngine.Section> {
        val liveHits = if (liveFor == query.trim()) live.hits else live.hits.filter { SearchEngine.matches(it, query) }
        return SearchEngine.sections(scopes, index, selected, query, liveHits)
    }

    fun notices(): List<String> = if (query.isBlank() || liveScopes().isEmpty()) emptyList() else live.notices
}

/** What the panel does with a pick; the fragment supplies it ([SearchActions]). */
class SearchCallbacks(
    val onHit: (SearchHit) -> Unit,
    val onCommand: (SearchCommand) -> Unit,
    val onCloudSearch: (String) -> Unit,
)

/**
 * The bar: libs:ui-kit's [KitSearchBar] — a 36dp row, a drawn magnifier, small text, × only while
 * there is text. The same bar in every place the SuperApp searches.
 */
@Composable
fun SearchBox(c: SearchController, placeholder: String, onGo: () -> Unit, modifier: Modifier = Modifier) {
    KitSearchBar(
        query = c.query,
        onQueryChange = { c.query = it },
        onSubmit = onGo,
        placeholder = placeholder,
        modifier = modifier.onFocusChanged { c.focused = it.hasFocus },
    )
}

/** Go: the exact command on a command line, else the top result, else the query to Cloud Search. */
fun SearchController.go(cb: SearchCallbacks) {
    if (commandMode) { SearchEngine.exactCommand(query, commands)?.let(cb.onCommand); return }
    if (query.isBlank()) return
    val top = SearchEngine.top(sections())
    if (top != null) cb.onHit(top) else cb.onCloudSearch(query.trim())
}

/** One toggle per scope; the last one on stays on ([SearchEngine.toggle]). */
@Composable
fun SearchScopeChips(c: SearchController, modifier: Modifier = Modifier) {
    if (c.scopes.size < 2) return
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.corner)
    Row(modifier.horizontalScroll(rememberScrollState()).testTag(SearchPanelTags.CHIPS),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in c.scopes) {
            val on = s.id in c.selected
            Text(
                s.label,
                color = if (on) p.tileInk else p.textSecondary,
                fontSize = KitDensity.caption,
                maxLines = 1,
                modifier = Modifier
                    .testTag(SearchPanelTags.chip(s.id))
                    .clip(shape)
                    .then(if (on) Modifier.background(p.accent) else Modifier.border(KitDensity.rule, p.hairline, shape))
                    .toggleable(value = on, role = Role.Checkbox) { c.toggle(s.id) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** A glyph per kind, the way Cloud Browser's dropdown marks its rows. */
private fun glyph(kind: String): String = when (kind) {
    SearchKinds.CLOUD_APPS -> "☁"
    SearchKinds.PHONE_APPS -> "▣"
    SearchKinds.CLOUD_CONFIGS, SearchKinds.PHONE_CONFIGS -> "⚙"
    SearchKinds.BROWSER_FAV -> "★"
    SearchKinds.BROWSER_HISTORY -> "⏱"
    SearchKinds.BROWSER_WEB -> "🔍"
    else -> "·"
}

/** A section title: Cloud Browser's dropdown style (accent, a hairline under it), with its count. */
@Composable
private fun SectionHeader(text: String, tag: String) {
    val p = LocalKitPalette.current
    Column(Modifier.testTag(tag)) {
        Text(text, color = p.accent, fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp))
        HorizontalDivider(color = p.hairline)
    }
}

/** A dense row: glyph, label, crumb; no background and no alpha of its own. */
@Composable
private fun ResultRow(glyph: String, label: String, crumb: String, tag: String, onTap: () -> Unit) {
    val p = LocalKitPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = p.textSecondary, fontSize = KitDensity.body, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = p.textPrimary, fontSize = KitDensity.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (crumb.isNotBlank()) Text(crumb, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Line(text: String, tag: String, onTap: (() -> Unit)? = null) {
    val p = LocalKitPalette.current
    Text(text, color = if (onTap != null) p.textPrimary else p.textSecondary, fontSize = KitDensity.body,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp))
}

/**
 * The results, ARRANGED PER TYPE: one section per scope that is on and has hits, in the declared
 * order (Cloud apps, Phone apps, Cloud configs, Phone configs, Browser favourites, Browser
 * history, Web), each under a small header with its count. Drawn on [surface], which is the
 * theme surface at 100% alpha (LauncherPalette.opaqueSurface): nothing under it shows through.
 * A drag on the list hides the keyboard.
 */
@Composable
fun SearchResults(c: SearchController, surface: Color, cb: SearchCallbacks, modifier: Modifier = Modifier) {
    val q = c.query
    val sections = remember(q, c.selected, c.live) { c.sections() }
    val notices = remember(q, c.selected, c.live) { c.notices() }
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) keyboard?.hide() }
    LazyColumn(modifier.fillMaxWidth().background(surface).testTag(SearchPanelTags.RESULTS), state = list) {
        if (c.commandMode) {
            val cq = SearchEngine.commandQuery(q)
            val matched = SearchEngine.commandMatches(q, c.commands)
            item { SectionHeader(if (cq.isEmpty()) "Commands · ${c.commands.size} — keep typing, Go to run"
                                 else "Commands · ${matched.size}", SearchPanelTags.COMMANDS) }
            if (matched.isEmpty()) item { Line("No command matches “:$cq”", SearchPanelTags.EMPTY) }
            items(matched) { cmd -> ResultRow("›", ":${cmd.alias}", cmd.crumb.ifBlank { cmd.label }, SearchPanelTags.COMMANDS) { cb.onCommand(cmd) } }
        } else {
            for (s in sections) {
                item(key = "h:${s.scope.id}") { SectionHeader("${s.scope.section} · ${s.total}", SearchPanelTags.section(s.scope.id)) }
                items(s.hits) { hit -> ResultRow(glyph(s.scope.kind), hit.label, hit.crumb, SearchPanelTags.row(s.scope.id)) { cb.onHit(hit) } }
            }
            for (n in notices) item { Line(n, SearchPanelTags.NOTICE) }
            if (sections.isEmpty()) {
                if (q.isBlank()) item { Line("No items in the selected scope(s)", SearchPanelTags.EMPTY) }
                else item { Line("Search “${q.trim()}” in Cloud Search", SearchPanelTags.CLOUD_SEARCH) { cb.onCloudSearch(q.trim()) } }
            }
        }
    }
}

/** Asks the live scopes (Cloud Browser) for the current query, a beat after the last letter. */
@Composable
fun SearchLiveEffect(c: SearchController) {
    val q = c.query
    val sel = c.selected
    LaunchedEffect(q, sel) {
        if (q.isBlank() || c.liveScopes().isEmpty()) { c.setLive(q, LiveResult.EMPTY); return@LaunchedEffect }
        delay(150)
        val r = withContext(Dispatchers.IO) { runCatching { c.fetchLive(q) }.getOrDefault(LiveResult.EMPTY) }
        c.setLive(q, r)
    }
}

/**
 * Under an inline bar (Cloud ▸ Apps, the Home swipe sheet): the chips while the bar has focus,
 * and the results over the whole page once there is text. An opaque layer with a shadow, that
 * takes its own touches; nothing at all while the bar is idle, so the page below is untouched.
 */
@Composable
fun SearchDropdown(c: SearchController, surface: Color, cb: SearchCallbacks) {
    SearchLiveEffect(c)
    val typing = c.query.isNotBlank()
    if (!typing && !c.focused) return
    Column(
        Modifier.fillMaxWidth()
            .then(if (typing) Modifier.fillMaxHeight() else Modifier)
            .shadow(8.dp)
            .background(surface)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .testTag(SearchPanelTags.DROPDOWN),
    ) {
        if (!c.commandMode) SearchScopeChips(c, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        if (typing) SearchResults(c, surface, cb, Modifier.weight(1f))
    }
}
