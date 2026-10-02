@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.Filters
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.bottomNavInsets
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which sheet is open over the shell. */
enum class Sheet { PROFILE, FILTERS, SESSIONS }

/** Everything the shell remembers while it is up; the durable half lives in [Services.prefs]. */
class SearchState(val services: Services) {
    val cfg: SearchConfig = services.cfg
    var vertical by mutableStateOf(cfg.defaultVertical)
    var dark by mutableStateOf(services.prefs.dark)
    var city by mutableStateOf(services.prefs.city)
    var sheet by mutableStateOf<Sheet?>(null)
    var busy by mutableStateOf(0)
    private val subpages = mutableStateMapOf<String, String>()
    val filters = mutableStateMapOf<String, Filters>()
    val listings = mutableMapOf<String, ListingModel>()
    val chat = ChatModel(services)

    fun v(): SearchConfig.Vertical = cfg.vertical(vertical) ?: cfg.verticals.first()
    fun subpageOf(v: SearchConfig.Vertical): String = subpages[v.id]?.takeIf { it in v.subpages } ?: v.subpages.first()
    fun showSubpage(v: SearchConfig.Vertical, id: String) { subpages[v.id] = id }
    fun filtersOf(v: String): Filters = filters[v] ?: Filters()
    fun listing(v: String): ListingModel = listings.getOrPut(v) { ListingModel(services.prefs.lastQuery(v)) }

    fun toggleDark() { dark = !dark; services.prefs.dark = dark }
    fun pickCity(id: String) { city = id; services.prefs.city = id; listings.values.forEach { it.stale = true } }
}

val LocalState = staticCompositionLocalOf<SearchState> { error("SearchShell provides the state") }

/** The test tags the Robolectric smoke test drives the shell by. */
object Tags {
    const val SHELL = "search_shell"
    const val MENU = "search_menu"
    const val ISLAND = "search_island"
    const val PROFILE = "search_profile"
    const val THEME = "search_theme_toggle"
    const val TOKEN = "search_token_status"
    const val SEARCH_BOX = "search_box"
    const val MORE_FILTERS = "search_more_filters"
    const val APPLY_FILTERS = "search_apply_filters"
    const val CHAT_INPUT = "search_chat_input"
    const val CHAT_SEND = "search_chat_send"
    const val SESSIONS = "search_sessions"
    const val MODEL = "search_model"
    fun subpage(id: String) = "search_subpage_$id"
    fun page(kind: String) = "search_page_$kind"
    fun card(key: String) = "search_card_$key"
    fun output(calc: String, id: String) = "search_out_${calc}_$id"
    fun field(calc: String, id: String) = "search_field_${calc}_$id"
    fun engine(id: String) = "search_engine_$id"
    fun source(id: String) = "search_source_$id"
    fun drawer(id: String) = "search_drawer_$id"
}

@Composable
fun SearchShell(state: SearchState) {
    CompositionLocalProvider(LocalState provides state) {
        SearchTheme(state.dark) {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet {
                        Text(stringResource(R.string.drawer_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(Metrics.gutter))
                        state.cfg.verticals.forEach { v ->
                            NavigationDrawerItem(
                                label = { Text(v.title) },
                                icon = { Icon(IconCatalog.vector(v.icon), contentDescription = null) },
                                selected = v.id == state.vertical,
                                onClick = { state.vertical = v.id; scope.launch { drawer.close() } },
                                modifier = Modifier.testTag(Tags.drawer(v.id)),
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = Metrics.gap))
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.saved_items)) },
                            icon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                            selected = false,
                            onClick = {
                                val v = state.cfg.verticals.firstOrNull { it.id == state.vertical && "saved" in it.subpages }
                                    ?: state.cfg.verticals.first { "saved" in it.subpages }
                                state.vertical = v.id
                                state.showSubpage(v, "saved")
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.testTag(Tags.drawer("saved")),
                        )
                    }
                },
            ) {
                Body(state, onMenu = { scope.launch { drawer.open() } })
            }
            when (state.sheet) {
                Sheet.PROFILE -> ProfileSheet(state)
                Sheet.FILTERS -> FiltersSheet(state)
                Sheet.SESSIONS -> SessionsSheet(state)
                null -> Unit
            }
        }
    }
}

@Composable
private fun Body(state: SearchState, onMenu: () -> Unit) {
    val collapse = rememberBottomNavCollapse()
    val insets = bottomNavInsets()
    val entries = state.cfg.verticals.map { BottomNavEntry(it.id, it.label, rememberVectorPainter(IconCatalog.vector(it.icon))) }
    val v = state.v()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(Tags.SHELL)) {
        TopBar(state, v, onMenu)
        if (v.subpages.size > 1) SubpageRow(state, v)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))
                .nestedScroll(collapse),
        ) {
            val sub = state.subpageOf(v)
            key(v.id, sub) { Page(v, state.cfg.subpage(sub)?.kind ?: "") }
        }
        BottomNavIsland(
            entries = entries,
            selectedId = state.vertical,
            onSelect = { state.vertical = it.id },
            collapsed = collapse.collapsed,
            insets = insets,
        )
    }
}

