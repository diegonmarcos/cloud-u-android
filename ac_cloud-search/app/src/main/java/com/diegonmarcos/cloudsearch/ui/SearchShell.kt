package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.Filters
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.SearchHost
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse
import com.diegonmarcos.superapp.searchpage.SearchChatState
import com.diegonmarcos.superapp.searchpage.SearchPageTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the mockup's menus is open over the shell (one at a time, like its closeAllMenus()). */
/** build.json::ui as baked into BuildConfig (#868): the island's items and each vertical's sub-page strip. */
val NAV: NavDecl by lazy {
    NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
}

enum class Menu { CATEGORIES, FILTERS, SESSIONS, PROFILE }

/** Everything the shell remembers while it is up; the durable half lives in [Services.prefs]. */
class SearchState(val services: Services) {
    val cfg: SearchConfig = services.cfg
    var vertical by mutableStateOf(cfg.defaultVertical)
    var dark by mutableStateOf(services.prefs.dark)
    var city by mutableStateOf(services.prefs.city)
    var menu by mutableStateOf<Menu?>(null)
    /** Saved Items (the menu's own view of every starred result) is showing instead of a vertical. */
    var saved by mutableStateOf(false)
    var busy by mutableStateOf(0)
    private val subpages = mutableStateMapOf<String, String>()
    val filters = mutableStateMapOf<String, Filters>()
    val listings = mutableMapOf<String, ListingModel>()
    private val thingsModels = mutableMapOf<String, ThingsModel>()
    /** Bumped when the Things area (location use, typed city, radius) changes: the page asks again. */
    var areaRev by mutableStateOf(0)
    val chat = SearchChatState(SearchHost(services))

    fun v(): SearchConfig.Vertical = cfg.vertical(vertical) ?: cfg.verticals.first()
    fun subpageOf(v: SearchConfig.Vertical): String = subpages[v.id]?.takeIf { it in v.subpages } ?: v.subpages.first()
    fun showSubpage(v: SearchConfig.Vertical, id: String) { subpages[v.id] = id }
    fun filtersOf(v: String): Filters = filters[v] ?: Filters()
    fun listing(v: String): ListingModel = listings.getOrPut(v) { ListingModel(services.prefs.lastQuery(v)) }
    fun things(v: String): ThingsModel = thingsModels.getOrPut(v) { ThingsModel(services.prefs.lastQuery(v)) }

    /** #903 the settings that place the Things search changed: every Things page asks again. */
    fun areaChanged() { thingsModels.values.forEach { it.stale = true }; areaRev++ }

    /** The mockup's switchTopic(): a vertical opens on its first subpage, menus close. */
    fun open(id: String) { vertical = id; saved = false; menu = null; cfg.vertical(id)?.let { subpages[it.id] = it.subpages.first() } }
    fun toggle(m: Menu) { menu = if (menu == m) null else m }
    fun toggleDark() { dark = !dark; services.prefs.dark = dark }
    fun pickCity(id: String) { city = id; services.prefs.city = id; listings.values.forEach { it.stale = true }; areaChanged() }
}

val LocalState = staticCompositionLocalOf<SearchState> { error("SearchShell provides the state") }

