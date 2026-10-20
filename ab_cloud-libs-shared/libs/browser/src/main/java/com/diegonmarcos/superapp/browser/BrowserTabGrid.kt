package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * GRID mode: the tab switcher, in Compose (a LazyVerticalGrid of cards and group headers).
 *
 * Long-press picks a card up and drags it (the owner asked for that, and it is why every per-tab
 * action lives behind the card's ⋮ and pin icons instead of a long-press menu); a horizontal swipe
 * closes a card ([SwipeGesture] holds the rules); the card has no ✕ button. The drag rules —
 * what swaps, what regroups on drop — are the same as they were when this was a RecyclerView.
 */
@Composable
fun BrowserTabGrid(
    rows: List<BrowserGridRow>,
    onOpen: (BrowserTab) -> Unit,
    /** Swipe left or right on a card: close it (the host confirms first for a pinned tab, and offers Undo). */
    onSwipeClose: (BrowserTab) -> Unit,
    /** The card's pin icon: toggle the pin. */
    onPin: (BrowserTab) -> Unit,
    /** The card's ⋮. */
    onMenu: (BrowserTab) -> Unit,
    onToggleGroup: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    /** #886 a tab was dropped onto another tab: (dragged key, target key) — the host groups them. */
    onDropOnTab: (String, String) -> Unit = { _, _ -> },
    /** #886 a tab was dropped onto a group's header: (dragged key, group). */
    onDropOnGroup: (String, String) -> Unit = { _, _ -> },
    /** #886 the ✎ on a group header: rename / recolour / ungroup. */
    onEditGroup: (String) -> Unit = {},
    /** #886 each group's colour (ARGB). */
    groupColors: () -> Map<String, Int> = { emptyMap() },
    /** Tab snapshots (memory LRU + disk); null = every card shows the favicon plate. */
    thumbs: TabThumbnails? = null,
    modifier: Modifier = Modifier,
) {
    val model = remember(rows) { TabGridModel(rows) }
    val gridState = rememberLazyGridState()
    val drag = remember(model) { TabDrag(model, gridState) }
    val reorder by rememberUpdatedState(onReorder)
    val dropOnTab by rememberUpdatedState(onDropOnTab)
    val dropOnGroup by rememberUpdatedState(onDropOnGroup)
    LazyVerticalGrid(
        columns = GridCells.Fixed(SPANS),
        state = gridState,
        modifier = modifier.fillMaxSize().testTag("browser:tabgrid"),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 24.dp),
    ) {
        items(
            model.rows,
            key = { it.gridKey() },
            // A group header is a divider, not a card — it owns the row.
            span = { GridItemSpan(if (it is BrowserGridRow.GroupHeader) maxLineSpan else 1) },
        ) { row ->
            when (row) {
                is BrowserGridRow.GroupHeader -> GroupHeaderRow(
                    row, groupColors, highlighted = drag.hover?.let { it.tabKey == null && it.group == row.group } == true,
                    onToggle = onToggleGroup, onEdit = onEditGroup)
                is BrowserGridRow.TabCard -> TabCardItem(
                    tab = row.tab, thumbs = thumbs, groupColors = groupColors, drag = drag,
                    onOpen = onOpen, onSwipeClose = onSwipeClose, onPin = onPin, onMenu = onMenu,
                    onDrop = { drag.finish(reorder, dropOnTab, dropOnGroup) })
            }
        }
    }
}

private const val SPANS = 2

internal fun BrowserGridRow.gridKey(): String = when (this) {
    is BrowserGridRow.GroupHeader -> "g:$group"
    is BrowserGridRow.TabCard -> tab.key
}

// ── model + drag state (plain Kotlin, no view) ────────────────────────

/** The rows being drawn, and the moves a drag makes in them. */
internal class TabGridModel(source: List<BrowserGridRow>) {
    val rows = mutableStateListOf<BrowserGridRow>().also { it.addAll(source) }

