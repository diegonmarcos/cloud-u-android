package com.diegonmarcos.superapp.navbartop1

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.bottomnav.AppTopBarController
import com.diegonmarcos.superapp.bottomnav.LocalNavUiSettings
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.cleanDrawerModel
import com.diegonmarcos.superapp.bottomnav.currentNavUi
import com.diegonmarcos.superapp.bottomnav.navTopRowFill
import com.diegonmarcos.superapp.bottomnav.AppTopBarLabels
import com.diegonmarcos.superapp.bottomnav.NavChromeScroll
import com.diegonmarcos.superapp.bottomnav.AppTopBarScaffold
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.diegonmarcos.superapp.bottomnav.TopBarSettings
import com.diegonmarcos.superapp.bottomnav.appTopBarLabels
import com.diegonmarcos.superapp.bottomnav.rememberAppTopBarController
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import com.diegonmarcos.superapp.navbartop2.LocalSubPageRequests
import com.diegonmarcos.superapp.navbartop2.NavUiConfigDialog
import com.diegonmarcos.superapp.navbartop2.SubPageRequests
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors

/** Every number of the first row, in one place; NavBarTopFirstRowTest measures against these. */
public object TopFirstRowSpec {
    public val HEIGHT: Dp = 52.dp
    /** The touch target of each plain icon, and the glyph drawn in it (no circle behind it). */
    public val ICON_TARGET: Dp = 44.dp
    public val ICON: Dp = 24.dp
    public const val ICONS_RIGHT: Int = 3
    /** The right slot (AI, Profile, Configs); the title takes the rest of the row, from the left edge. */
    public val SLOT: Dp = ICON_TARGET * ICONS_RIGHT
    public val ISLAND_RADIUS: Dp = 20.dp
    public val ISLAND_PAD_H: Dp = 14.dp
    public val ISLAND_PAD_V: Dp = 7.dp
    public const val TITLE_SP: Int = 13
    /** The finite height bound the app's actions get inside the dropdown's own scroll: far more than a phone shows, so the dropdown scrolls them, and finite so their own scroll/LazyColumn cannot meet an infinite height. */
    public val ACTIONS_MAX_HEIGHT: Dp = 2000.dp
    public val DROPDOWN_WIDTH: Dp = 280.dp
    public val DROPDOWN_MAX_HEIGHT: Dp = 520.dp

    /** The widest the title island may be on a row of [rowWidth]: what the right slot leaves. */
    public fun islandMaxWidth(rowWidth: Dp): Dp = (rowWidth - SLOT).coerceAtLeast(0.dp)
}

/**
 * The row's test tags. They are the SAME strings as libs:bottomnav's [TopBarTags] (the row this one replaces), so an
 * app's tests that find the title or an action by [TopBarTags] still find it.
 */
public object TopFirstRowTags {
    public const val ROW: String = TopBarTags.BAR
    public const val ISLAND: String = TopBarTags.TITLE
    public const val AI: String = TopBarTags.AI
    public const val PROFILE: String = TopBarTags.ACCOUNT
    public const val CONFIGS: String = TopBarTags.CONFIGS
    public const val DROPDOWN: String = "navtop1_dropdown"
    /** The chevron that opens the dropdown when a tap on the title does something else (onTitleClick). */
    public const val MORE: String = "navtop1_more"
    public const val SEPARATOR_PAGES: String = "navtop1_sep_pages"
    public const val SEPARATOR_ACTIONS: String = "navtop1_sep_actions"
    public const val ACTIONS: String = "navtop1_actions"
    /** The shared "Configs > UI" entry at the end of the dropdown. */
    public const val CONFIGS_UI: String = "navtop1_configs_ui"
    public fun crumb(index: Int): String = "navtop1_crumb_$index"
    public fun entry(id: String): String = "navtop1_entry_$id"
}

/** One row of the title dropdown: the bottom page (depth 0) or one of its sub-pages (depth 1..). */
public data class HierarchyEntry(
    val id: String,
    val label: String,
    val depth: Int,
    /** The bottom-nav section this entry belongs to. */
    val sectionId: String,
    /** On the path to the page now shown. */
    val selected: Boolean,
    /** The page itself is a launch hand-off, not a destination. */
    val action: String = "",
)

/**
 * The hierarchy the title dropdown shows: [section] (the bottom page) first, then its sub-pages depth-first, the SAME
 * tree the side menu lists. [currentPageId] marks the path to the page now shown (the section itself when null or
 * unknown). Pure, so the tree is testable without drawing.
 */