/** The test tags the Robolectric smoke test drives the shell by. */
object Tags {
    const val SHELL = "search_shell"
    const val MENU = "search_menu"
    const val ISLAND = "search_island"
    const val PROFILE = "search_profile"
    const val PROFILE_MENU = "search_profile_menu"
    const val CATEGORIES = "search_categories"
    const val FILTERS = "search_filters"
    const val SESSIONS_MENU = "search_sessions_menu"
    const val CLOSE_MENU = "search_close_menu"
    const val THEME = "search_theme_toggle"
    const val TOKEN = "search_token_status"
    const val TOKEN_CHECK = "search_token_check"
    const val SEARCH_BOX = "search_box"
    const val MORE_FILTERS = "search_more_filters"
    const val APPLY_FILTERS = "search_apply_filters"
    const val SORT = "search_sort"
    const val CHAT_INPUT = SearchPageTags.CHAT_INPUT
    const val CHAT_SEND = SearchPageTags.CHAT_SEND
    const val NEW_CHAT = SearchPageTags.NEW_CHAT
    const val SESSIONS = "search_sessions"
    const val MODEL = SearchPageTags.MODEL
    const val WEB = SearchPageTags.WEB
    const val SAVED = "search_saved"
    const val THINGS_AREA = "search_things_area"
    const val THINGS_SETTINGS = "search_things_settings"
    const val THINGS_STORES = "search_things_stores"
    const val THINGS_TABLE = "search_things_table"
    const val THINGS_USE_LOCATION = "search_things_use_location"
    const val THINGS_CITY = "search_things_city"
    const val THINGS_RADIUS = "search_things_radius"
    const val THINGS_APPLY = "search_things_apply"
    fun thingsRow(osm: String) = "search_things_row_$osm"
    /** libs:bottomnav's island item tag (BottomNavBar.itemTag, internal to the lib). */
    fun nav(id: String) = "bottomnav_item_$id"
    /** A sub-page pill of the page-tab strip. */
    fun subpage(id: String) = PageTabsTags.tab(id)
    fun page(kind: String) = "search_page_$kind"
    fun card(key: String) = "search_card_$key"
    fun output(calc: String, id: String) = "search_out_${calc}_$id"
    fun field(calc: String, id: String) = "search_field_${calc}_$id"
    fun engine(id: String) = SearchPageTags.engine(id)
    fun source(id: String) = "search_source_$id"
    fun drawer(id: String) = "search_drawer_$id"
    fun chip(id: String) = "search_chip_$id"
    fun city(id: String) = "search_city_$id"
    fun option(id: String) = "search_option_$id"
}

@Composable
fun SearchShell(state: SearchState) {
    CompositionLocalProvider(LocalState provides state) {
        SearchTheme(state.dark) {
            val g = LocalGlass.current
            val v = state.v()
            val collapse = rememberBottomNavCollapse()
            Box(Modifier.fillMaxSize().background(g.background).testTag(Tags.SHELL)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding().nestedScroll(collapse)) {
                    // The page under the chrome: it pads itself by contentTop / contentBottom.
                    if (state.saved) SavedPage()
                    else {
                        val sub = state.subpageOf(v)
                        key(v.id, sub) { Page(v, state.cfg.subpage(sub)?.kind ?: "") }
                    }
                    TopBar(state, v, Modifier.align(Alignment.TopCenter))
                    // The keyboard takes the bottom of the screen; the nav returns when it closes.
                    // #868 the fleet's island, fed by build.json::ui. This box already clears the system
                    // bars, and the island reads what it consumed, so it clears none of them twice.
                    if (!imeOpen()) {
                        BottomNavIsland(
                            entries = NAV.islandEntries { painterResource(IconCatalog.res(it)) },
                            selectedId = if (state.saved) null else state.vertical,
                            onSelect = { state.open(it.id) },
                            modifier = Modifier.align(Alignment.BottomCenter),
                            collapsed = collapse.collapsed,
                        )
                    }
                }
                Scrim(state.menu != null) { state.menu = null }
                SideMenu(state.menu == Menu.CATEGORIES, Tags.CATEGORIES) { Categories(state) }
                SideMenu(state.menu == Menu.FILTERS, Tags.FILTERS) { FiltersMenu(state) }
                SideMenu(state.menu == Menu.SESSIONS, Tags.SESSIONS_MENU) { SessionsMenu(state) }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ProfilePopover(state.menu == Menu.PROFILE) { ProfileMenu(state) }
                }
            }
        }
    }
}

