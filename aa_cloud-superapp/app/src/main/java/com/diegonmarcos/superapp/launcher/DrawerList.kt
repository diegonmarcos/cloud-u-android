package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** Where a drawer row goes when it is tapped; the host Activity resolves it. */
internal sealed class DrawerTarget {
    data class Section(val id: String, val label: String) : DrawerTarget()
    data class Page(val sectionId: String, val pageId: String, val label: String) : DrawerTarget()
    data class Action(val actionType: String) : DrawerTarget()
    /** Aggregator tile target — uses the existing tile grammar
     *  (section: / page: / action: / http(s):// / app:// / intent://)
     *  so the activity's onTileClicked handler resolves it. */
    data class Tile(val target: String) : DrawerTarget()
}

/**
 * One row of a drawer menu. [kind]: a TITLE is a group's heading (the sub-menu title), a HEADING a
 * declared heading row (disabled, greyed), an ITEM a tappable destination with its icon.
 */
internal class DrawerRow(
    val label: String,
    val kind: Kind = Kind.ITEM,
    val iconRes: Int = 0,
    val target: DrawerTarget? = null,
) {
    enum class Kind { TITLE, HEADING, ITEM }
}

internal fun drawerRowTag(i: Int) = "drawer:row:$i"
internal const val TAG_DRAWER_LIST = "drawer:list"

/**
 * The dense drawer menu the section rail and the Home drawer share (was a NavigationView): a list of
 * [DrawerRow]s under the palette, an optional [header] above the first row. Headings and titles do
 * not react to taps; an item calls [onPick] with its target.
 */
@Composable
internal fun DrawerList(
    rows: List<DrawerRow>,
    onPick: (DrawerTarget) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    LazyColumn(modifier.testTag(TAG_DRAWER_LIST), state = rememberLazyListState()) {
        if (header != null) item { header() }
        itemsIndexed(rows) { i, row ->
            when (row.kind) {
                DrawerRow.Kind.TITLE -> Text(row.label, color = p.accent, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 2.dp).alpha(0.8f).testTag(drawerRowTag(i)))
                DrawerRow.Kind.HEADING -> Text(row.label, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp, bottom = 2.dp).alpha(0.7f).testTag(drawerRowTag(i)))
                DrawerRow.Kind.ITEM -> Row(
                    Modifier.fillMaxWidth()
                        .clickable { row.target?.let(onPick) }
                        .padding(horizontal = 10.dp, vertical = 3.dp).testTag(drawerRowTag(i)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.iconRes != 0) {
                        Icon(painterResource(row.iconRes), contentDescription = null, tint = p.textPrimary,
                            modifier = Modifier.size(18.dp))
                    } else {
                        androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))
                    }
                    Text(row.label, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