public fun titleHierarchy(section: NavSection, currentPageId: String?): List<HierarchyEntry> {
    val onPath = section.path(currentPageId).map { it.id }.toSet()
    val out = ArrayList<HierarchyEntry>()
    out += HierarchyEntry(section.id, section.label, 0, section.id, selected = onPath.isEmpty())
    fun walk(pages: List<NavPage>, depth: Int) {
        for (p in pages) {
            out += HierarchyEntry(p.id, p.label, depth, section.id, selected = p.id in onPath, action = p.action)
            walk(p.pages, depth + 1)
        }
    }
    walk(section.pages, 1)
    return out
}

/**
 * [hierarchy] with the selection moved to the page a strip on screen shows ([shown], from [SubPageRequests.shownIds]), so the title
 * path and the dropdown follow a pick made on a strip or from the dropdown even when the app keeps no sub-page state of its own.
 * The deepest shown page that is in [hierarchy] wins (the LAST of [shown] among equals, which is the strip used last), unless the app already marked a deeper page. Pure.
 */
public fun hierarchyWithShown(hierarchy: List<HierarchyEntry>, shown: Collection<String>): List<HierarchyEntry> {
    if (shown.isEmpty()) return hierarchy
    val idx = shown.mapNotNull { id -> hierarchy.indexOfFirst { it.depth > 0 && it.id == id }.takeIf { it >= 0 } }
        .asReversed().maxByOrNull { hierarchy[it].depth } ?: return hierarchy
    val appDepth = hierarchy.filter { it.depth > 0 && it.selected }.maxOfOrNull { it.depth } ?: 0
    if (hierarchy[idx].depth < appDepth) return hierarchy
    val path = HashSet<Int>()
    var depth = hierarchy[idx].depth
    path += idx
    var k = idx - 1
    while (k >= 0 && depth > 1) { if (hierarchy[k].depth < depth) { depth = hierarchy[k].depth; if (depth > 0) path += k }; k-- }
    return hierarchy.mapIndexed { i, e -> if (e.depth == 0) e.copy(selected = false) else e.copy(selected = i in path) }
}