/** .top-nav: menu · the dynamic island · profile. */
@Composable
private fun TopBar(state: SearchState, v: SearchConfig.Vertical, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = Metrics.topOffset, start = Metrics.gutter, end = Metrics.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(R.drawable.ph_list, stringResource(R.string.menu), Tags.MENU) { state.toggle(Menu.CATEGORIES) }
        Spacer(Modifier.weight(1f))
        val text = when {
            state.busy > 0 || state.chat.sending -> stringResource(R.string.island_busy)
            state.saved -> stringResource(R.string.saved_items)
            else -> v.title
        }
        Island(if (state.saved) IconCatalog.SAVED else v.icon, text, Modifier.testTag(Tags.ISLAND))
        Spacer(Modifier.weight(1f))
        IconBtn(R.drawable.ph_user, stringResource(R.string.profile), Tags.PROFILE) { state.toggle(Menu.PROFILE) }
    }
}

/** The one dispatch from a subpage's declared `kind` to its page. */
@Composable
private fun Page(v: SearchConfig.Vertical, kind: String) {
    Box(Modifier.fillMaxSize().testTag(Tags.page(kind))) {
        when (kind) {
            "listing" -> ListingPage(v)
            "things" -> ThingsPage(v)
            "analysis" -> AnalysisPage(v)
            "feed" -> FeedPage(v)
            "calculators" -> CalculatorsPage(v)
            "assistant" -> AssistantPage(v)
            else -> Text(stringResource(R.string.no_renderer, kind), Modifier.padding(Metrics.gutter))
        }
    }
}

/** The hamburger menu: Categories (every vertical) and Saved Items. */
@Composable
private fun Categories(state: SearchState) {
    val g = LocalGlass.current
    MenuTitle(stringResource(R.string.drawer_title))
    state.cfg.verticals.forEach { v ->
        MenuItem(v.title, Tags.drawer(v.id), icon = { DeclaredIcon(v.icon, Metrics.icon, g.text) }) { state.open(v.id) }
    }
    Hairline(Modifier.padding(vertical = Metrics.gutter))
    MenuItem(stringResource(R.string.saved_items), Tags.drawer("saved"), icon = { Ph(R.drawable.ph_star, Metrics.icon, g.text) }) {
        state.saved = true
        state.menu = null
    }
}

/** Extensive Filters: sort, price range, the vertical's declared options. Applied on Apply Filters. */
@Composable
private fun FiltersMenu(state: SearchState) {
    val g = LocalGlass.current
    val v = state.v()
    val f0 = state.filtersOf(v.id)
    var sort by remember(v.id) { mutableStateOf(f0.sort) }
    var min by remember(v.id) { mutableStateOf(f0.min?.let { plain(it) } ?: "") }
    var max by remember(v.id) { mutableStateOf(f0.max?.let { plain(it) } ?: "") }
    var chips by remember(v.id) { mutableStateOf(f0.chips) }
    MenuTitle(stringResource(R.string.extensive_filters), { state.menu = null }, stringResource(R.string.close))
    FieldLabel(stringResource(R.string.sort_by))
    Select(stringResource(sortLabel(sort)), Filters.Sort.entries.map { it to stringResource(sortLabel(it)) }, Tags.SORT) { sort = it }
    FieldLabel(stringResource(R.string.price_range))
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        GlassField(min, { min = it }, stringResource(R.string.min), Tags.option("min"), Modifier.weight(1f), icon = null, number = true)
        GlassField(max, { max = it }, stringResource(R.string.max), Tags.option("max"), Modifier.weight(1f), icon = null, number = true)
    }
    Hairline(Modifier.padding(vertical = Metrics.small))
    if (v.chips.isEmpty()) Text(stringResource(R.string.no_advanced_filters), color = g.text2, style = Type.style(Type.small))
    else {
        FieldLabel(stringResource(R.string.more_options))
        v.chips.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.chipGap)) {
                pair.forEach { c ->
                    val on = c.id in chips
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(Metrics.tileRadius)).background(g.field)
                            .clickable { chips = if (on) chips - c.id else chips + c.id }.testTag(Tags.option(c.id))
                            .padding(horizontal = Metrics.chipGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { chips = if (on) chips - c.id else chips + c.id }, colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.text2))
                        Text(c.label, color = g.text, style = Type.style(Type.body), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Text(
        stringResource(R.string.apply_filters), color = g.onBadge, style = Type.style(Type.menuItem, FontWeight.Bold),
        modifier = Modifier.fillMaxWidth().padding(top = Metrics.gap).clip(RoundedCornerShape(Metrics.tileRadius)).background(g.accent)
            .clickable {
                state.filters[v.id] = f0.copy(sort = sort, min = num(min), max = num(max), chips = chips)
                state.menu = null
            }
            .testTag(Tags.APPLY_FILTERS).padding(vertical = Metrics.menuItemPad),
        textAlign = TextAlign.Center,
    )
}

