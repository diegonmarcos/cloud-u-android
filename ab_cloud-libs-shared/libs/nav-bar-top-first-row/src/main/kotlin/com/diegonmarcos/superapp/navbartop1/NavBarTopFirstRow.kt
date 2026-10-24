package com.diegonmarcos.superapp.navbartop1

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.bottomnav.AppTopBarController
import com.diegonmarcos.superapp.bottomnav.AppTopBarLabels
import com.diegonmarcos.superapp.bottomnav.AppTopBarScaffold
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.diegonmarcos.superapp.bottomnav.TopBarSettings
import com.diegonmarcos.superapp.bottomnav.appTopBarLabels
import com.diegonmarcos.superapp.bottomnav.rememberAppTopBarController
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors

/** Every number of the first row, in one place; NavBarTopFirstRowTest measures against these. */
public object TopFirstRowSpec {
    public val HEIGHT: Dp = 52.dp
    /** The touch target of each plain icon, and the glyph drawn in it (no circle behind it). */
    public val ICON_TARGET: Dp = 44.dp
    public val ICON: Dp = 24.dp
    public const val ICONS_RIGHT: Int = 3
    /** Left and right slots are the same width so the title island sits on the row's exact centre. */
    public val SLOT: Dp = ICON_TARGET * ICONS_RIGHT
    public val ISLAND_RADIUS: Dp = 20.dp
    public val ISLAND_PAD_H: Dp = 14.dp
    public val ISLAND_PAD_V: Dp = 7.dp
    public const val TITLE_SP: Int = 13

    /** The widest the island may be on a row of [rowWidth]: what is left between the two equal slots. */
    public fun islandMaxWidth(rowWidth: Dp): Dp = (rowWidth - SLOT * 2).coerceAtLeast(0.dp)
}

/**
 * The row's test tags. They are the SAME strings as libs:bottomnav's [TopBarTags] (the row this one replaces), so an
 * app's tests that find the hamburger, the title or an action by [TopBarTags] still find it.
 */
public object TopFirstRowTags {
    public const val ROW: String = TopBarTags.BAR
    public const val MENU: String = TopBarTags.MENU
    public const val ISLAND: String = TopBarTags.TITLE
    public const val AI: String = TopBarTags.AI
    public const val PROFILE: String = TopBarTags.ACCOUNT
    public const val CONFIGS: String = TopBarTags.CONFIGS
    public const val DROPDOWN: String = "navtop1_dropdown"
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
 * THE first row of every app (#1154): a plain hamburger at the left (it opens the side menu by tap; there is no
 * edge-swipe), the page title as an island at the centre (a rounded darker-gray box; a tap opens the hierarchy
 * dropdown of the page shown, bottom page then sub-pages, tap to navigate), and plain AI (sparkles), Profile
 * (round avatar) and Configs (vertical dots) icons at the right. No icon sits inside a circle.
 */
@Composable
public fun NavBarTopFirstRow(
    title: String,
    colors: ThemeColors,
    labels: AppTopBarLabels,
    hierarchy: List<HierarchyEntry>,
    onNavigate: (HierarchyEntry) -> Unit,
    onMenu: () -> Unit,
    onAi: () -> Unit,
    onProfile: () -> Unit,
    onConfigs: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the default round avatar glyph, e.g. the signed-in user's picture. */
    avatar: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(TopFirstRowSpec.HEIGHT).testTag(TopFirstRowTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(TopFirstRowSpec.SLOT), contentAlignment = Alignment.CenterStart) {
            PlainIcon(Icons.Filled.Menu, labels.menu, TopFirstRowTags.MENU, colors, onMenu)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(TopFirstRowSpec.ISLAND_RADIUS)
            Box {
                Text(
                    title,
                    Modifier.clip(shape).background(colors.island, shape).border(1.dp, colors.islandBorder, shape)
                        .clickable(enabled = hierarchy.isNotEmpty(), role = Role.Button) { open = true }
                        .padding(horizontal = TopFirstRowSpec.ISLAND_PAD_H, vertical = TopFirstRowSpec.ISLAND_PAD_V)
                        .testTag(TopFirstRowTags.ISLAND)
                        .semantics { heading() },
                    color = colors.onIsland,
                    fontSize = TopFirstRowSpec.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                DropdownMenu(
                    expanded = open, onDismissRequest = { open = false }, containerColor = colors.island,
                    modifier = Modifier.testTag(TopFirstRowTags.DROPDOWN),
                ) {
                    hierarchy.forEach { e ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    e.label,
                                    Modifier.padding(start = 16.dp * e.depth),
                                    fontWeight = if (e.selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (e.selected) colors.accentOnIsland else colors.onIsland,
                                )
                            },
                            onClick = { open = false; onNavigate(e) },
                            modifier = Modifier.testTag(TopFirstRowTags.entry(e.id)),
                        )
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
 * The fleet's top-bar scaffold with THIS row drawn in it (#1154): libs:bottomnav's scaffold keeps the side drawer (opened by
 * tapping the hamburger only), the AI popup and the hide-on-scroll slot; the row is [NavBarTopFirstRow]. [onAi] null opens
 * the scaffold's own AI popup ([aiPanel]); otherwise it is the host's. Profile and Configs are the host's ([onAccount], [onConfigs]).
 */
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
    content: @Composable () -> Unit,
) {
    AppTopBarScaffold(
        decl = TopBarDecl(drawer = true, settings = TopBarSettings.KNOWN, inlineActions = true),
        title = title,
        onAccount = onAccount,
        onConfigs = onConfigs,
        modifier = modifier,
        controller = controller,
        labels = labels,
        onAi = onAi,
        aiPanel = aiPanel,
        barVisible = barVisible,
        topRow = { c ->
            NavBarTopFirstRow(
                title, ThemeBackground.colors(dark), labels, hierarchy, onNavigate,
                onMenu = { c.openDrawer() },
                onAi = { if (onAi != null) onAi() else c.toggleAi() },
                onProfile = onAccount, onConfigs = onConfigs, avatar = avatar,
            )
        },
        content = content,
    )
}