    fun indexOf(key: String): Int = rows.indexOfFirst { it.gridKey() == key }

    /** The drawn tabs' keys in drawn order (the name is from when tabs were keyed by url). */
    fun visibleTabUrls(): List<String> = BrowserGridRows.visibleTabs(rows).map { it.key }

    fun tabByKey(key: String): BrowserTab? = BrowserGridRows.visibleTabs(rows).firstOrNull { it.key == key }

    /** Row position → index within the drawn tab list. */
    private fun tabIndex(pos: Int): Int {
        if (rows.getOrNull(pos) !is BrowserGridRow.TabCard) return -1
        var n = 0
        for (i in 0 until pos) if (rows[i] is BrowserGridRow.TabCard) n++
        return n
    }

    fun canMove(from: Int, to: Int): Boolean {
        val fi = tabIndex(from); val ti = tabIndex(to)
        if (fi < 0 || ti < 0) return false
        return BrowserTabOrder.canMove(BrowserGridRows.visibleTabs(rows), fi, ti)
    }

    fun move(from: Int, to: Int): Boolean {
        if (!canMove(from, to)) return false
        rows.add(to, rows.removeAt(from))
        return true
    }
}

/** One long-press drag: the finger's position, what is under it, and what the drop means. */
internal class TabDrag(private val model: TabGridModel, private val grid: LazyGridState) {
    /** The key of the tab being dragged, kept from pick-up to drop (its row position moves). */
    var key by mutableStateOf<String?>(null); private set
    /** The card or header under the dragged card's centre: the drop target, lit while hovered. */
    var hover by mutableStateOf<BrowserTabGroups.Slot?>(null); private set
    /** The dragged card's centre in grid coordinates. */
    var pointer by mutableStateOf(Offset.Zero); private set

    private var start = Offset.Zero
    private var total = Offset.Zero
    private var guard: List<Any>? = null

    private fun order(): List<Any> = grid.layoutInfo.visibleItemsInfo.map { it.key }

    fun begin(k: String) {
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } ?: return
        key = k; hover = null; total = Offset.Zero; guard = null
        start = Offset(info.offset.x + info.size.width / 2f, info.offset.y + info.size.height / 2f)
        pointer = start
    }

    fun move(delta: Offset) {
        val k = key ?: return
        total += delta
        pointer = start + total
        val cx = pointer.x.toInt(); val cy = pointer.y.toInt()
        val slots = grid.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val row = model.rows.firstOrNull { it.gridKey() == info.key } ?: return@mapNotNull null
            val l = info.offset.x; val t = info.offset.y; val r = l + info.size.width; val b = t + info.size.height
            when (row) {
                is BrowserGridRow.TabCard -> BrowserTabGroups.Slot(row.tab.key, null, l, t, r, b)
                is BrowserGridRow.GroupHeader -> BrowserTabGroups.Slot(null, row.group, l, t, r, b)
            }
        }
        val hit = BrowserTabGroups.hit(slots, cx, cy, k)
        hover = hit
        // Reordering (a swap while dragging) is for tabs of one NAMED group; a loose tab dragged over
        // another is a regroup, decided on drop from [hover], so no swap may happen under it.
        if (guard != null && order() == guard) return   // the last swap has not been laid out yet
        guard = null
        val a = model.tabByKey(k) ?: return
        val over = hit?.tabKey ?: return
        val from = model.indexOf(k); val to = model.indexOf(over)
        if (a.group.isNotBlank() && model.canMove(from, to)) {
            model.move(from, to)
            guard = order()
        }
    }

    /** The finger came up: reorder, or regroup when it came up ON a tab or a header. */
    fun finish(onReorder: (List<String>) -> Unit, onDropOnTab: (String, String) -> Unit, onDropOnGroup: (String, String) -> Unit) {
        val k = key; val target = hover
        key = null; hover = null; guard = null
        // #886 dropped ON a tab or a group header: regroup (the host redraws). Otherwise it was a
        // plain move, and the order that has to survive a restart is the one he let go of — persist
        // on drop, not on every intermediate move.
        val dragGroup = k?.let { model.tabByKey(it)?.group }.orEmpty()
        val targetGroup = target?.tabKey?.let { model.tabByKey(it)?.group } ?: target?.group
        when {
            // Same named group: a reorder (already applied by move), not a regroup.
            k == null || (targetGroup != null && targetGroup.isNotBlank() && targetGroup == dragGroup) ->
                onReorder(model.visibleTabUrls())
            target?.tabKey != null -> onDropOnTab(k, target.tabKey)
            target?.group != null -> onDropOnGroup(k, target.group)
            else -> onReorder(model.visibleTabUrls())
        }
    }

    fun cancel() { key = null; hover = null; guard = null }

    /** Where the dragged card's own layout slot is now, as an offset from the finger-held centre. */
    fun shiftOf(k: String): Offset {
        if (key != k) return Offset.Zero
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } ?: return Offset.Zero
        return Offset(pointer.x - (info.offset.x + info.size.width / 2f), pointer.y - (info.offset.y + info.size.height / 2f))
    }
}