/** A <select>: the current choice with a caret; the options drop down. */
@Composable
fun <T> Select(current: String, options: List<Pair<T, String>>, tag: String, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    val g = LocalGlass.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Metrics.tileRadius)
    Box(modifier) {
        Row(
            Modifier.clip(shape).background(g.field).border(Metrics.hairline, g.tileBorder, shape)
                .clickable { open = true }.testTag(tag).padding(horizontal = Metrics.gap, vertical = Metrics.chipGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.small),
        ) {
            Text(current, color = g.text, style = Type.style(Type.small), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Ph(R.drawable.ph_caret_down, Metrics.iconXs, g.text2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label, style = Type.style(Type.small)) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

/** Profile & settings: where the AI token comes from, the theme, the city, the build. */
@Composable
private fun ProfileMenu(state: SearchState) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    Column(Modifier.testTag(Tags.PROFILE_MENU), verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        Row(Modifier.padding(Metrics.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.cardPad)) {
            Box(Modifier.size(Metrics.profileAvatar).clip(CircleShape).background(g.field), contentAlignment = Alignment.Center) {
                Ph(R.drawable.ph_user, Metrics.icon, g.accent)
            }
            Column {
                Text(stringResource(R.string.fleet_account), color = g.text, style = Type.style(Type.menuItem, FontWeight.SemiBold))
                Text(stringResource(R.string.version, BuildConfig.GIT_SHORT_SHA), color = g.text2, style = Type.style(Type.label))
            }
        }
        Hairline()
        val box = RoundedCornerShape(Metrics.cardRadius)
        Column(
            Modifier.fillMaxWidth().clip(box).background(g.field).border(Metrics.hairline, g.glassBorder, box).padding(Metrics.cardPad),
            verticalArrangement = Arrangement.spacedBy(Metrics.small),
        ) {
            Text(stringResource(R.string.token_title), color = g.text2, style = Type.style(Type.label, FontWeight.Bold))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Chip(stringResource(R.string.token_check), Tags.TOKEN_CHECK, icon = R.drawable.ph_key) {
                    token = ctx.getString(R.string.token_checking)
                    scope.launch {
                        val t = withContext(Dispatchers.IO) { Account.token(ctx, state.cfg.ai.accountProvider) }
                        token = if (t.value != null) ctx.getString(R.string.token_present) else ctx.getString(R.string.token_missing, t.why)
                    }
                }
                Text(token ?: "", color = g.text, style = Type.style(Type.label), modifier = Modifier.weight(1f).testTag(Tags.TOKEN))
            }
            Text(stringResource(R.string.token_blurb), color = g.text2, style = Type.style(Type.tiny))
        }
        MenuItem(stringResource(R.string.toggle_theme), Tags.THEME, icon = { Ph(if (state.dark) R.drawable.ph_moon else R.drawable.ph_sun, Metrics.icon, g.text) }) {
            state.toggleDark()
        }
        Row(Modifier.padding(horizontal = Metrics.menuItemPad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.cardPad)) {
            Ph(R.drawable.ph_map_pin, Metrics.icon, g.text)
            Text(stringResource(R.string.city), color = g.text, style = Type.style(Type.menuItem))
        }
        ChipRow {
            state.cfg.cities.forEach { c -> Chip(c.label, Tags.city(c.id), selected = c.id == state.city) { state.pickCity(c.id) } }
        }
        if (state.cfg.things != null) {
            Hairline(Modifier.padding(vertical = Metrics.small))
            ThingsSettings(state)
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