/**
 * THE first row of every app (#1154): no hamburger; the title at the LEFT as a breadcrumb path ("{App} / {Section} / {Page}",
 * [titleNav]) in a rounded darker-gray box, truncated from the left with an ellipsis when long; a tap opens the dropdown:
 * the path with each segment tappable, a separator, the siblings and children of the page shown, and (second separator)
 * whatever the page declared through [extra] (the app's non-navigation actions); and plain AI (sparkles), Profile (round
 * avatar) and Configs (vertical dots) icons at the right. No icon sits inside a circle.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun NavBarTopFirstRow(
    title: String,
    colors: ThemeColors,
    labels: AppTopBarLabels,
    hierarchy: List<HierarchyEntry>,
    onNavigate: (HierarchyEntry) -> Unit,
    onAi: () -> Unit,
    onProfile: () -> Unit,
    onConfigs: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the default round avatar glyph, e.g. the signed-in user's picture. */
    avatar: (@Composable () -> Unit)? = null,
    /** #1164 a host whose title IS an input (Cloud Browser's address): a tap on the title calls this and shows the title as is; a chevron beside it opens the dropdown. */
    onTitleClick: (() -> Unit)? = null,
    /** The app's name, the first segment of the path (default: the installed app's label). */
    appName: String = defaultAppName(),
    /** The app's bottom sections: the siblings of a section, and where the app segment goes (its first). */
    sections: List<NavSection> = emptyList(),
    /** A last path segment that is not a page, e.g. the open message's subject. */
    leaf: String? = null,
    /** Non-navigation actions of the app, listed under the dropdown's second separator. */
    extra: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    /** Non-null: the dropdown ends with the shared "Configs > UI" entry (nav mode, scroll-hide), which calls this. */
    onConfigsUi: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val nav = remember(appName, title, hierarchy, sections, leaf) { titleNav(appName, title, hierarchy, sections, leaf) }
    Row(
        modifier.semantics { testTagsAsResourceId = true }.fillMaxWidth().height(TopFirstRowSpec.HEIGHT).testTag(TopFirstRowTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(start = TopFirstRowSpec.ISLAND_PAD_H / 2), contentAlignment = Alignment.CenterStart) {
            val shape = RoundedCornerShape(TopFirstRowSpec.ISLAND_RADIUS)
            Box {
                // The title box has NO fill and NO outline (NavTokens.topRowFill, transparent): the app's own background shows through.
                Row(
                    Modifier.clip(shape).background(navTopRowFill(), shape)
                        .clickable(role = Role.Button) { if (onTitleClick != null) onTitleClick() else open = true }
                        .padding(horizontal = TopFirstRowSpec.ISLAND_PAD_H, vertical = TopFirstRowSpec.ISLAND_PAD_V)
                        .testTag(TopFirstRowTags.ISLAND)
                        .semantics { heading() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onTitleClick != null) {
                        Text(title, color = colors.onIsland, fontSize = TopFirstRowSpec.TITLE_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
                        Icon(
                            Icons.Filled.ArrowDropDown, contentDescription = labels.drawer, tint = colors.onIsland,
                            modifier = Modifier.size(TopFirstRowSpec.ICON).clickable(role = Role.Button) { open = true }.testTag(TopFirstRowTags.MORE),
                        )
                    } else {
                        BreadcrumbText(nav.labels, colors.onIsland)
                    }
                }
                // A Popup, not material's DropdownMenu: that sizes its content by INTRINSIC width, which a page's actions
                // (a LazyColumn, BoxWithConstraints) cannot answer, and it crashed them.
                if (open) Popup(
                    popupPositionProvider = remember { BelowAnchor },
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                  // The popup is its own window: the runtime nav walk (cloud-android-nav-walk.py) finds the entries by tag.
                  Box(Modifier.semantics { testTagsAsResourceId = true }) {
                  Surface(
                    color = colors.island, shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp,
                    modifier = Modifier.width(TopFirstRowSpec.DROPDOWN_WIDTH).heightIn(max = TopFirstRowSpec.DROPDOWN_MAX_HEIGHT).testTag(TopFirstRowTags.DROPDOWN),
                  ) {
                  Column(Modifier.verticalScroll(rememberScrollState())) {
                    // line 1: the path, each segment a tap to that ancestor
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        nav.crumbs.forEachIndexed { i, c ->
                            if (i > 0) Text(" / ", color = colors.onIsland, fontSize = TopFirstRowSpec.TITLE_SP.sp)
                            val target = c.target
                            Text(
                                c.label,
                                (if (target != null) Modifier.clickable(role = Role.Button) { open = false; onNavigate(target) } else Modifier)
                                    .testTag(TopFirstRowTags.crumb(i)),
                                color = if (i == nav.crumbs.lastIndex) colors.accentOnIsland else colors.onIsland,
                                fontSize = TopFirstRowSpec.TITLE_SP.sp,
                                fontWeight = if (i == nav.crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                    if (nav.related.isNotEmpty()) HorizontalDivider(Modifier.testTag(TopFirstRowTags.SEPARATOR_PAGES), color = colors.islandBorder)
                    nav.related.forEach { e ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    e.label,
                                    Modifier.padding(start = 16.dp * (e.depth - nav.currentDepth).coerceAtLeast(0)),
                                    fontWeight = if (e.selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (e.selected) colors.accentOnIsland else colors.onIsland,
                                )
                            },
                            onClick = { open = false; onNavigate(e) },
                            modifier = Modifier.testTag(TopFirstRowTags.entry(e.id)),
                        )
                    }
                    if (extra != null) {
                        HorizontalDivider(Modifier.testTag(TopFirstRowTags.SEPARATOR_ACTIONS), color = colors.islandBorder)
                        // The popup's column scrolls (infinite height), so a page's actions get a FINITE bound: its own verticalScroll /
                        // LazyColumn then measures instead of crashing, and the popup's scroll reaches all of it.
                        Column(Modifier.heightIn(max = TopFirstRowSpec.ACTIONS_MAX_HEIGHT).testTag(TopFirstRowTags.ACTIONS)) { extra { open = false } }
                    }
                    if (onConfigsUi != null) {
                        HorizontalDivider(color = colors.islandBorder)
                        DropdownMenuItem(
                            text = { Text(NavUiDecl.CONFIGS_UI_LABEL, color = colors.onIsland) },
                            onClick = { open = false; onConfigsUi() },
                            modifier = Modifier.testTag(TopFirstRowTags.CONFIGS_UI),
                        )
                    }
                  }
                  }
                  }
                }
            }
        }
        Row(Modifier.width(TopFirstRowSpec.SLOT), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PlainIcon(Icons.Filled.AutoAwesome, labels.ai, TopFirstRowTags.AI, colors, onAi)
            if (avatar != null) {
                Box(
                    Modifier.size(TopFirstRowSpec.ICON_TARGET).testTag(TopFirstRowTags.PROFILE)
                        .clickable(role = Role.Button, onClick = onProfile).semantics { contentDescription = labels.account },
                    contentAlignment = Alignment.Center,
                ) { avatar() }
            } else PlainIcon(Icons.Filled.AccountCircle, labels.account, TopFirstRowTags.PROFILE, colors, onProfile)
            PlainIcon(Icons.Filled.MoreVert, labels.configs, TopFirstRowTags.CONFIGS, colors, onConfigs)
        }
    }
}

/** The installed app's label: the first segment of the path unless the host names it. */
@Composable
public fun defaultAppName(): String {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
}

/** The path on one line, cut from the LEFT ("... / Inbox / Subject") to the width the row leaves it. */
@Composable
private fun BreadcrumbText(labels: List<String>, color: androidx.compose.ui.graphics.Color) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = TopFirstRowSpec.TITLE_SP.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints {
        val maxPx = constraints.maxWidth
        val line = if (constraints.hasBoundedWidth) {
            fitCrumbs(labels) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= maxPx }
        } else labels.joinToString(" / ")
        Text(line, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
    }
}

