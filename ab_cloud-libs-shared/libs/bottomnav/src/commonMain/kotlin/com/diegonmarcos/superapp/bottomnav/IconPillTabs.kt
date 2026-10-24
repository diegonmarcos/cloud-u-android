package com.diegonmarcos.superapp.bottomnav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Samsung Health tab strip (ticket #1144): a capsule of ICON-ONLY tabs where the selected tab is a
 * highlighted pill that also shows its NAME. Drawn from the same [NavTokens] palettes as [PageTabs]
 * (this is [PageTabs]' `iconFor` mode, not a second widget): idle tabs are a bare glyph in the idle ink,
 * the selected tab wears the selected fill and stroke with its glyph and its label side by side.
 *
 * Smaller than the bottom nav (hierarchy: these are the children of the selected nav page): [TAB_HEIGHT],
 * [TAB_ICON], [TAB_LABEL_SP]. The row fills the width and shares the free space EVENLY (space-evenly): one tab
 * sits centred, N tabs have equal gaps between them and to both borders. When the selected name leaves too
 * little room the row scrolls rather than clipping a tab. Every tab is announced as a Tab with its
 * declared label, so an icon-only tab is still named for TalkBack.
 */
@Composable
internal fun IconPillStrip(
    pages: List<NavPage>,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier,
    onReselect: (NavPage) -> Unit,
    underTopChrome: Boolean,
    insets: WindowInsets?,
    surface: TabSurface,
    flushTop: Boolean,
    iconFor: @Composable (NavPage) -> Painter?,
) {
    if (pages.isEmpty()) return
    val tokens = navTokens()
    val colors = tokens.tabColors(surface)
    val density = LocalDensity.current
    val live = insets ?: WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val liveTop = if (underTopChrome && !flushTop) live.getTop(density) else 0
    val top = (if (flushTop) 0.dp else tokens.tabsTopInset) + with(density) { liveTop.toDp() }
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = top, bottom = tokens.tabsBottomInset, start = 8.dp, end = 8.dp)) {
        val capsule = CircleShape
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(PageTabsTags.STRIP)
                .clip(capsule)
                .background(colors.idleFill, capsule)
                .border(tokens.tabsPillStroke, colors.idleStroke, capsule)
                .horizontalScroll(scroll)
                // Vertical padding only: the free width is shared by SpaceEvenly, so the gap to each border equals the gap between tabs.
                .padding(vertical = tokens.tabsStripPadding)
                .widthIn(min = maxWidth)
                .selectableGroup()
                .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = pages.size) },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { i, page ->
                val on = page.id == selectedId
                val ink = if (on) colors.selectedText else colors.idleText
                val source = rememberSource()
                Row(
                    Modifier
                        .defaultMinSize(minWidth = TAB_MIN_WIDTH)
                        .heightIn(min = TAB_HEIGHT)
                        .clip(CircleShape)
                        .then(if (on) Modifier.background(colors.selectedFill, CircleShape).border(tokens.tabsPillStroke, colors.selectedStroke, CircleShape) else Modifier)
                        .clickable(interactionSource = source, indication = FleetIndication) {
                            if (on) onReselect(page) else onSelect(page)
                        }
                        .semantics {
                            role = Role.Tab
                            selected = on
                            contentDescription = page.label
                            collectionItemInfo = CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = i, columnSpan = 1)
                        }
                        .padding(horizontal = if (on) 12.dp else 8.dp)
                        .testTag(PageTabsTags.tab(page.id)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val painter = iconFor(page)
                    if (painter != null) {
                        Icon(painter, null, Modifier.size(TAB_ICON).testTag(PageTabsTags.icon(page.id)), tint = ink)
                    } else {
                        // No glyph declared: the initial stands in, so a tab is never blank.
                        Text(page.label.take(1).uppercase(), Modifier.testTag(PageTabsTags.icon(page.id)), color = ink,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    }
                    if (on) {
                        Text(
                            page.label,
                            Modifier.padding(start = 6.dp).testTag(PageTabsTags.label(page.id)),
                            color = ink,
                            style = TextStyle(fontSize = TAB_LABEL_SP.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// Sub-page tabs are the CHILDREN of the bottom-nav page, so they are drawn smaller than the nav: a 32dp
// target (the nav's collapsed item is 36dp, its icon 24dp, its label 12sp), an 18dp glyph, an 11sp name.
internal val TAB_HEIGHT = 32.dp
internal val TAB_MIN_WIDTH = 36.dp
internal val TAB_ICON = 18.dp
internal const val TAB_LABEL_SP = 11

@Composable
private fun rememberSource(): MutableInteractionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