/** Menu · the dynamic island (the vertical, or what is running) · profile. */
@Composable
private fun TopBar(state: SearchState, v: SearchConfig.Vertical, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Metrics.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu, modifier = Modifier.testTag(Tags.MENU)) {
            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.menu))
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .height(Metrics.islandHeight)
                .clip(RoundedCornerShape(Metrics.corner))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = Metrics.gutter)
                .testTag(Tags.ISLAND),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
        ) {
            Icon(IconCatalog.vector(v.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                if (state.busy > 0) stringResource(R.string.island_busy) else v.title,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { state.sheet = Sheet.PROFILE }, modifier = Modifier.testTag(Tags.PROFILE)) {
            Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.profile))
        }
    }
}

@Composable
private fun SubpageRow(state: SearchState, v: SearchConfig.Vertical) {
    val selected = state.subpageOf(v)
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Metrics.gutter, vertical = Metrics.small),
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
    ) {
        items(v.subpages, key = { it }) { id ->
            FilterChip(
                selected = id == selected,
                onClick = { state.showSubpage(v, id) },
                label = { Text(state.cfg.subpage(id)?.label ?: id) },
                modifier = Modifier.testTag(Tags.subpage(id)),
            )
        }
    }
}

/** The one dispatch from a subpage's declared `kind` to its page. */
@Composable
private fun Page(v: SearchConfig.Vertical, kind: String) {
    Box(Modifier.fillMaxSize().testTag(Tags.page(kind))) {
        when (kind) {
            "listing" -> ListingPage(v)
            "analysis" -> AnalysisPage(v)
            "feed" -> FeedPage(v)
            "calculators" -> CalculatorsPage(v)
            "web" -> WebPage(v)
            "chat" -> ChatPage()
            "saved" -> SavedPage(v)
            else -> Text(stringResource(R.string.no_renderer, kind), Modifier.padding(Metrics.gutter))
        }
    }
}

/** Profile & settings: where the AI token comes from, the theme, the city. */
@Composable
private fun ProfileSheet(state: SearchState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { state.sheet = null }) {
        Column(Modifier.padding(Metrics.gutter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Text(stringResource(R.string.profile), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.token_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.token_blurb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                OutlinedButton(onClick = {
                    token = ctx.getString(R.string.token_checking)
                    scope.launch {
                        val t = withContext(Dispatchers.IO) { Account.token(ctx, state.cfg.ai.accountProvider) }
                        token = if (t.value != null) ctx.getString(R.string.token_present) else ctx.getString(R.string.token_missing, t.why)
                    }
                }) { Text(stringResource(R.string.token_check)) }
                Text(token ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.TOKEN))
            }
            HorizontalDivider()
            KitSwitchRow(
                title = stringResource(R.string.dark_theme), subtitle = "", checked = state.dark,
                onCheckedChange = { state.toggleDark() }, modifier = Modifier.testTag(Tags.THEME),
            )
            HorizontalDivider()
            Text(stringResource(R.string.city), style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                items(state.cfg.cities, key = { it.id }) { c ->
                    FilterChip(selected = c.id == state.city, onClick = { state.pickCity(c.id) }, label = { Text(c.label) })
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.version, BuildConfig.GIT_SHORT_SHA, BuildConfig.BUILD_TIMESTAMP),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Extensive Filters: sort, price range, the vertical's declared chips. Applied on Apply. */
@Composable
private fun FiltersSheet(state: SearchState) {
    val v = state.v()
    val f0 = state.filtersOf(v.id)
    var sort by remember { mutableStateOf(f0.sort) }
    var min by remember { mutableStateOf(f0.min?.toString() ?: "") }
    var max by remember { mutableStateOf(f0.max?.toString() ?: "") }
    var chips by remember { mutableStateOf(f0.chips) }
    ModalBottomSheet(onDismissRequest = { state.sheet = null }) {
        Column(Modifier.padding(Metrics.gutter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Text(stringResource(R.string.extensive_filters), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.sort_by), style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                items(Filters.Sort.entries.toList(), key = { it.id }) { s ->
                    FilterChip(selected = s == sort, onClick = { sort = s }, label = { Text(stringResource(sortLabel(s))) })
                }
            }
            Text(stringResource(R.string.price_range), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                OutlinedTextField(min, { min = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.min)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(max, { max = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.max)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            if (v.chips.isNotEmpty()) {
                Text(stringResource(R.string.more_options), style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    items(v.chips, key = { it.id }) { c ->
                        FilterChip(selected = c.id in chips, onClick = { chips = if (c.id in chips) chips - c.id else chips + c.id }, label = { Text(c.label) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                OutlinedButton(onClick = { state.filters[v.id] = Filters(); state.sheet = null }) { Text(stringResource(R.string.reset)) }
                Button(
                    onClick = {
                        state.filters[v.id] = f0.copy(sort = sort, min = num(min), max = num(max), chips = chips)
                        state.sheet = null
                    },
                    modifier = Modifier.testTag(Tags.APPLY_FILTERS),
                ) { Text(stringResource(R.string.apply_filters)) }
            }
            Spacer(Modifier.width(Metrics.gap))
        }
    }
}

fun sortLabel(s: Filters.Sort): Int = when (s) {
    Filters.Sort.RELEVANCE -> R.string.sort_relevance
    Filters.Sort.NEWEST -> R.string.sort_newest
    Filters.Sort.PRICE_ASC -> R.string.sort_price_asc
    Filters.Sort.PRICE_DESC -> R.string.sort_price_desc
}

/** A typed number, comma or point; blank or unreadable = no bound. */
fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