/** A bare glyph in a [TopFirstRowSpec.ICON_TARGET] touch target: no circle, no fill. */
@Composable
private fun PlainIcon(icon: ImageVector, label: String, tag: String, colors: ThemeColors, onClick: () -> Unit) {
    Box(
        Modifier.size(TopFirstRowSpec.ICON_TARGET).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(TopFirstRowSpec.ICON))
    }
}

/**
 * The fleet's top-bar scaffold with THIS row drawn in it (#1154): libs:bottomnav's scaffold keeps the AI popup and the hide-on-scroll slot (there is no hamburger: what a page
 * puts in TopBarDrawer is listed at the end of the title dropdown); the row is [NavBarTopFirstRow]. [onAi] null opens
 * the scaffold's own AI popup ([aiPanel]); otherwise it is the host's. Profile and Configs are the host's ([onAccount], [onConfigs]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun NavBarTopFirstRowScaffold(
    title: String,
    hierarchy: List<HierarchyEntry>,
    onNavigate: (HierarchyEntry) -> Unit,
    onAccount: () -> Unit,
    onConfigs: () -> Unit,
    modifier: Modifier = Modifier,
    labels: AppTopBarLabels = appTopBarLabels(),
    controller: AppTopBarController = rememberAppTopBarController(),
    onAi: (() -> Unit)? = null,
    aiPanel: @Composable (close: () -> Unit) -> Unit = {},
    barVisible: Boolean = true,
    avatar: (@Composable () -> Unit)? = null,
    /** The app's light/dark mode: picks the shared palette (libs:theme-background). An app passes no colour. */
    dark: Boolean = true,
    /** #1164 see [NavBarTopFirstRow]'s onTitleClick. */
    onTitleClick: (() -> Unit)? = null,
    /** The first path segment (default: the installed app's label). */
    appName: String = defaultAppName(),
    /** The app's bottom sections: siblings of a section, and where a tap on the app segment goes. */
    sections: List<NavSection> = emptyList(),
    /** A last path segment that is not a page; a page may also set it with [BreadcrumbLeaf]. */
    leaf: String? = null,
    /** False: this app draws the Configs > UI card in its own Configs page and the first row carries no entry for it. */
    configsUi: Boolean = true,
    content: @Composable () -> Unit,
) {
    var uiOpen by remember { mutableStateOf(false) }
    if (uiOpen) NavUiConfigDialog(dark) { uiOpen = false }
    // Classic or Clean (NavUiMode): the user's choice in this app's Configs > UI over the app's declared default; every bar below reads the same.
    val ui = currentNavUi()
    val plan = NavUiDecl.plan(ui.mode)
    val requests = remember { SubPageRequests() }
    val leafState = remember { BreadcrumbLeafState() }
    // A pick below the bottom page asks the strips of the page it opens to select it (the app only switches the page).
    val navigate: (HierarchyEntry) -> Unit = { e -> if (e.depth > 0) requests.request(e.id); onNavigate(e) }
    // The title path follows the page the strips show (a pick from a strip or the dropdown), not only the app's own state.
    val followed = hierarchyWithShown(hierarchy, requests.shownIds())
    // The Clean drawer: the page dropdown, the AI / Profile / Settings icons and the child-page dropdown (cleanDrawerModel).
    val here = followed.firstOrNull { it.depth == 0 }
    val bottom = sections.ifEmpty { listOfNotNull(here?.let { NavSection(it.sectionId, it.label, "") }) }
    val current = sections.firstOrNull { it.id == here?.sectionId }
    val cleanModel = cleanDrawerModel(bottom, current, followed.lastOrNull { it.depth > 0 && it.selected }?.id)
    val cleanDrawerBody: @Composable ColumnScope.(close: () -> Unit) -> Unit = { close ->
        CleanDrawerContent(
            model = cleanModel, labels = labels,
            onPage = { e -> navigate(HierarchyEntry(e.id, e.label, 0, e.id, selected = e.selected)) },
            onSubPage = { e -> navigate(HierarchyEntry(e.id, e.label, e.depth + 1, here?.sectionId ?: "", selected = e.selected)) },
            onAi = { if (onAi != null) onAi() else controller.toggleAi() },
            onProfile = onAccount, onConfigs = onConfigs, close = close,
            onConfigsUi = if (configsUi) ({ uiOpen = true }) else null,
        )
    }
    // #1157 scrolling the page down hides this row and compacts the second row and the bottom nav; up restores all three.
    // Scroll-hide (Configs > UI, on by default): a downward scroll hides ALL of them until an upward scroll reveals them again.
    CompositionLocalProvider(LocalNavUiSettings provides ui) {
    NavChromeScroll { collapse ->
        AppTopBarScaffold(
            decl = TopBarDecl(drawer = plan.hamburger, settings = TopBarSettings.KNOWN, inlineActions = true),
            drawerOverride = if (plan.hamburger) cleanDrawerBody else null,
            title = title,
            onAccount = onAccount,
            onConfigs = onConfigs,
            // Every test tag under the scaffold (the bars and the page) is a resource id for UiAutomator: the runtime nav walk reads them.
            modifier = modifier.semantics { testTagsAsResourceId = true },
            controller = controller,
            labels = labels,
            onAi = onAi,
            aiPanel = aiPanel,
            barVisible = barVisible && !collapse.collapsed,
            topRow = { c ->
                val slot = c.drawerContent
                if (plan.hamburger) NavBarTopCleanRow(ThemeBackground.colors(dark), labels, onMenu = c::openDrawer) else NavBarTopFirstRow(
                    title, ThemeBackground.colors(dark), labels, followed, navigate,
                    onAi = { if (onAi != null) onAi() else c.toggleAi() },
                    onProfile = onAccount, onConfigs = onConfigs, avatar = avatar, onTitleClick = onTitleClick,
                    appName = appName, sections = sections, leaf = leaf ?: leafState.text,
                    extra = if (slot != null) ({ close -> slot(close) }) else null,
                    onConfigsUi = if (configsUi) ({ uiOpen = true }) else null,
                )
            },
            content = { CompositionLocalProvider(LocalSubPageRequests provides requests, LocalBreadcrumbLeaf provides leafState, content = content) },
        )
    }
    }
}