// ── header ───────────────────────────────────────────────────────────

@Composable
private fun GroupHeaderRow(
    row: BrowserGridRow.GroupHeader,
    groupColors: () -> Map<String, Int>,
    highlighted: Boolean,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val chevron = if (row.collapsed) "▸" else "▾"
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = if (highlighted) 1.04f else 1f; scaleY = scaleX }
            .clickable { onToggle(row.group) }
            .padding(start = 10.dp, top = 14.dp, end = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● ", color = Color(BrowserTabGroups.colorOf(groupColors(), row.group)))
        Text("$chevron  ${row.group}  (${row.count})", color = Color(0xFFE9D8FD), fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text("  ✎  ", color = Color(0xCCFFFFFF), modifier = Modifier.clickable { onEdit(row.group) })
    }
}

// ── card ─────────────────────────────────────────────────────────────

@Composable
private fun TabCardItem(
    tab: BrowserTab,
    thumbs: TabThumbnails?,
    groupColors: () -> Map<String, Int>,
    drag: TabDrag,
    onOpen: (BrowserTab) -> Unit,
    onSwipeClose: (BrowserTab) -> Unit,
    onPin: (BrowserTab) -> Unit,
    onMenu: (BrowserTab) -> Unit,
    onDrop: () -> Unit,
) {
    val key = tab.key
    val dragging = drag.key == key
    val highlighted = drag.hover?.tabKey == key
    // Snapshot: memory when cached (no IO), else loaded off the UI thread; the favicon the same.
    var snapshot by remember(key) { mutableStateOf(thumbs?.cached(key)) }
    var icon by remember(key, tab.iconPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(key, thumbs) { if (snapshot == null) thumbs?.load(key) { snapshot = it } }
    LaunchedEffect(tab.iconPath, thumbs) {
        if (tab.iconPath.isNotBlank()) thumbs?.loadIcon(tab.iconPath) { icon = it }
    }

    var swipeX by remember(key) { mutableFloatStateOf(0f) }
    var width by remember(key) { mutableFloatStateOf(1f) }
    val onSwipe by rememberUpdatedState(onSwipeClose)
    val tabNow by rememberUpdatedState(tab)
    val onDropNow by rememberUpdatedState(onDrop)

    val ring = when {
        highlighted -> 3.dp to Color(0xFFFFD166)
        tab.pinned -> 2.dp to Color(0xFFFFD166)   // pinned: marked on the card itself
        tab.isPrivate -> 2.dp to Color(0xFF9CA3AF)
        else -> 1.dp to Color(0x55B794F4)
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .padding(6.dp)
            .fillMaxWidth()
            .height(220.dp)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                val s = drag.shiftOf(key)
                translationX = swipeX + s.x
                translationY = s.y
                val z = if (highlighted) 1.04f else 1f
                scaleX = z; scaleY = z
                alpha = if (dragging) 0.92f else 1f - (kotlin.math.abs(swipeX) / (width * 1.5f)).coerceIn(0f, 0.6f)
            }
            .testTag("browser:tabcard:$key")
            .clip(shape)
            .background(if (tab.isPrivate) Color(0xFF1F2937) else Color(0xFF1A0033), shape)
            .border(ring.first, ring.second, shape)
            .onSizeChanged { width = it.width.toFloat() }
            // A horizontal swipe closes it; a pinned tab snaps back and the host asks first.
            .pointerInput(key) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onDragEnd = {
                        val far = kotlin.math.abs(swipeX) >= width * SwipeGesture.THRESHOLD
                        if (far && drag.key == null) onSwipe(tabNow)
                        if (!far || SwipeGesture.needsConfirm(tabNow)) swipeX = 0f
                    },
                    onDragCancel = { swipeX = 0f },
                    onHorizontalDrag = { change, amount -> if (drag.key == null) { change.consume(); swipeX += amount } },
                )
            }
            // Long-press picks the card up (after the swipe in the chain, so it sees a held touch first).
            .pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag.begin(key) },
                    onDrag = { change, amount -> change.consume(); drag.move(amount) },
                    onDragEnd = { if (drag.key != null) onDropNow() },
                    onDragCancel = { drag.cancel() },
                )
            }
            .clickable { onOpen(tab) },
    ) {
        // The page snapshot, cropped to fill the card and anchored to its top.
        val shot = snapshot
        if (shot != null) {
            val img = remember(shot) { shot.asImageBitmap() }
            Image(img, TabCardA11y.SNAPSHOT, Modifier.fillMaxSize(), contentScale = TAB_SNAPSHOT_SCALE, alignment = TAB_SNAPSHOT_ALIGN)
        } else {
            // No snapshot yet: the favicon on a plain plate.
            Box(Modifier.fillMaxSize().background(Color(0xFF24103F)))
            val fav = icon
            Box(Modifier.size(48.dp).align(Alignment.Center).semantics { contentDescription = TabCardA11y.PLACEHOLDER }) {
                if (fav != null) Image(remember(fav) { fav.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        // #886 a thin bar in the group's colour across the top of a grouped tab's card.
        if (tab.group.isNotBlank()) {
            Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.TopCenter)
                .background(Color(BrowserTabGroups.colorOf(groupColors(), tab.group))))
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0xCC000000))
                .padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The pin icon is always there: bright when pinned, dim when not, and a tap toggles it.
            Text("📌", Modifier.alpha(if (tab.pinned) 1f else 0.35f)
                .testTag("browser:tabcard:pin")
                .semantics { contentDescription = if (tab.pinned) "Unpin tab" else "Pin tab" }
                .clickable { onPin(tab) }
                .padding(start = 2.dp, top = 2.dp, end = 8.dp, bottom = 2.dp))
            val fav = icon
            if (fav != null) {
                Image(remember(fav) { fav.asImageBitmap() }, null, Modifier.size(16.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.size(6.dp))
            } else {
                Spacer(Modifier.size(22.dp))
            }
            Text(tab.displayTitle, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("⋮", Modifier.testTag("browser:tabcard:menu").clickable { onMenu(tab) }
                .padding(start = 12.dp, end = 10.dp),
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The snapshot fills the card (crop) and keeps its TOP edge. */
val TAB_SNAPSHOT_SCALE = ContentScale.Crop
val TAB_SNAPSHOT_ALIGN = Alignment.TopCenter

/** Content descriptions of the card's picture, so a test (and TalkBack) can tell a snapshot from the plate. */
object TabCardA11y {
    const val SNAPSHOT = "Page snapshot"
    const val PLACEHOLDER = "No snapshot yet"
}
