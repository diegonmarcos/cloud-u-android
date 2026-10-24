package com.diegonmarcos.superapp.navbartop2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.bottomnav.TabSurface
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors

/**
 * The second row's sizes (#1154). It holds the CHILDREN of the bottom nav's page, so it is drawn clearly smaller
 * than [com.diegonmarcos.superapp.navbarbottom.BottomSpec]: a tab is [TAB_HEIGHT] against the nav's 56dp cell
 * (0.64), its glyph [ICON] against 24dp (0.67) and its name [LABEL] against 12sp (0.75, above the 8sp legibility
 * floor of the fleet). [SCALE_MAX] is the ratio ceiling NavBarTopSecondRowTest holds all three to.
 */
public object SecondRowSpec {
    public val TAB_HEIGHT: Dp = 36.dp
    public val ICON: Dp = 16.dp
    public val LABEL: TextUnit = 9.sp
    public const val SCALE_MAX: Float = 0.75f
    public val STRIP_RADIUS: Dp = 14.dp
    /** The selected tab is a rounded RECTANGLE, never a circle or a full pill. */
    public val TAB_RADIUS: Dp = 10.dp
    public val TAB_MIN_WIDTH: Dp = 40.dp
    public val SELECTED_PAD_H: Dp = 12.dp
    public val IDLE_PAD_H: Dp = 8.dp
    public val STRIP_PAD_V: Dp = 4.dp
    public val SIDE: Dp = 8.dp
    public val TOP_INSET: Dp = 10.dp
    public val BOTTOM_INSET: Dp = 10.dp
}

/** The strip's test tags: the SAME strings as libs:bottomnav's [PageTabsTags], so an app's tests that find a tab by them still do. */
public object SecondRowTags {
    public const val STRIP: String = PageTabsTags.STRIP
    public fun tab(id: String): String = PageTabsTags.tab(id)
    public fun icon(id: String): String = PageTabsTags.icon(id)
    public fun label(id: String): String = PageTabsTags.label(id)
}

/**
 * THE second top row of every app (#1154): the sub-pages of the bottom nav's page as small tabs. The free width is
 * shared SpaceEvenly, so one tab sits centred and several have equal gaps between them and to both borders. The
 * selected tab is a rounded rectangle filled with the SuperApp violet ([ThemeColors.tabSelected]) and shows its
 * name next to its glyph; the others are bare glyphs. [iconFor] gives a page's glyph (null = its initial).
 */
@Composable
public fun NavBarTopSecondRow(
    pages: List<NavPage>,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
    /** The app's light/dark mode: picks the shared palette (libs:theme-background). An app passes no colour. */
    dark: Boolean = true,
    onReselect: (NavPage) -> Unit = {},
    /** True: the strip clears the status bar and cutout (it sits at the very top of the window). */
    underTopChrome: Boolean = false,
    flushTop: Boolean = false,
    /** A page that is light (a white sheet): draws the light palette whatever [dark] says. Null = [dark]'s. */
    surface: TabSurface? = null,
    iconFor: @Composable (NavPage) -> Painter? = { null },
) {
    if (pages.isEmpty()) return
    val palette = when (surface) {
        TabSurface.Light -> ThemeBackground.colors(false)
        TabSurface.Dark -> ThemeBackground.colors(true)
        null -> ThemeBackground.colors(dark)
    }
    val density = LocalDensity.current
    val live = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val liveTop = if (underTopChrome && !flushTop) live.getTop(density) else 0
    val top = (if (flushTop) 0.dp else SecondRowSpec.TOP_INSET) + with(density) { liveTop.toDp() }
    val strip = RoundedCornerShape(SecondRowSpec.STRIP_RADIUS)
    val tab = RoundedCornerShape(SecondRowSpec.TAB_RADIUS)
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = top, bottom = SecondRowSpec.BOTTOM_INSET, start = SecondRowSpec.SIDE, end = SecondRowSpec.SIDE)) {
    Row(
        Modifier.fillMaxWidth()
            .testTag(SecondRowTags.STRIP)
            .clip(strip).background(palette.tabStrip, strip).border(1.dp, palette.islandBorder, strip)
            // A crowded row scrolls rather than clipping a tab; the free width of a roomy one is shared SpaceEvenly.
            .horizontalScroll(scroll)
            .padding(vertical = SecondRowSpec.STRIP_PAD_V)
            .widthIn(min = maxWidth)
            .selectableGroup()
            .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = pages.size) },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pages.forEachIndexed { i, page ->
            val on = page.id == selectedId
            val ink = if (on) palette.onTabSelected else palette.inkDim
            Row(
                Modifier.defaultMinSize(minWidth = SecondRowSpec.TAB_MIN_WIDTH).height(SecondRowSpec.TAB_HEIGHT)
                    .clip(tab)
                    .then(if (on) Modifier.background(palette.tabSelected, tab) else Modifier)
                    .clickable(role = Role.Tab) { if (on) onReselect(page) else onSelect(page) }
                    .semantics {
                        role = Role.Tab
                        selected = on
                        contentDescription = page.label
                        collectionItemInfo = CollectionItemInfo(0, 1, i, 1)
                    }
                    .padding(horizontal = if (on) SecondRowSpec.SELECTED_PAD_H else SecondRowSpec.IDLE_PAD_H)
                    .testTag(SecondRowTags.tab(page.id)),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val painter = iconFor(page)
                if (painter != null) {
                    Icon(painter, null, Modifier.size(SecondRowSpec.ICON).testTag(SecondRowTags.icon(page.id)), tint = ink)
                } else {
                    Text(page.label.take(1).uppercase(), Modifier.testTag(SecondRowTags.icon(page.id)), color = ink,
                        fontSize = SecondRowSpec.LABEL, fontWeight = FontWeight.Bold)
                }
                if (on) {
                    Text(
                        page.label, Modifier.padding(start = 6.dp).testTag(SecondRowTags.label(page.id)),
                        color = ink, fontSize = SecondRowSpec.LABEL, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    }
}