/**
 * Clean mode's first row: TRANSPARENT (the app's background shows through, [navTopRowFill]) and nothing but the top-left hamburger, which
 * opens the left drawer that holds the page dropdown, the AI / Profile / Settings icons and the child-page dropdown ([CleanDrawerContent]).
 */
@Composable
public fun NavBarTopCleanRow(colors: ThemeColors, labels: AppTopBarLabels, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { testTagsAsResourceId = true }.fillMaxWidth().height(TopFirstRowSpec.HEIGHT).background(navTopRowFill()).testTag(TopFirstRowTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainIcon(Icons.Filled.Menu, labels.menu, CleanDrawerTags.MENU, colors, onMenu)
    }
}

/** The path's last, page-less segment (an open message's subject), set by whichever page shows it. */
public class BreadcrumbLeafState {
    public var text: String? by mutableStateOf(null)
}

public val LocalBreadcrumbLeaf: ProvidableCompositionLocal<BreadcrumbLeafState> = compositionLocalOf { BreadcrumbLeafState() }

/** Call from a page while it shows [text] (e.g. the open message's subject): the title path ends with it, and it clears when the page leaves. */
@Composable
public fun BreadcrumbLeaf(text: String?) {
    val state = LocalBreadcrumbLeaf.current
    DisposableEffect(state, text) {
        state.text = text
        onDispose { if (state.text == text) state.text = null }
    }
}

/** The dropdown sits under the title, kept inside the window horizontally. */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), anchorBounds.bottom)
}
