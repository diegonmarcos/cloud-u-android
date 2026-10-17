package com.diegonmarcos.superapp.launcher

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.extinstall.extInstallRing
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The launcher tile (was R.layout.item_tile + the three binders in TileGridFragment,
 * HomeGroupedFragment and GroupedTilesFragment): a glass card, a glyph fitted to its column and a
 * label under it. One composable for every Cloud grid, so the icon arithmetic (the same
 * [GridColumns] helpers the 360 dp test asserts), the press feel, the haptic and the install ring
 * cannot drift between them.
 *
 * [cols] / [pageDp] / [chromeDp] are the grid's column count, its page padding and the tile's own
 * margin+padding (what [GridColumns.iconPx] subtracts from the cell). [height] is the CELL's height, margin
 * included: a flat grid's card is item_tile.xml's 128 dp inside its 3 dp margin (134), HomeGrouped's rows are 76
 * (a 70 dp card). TileSizeTest pins both.
 */
@Composable
internal fun LauncherTile(
    tileId: String,
    label: String,
    @DrawableRes iconRes: Int,
    cols: Int,
    pageDp: Int,
    chromeDp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = TILE_CELL_HEIGHT,
) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val view = LocalView.current
    // The density Compose itself lays out with (LocalDensity), not the Activity resources': an in-process scale
    // (SystemDisplay.wrap) or a hosting window with another Configuration must not make the glyph disagree with its tile.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val widthPx = ctx.resources.displayMetrics.widthPixels
    val iconDp = remember(cols, pageDp, chromeDp, density, widthPx) { tileIconDp(widthPx, density, cols, pageDp, chromeDp) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // The old tile_press animation: a quick shrink while the finger is down.
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "tile-press")
    Column(
        modifier.height(height).padding(3.dp)
            .onSizeChanged { TileMetrics.tile(tileId, it.width, it.height, density) } // the CARD: inside the 3 dp margin
            .scale(scale)
            .clip(RoundedCornerShape(16.dp)).background(p.surface)
            .clickable(interactionSource = src, indication = LocalIndication.current) {
                Haptics.tap(view)
                onClick()
            }
            .extInstallRing(tileId, iconDp, iconTop = 8.dp)
            .padding(4.dp).testTag(tileTag(tileId)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = p.textPrimary,
            modifier = Modifier.padding(top = 4.dp).size(iconDp).testTag(tileIconTag(tileId))
                .onSizeChanged { TileMetrics.icon(tileId, it.width, it.height, density) })
        Text(label, color = p.textPrimary, fontSize = GridColumns.labelSp(cols).sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

/** A flat grid cell: the old item_tile.xml card (128 dp) plus its 3 dp margin a side. */
internal val TILE_CELL_HEIGHT: Dp = 134.dp

/** The icon edge in dp: [GridColumns.iconPx] over the screen minus [pageDp], split [cols] ways, minus [chromeDp]. */
internal fun tileIconDp(widthPx: Int, density: Float, cols: Int, pageDp: Int, chromeDp: Int): Dp {
    val cell = GridColumns.cellPx(widthPx - (pageDp * density).toInt(), cols)
    val px = GridColumns.iconPx((32 * density).toInt(), cell, (chromeDp * density).toInt(), (16 * density).toInt())
    // Hard ceiling: the glyph is never larger than the 32 dp the View tile drew, whatever the arithmetic above says.
    return (px / density).coerceAtMost(TILE_ICON_MAX_DP).dp
}

/** item_tile.xml's icon edge: the most a launcher tile glyph may be. */
internal const val TILE_ICON_MAX_DP = 32f

/**
 * What the tiles REALLY measured on this device (px from layout, divided by the density they were laid out at), kept for
 * Configs ▸ Observability ▸ Diagnostics and its remote read. Written from the composition, read from anywhere.
 */
object TileMetrics {
    data class Sample(val id: String, val tileWDp: Float, val tileHDp: Float, val iconWDp: Float, val iconHDp: Float,
                      val density: Float, val atMs: Long)

    private val tiles = java.util.concurrent.ConcurrentHashMap<String, FloatArray>() // w,h,icon w,icon h,density
    @Volatile private var last: String? = null

    internal fun tile(id: String, w: Int, h: Int, density: Float) {
        val a = tiles.getOrPut(id) { FloatArray(5) }; a[0] = w / density; a[1] = h / density; a[4] = density; last = id
    }

    internal fun icon(id: String, w: Int, h: Int, density: Float) {
        val a = tiles.getOrPut(id) { FloatArray(5) }; a[2] = w / density; a[3] = h / density; a[4] = density; last = id
    }

    /** The most recently measured tile, or null before any grid has been drawn. */
    fun latest(): Sample? = last?.let { id -> tiles[id]?.let { Sample(id, it[0], it[1], it[2], it[3], it[4], 0L) } }

    /** The largest glyph any tile measured: the number that answers "are the icons huge". */
    fun largestIconDp(): Float = tiles.values.maxOfOrNull { maxOf(it[2], it[3]) } ?: 0f

    fun count(): Int = tiles.size

    fun clear() { tiles.clear(); last = null }
}

/**
 * A `separator` page as a CHARACTER: the same faint caption the Data Apps row draws (alpha 0x66 of the ink,
 * caption size), as wide as its own text and no wider (no weight, no column slot), never a control and
 * hidden from accessibility — TalkBack walks from the tile before it to the tile after it.
 */
@Composable
internal fun TileSeparator(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textPrimary.copy(alpha = 0.4f), fontSize = 12.sp,
        textAlign = TextAlign.Center, modifier = modifier.padding(6.dp).clearAndSetSemantics { })
}

/** Group heading between tile rows: the declared words, upper-cased. */
@Composable
internal fun TileGroupHeader(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Text(text.uppercase(), color = LocalKitPalette.current.textSecondary, fontSize = 11.sp,
        letterSpacing = 0.12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(start = 14.dp, top = if (compact) 6.dp else 14.dp, end = 14.dp,
            bottom = if (compact) 1.dp else 6.dp))
}

/** The heading one level BELOW [TileGroupHeader]: indented and a shade quieter. */
@Composable
internal fun TileSubgroupHeader(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Text(text.uppercase(), color = LocalKitPalette.current.textSecondary, fontSize = 10.sp,
        letterSpacing = 0.10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(start = 26.dp, top = if (compact) 1.dp else 6.dp, end = 14.dp,
            bottom = if (compact) 0.dp else 4.dp))
}

internal fun tileTag(id: String) = "tile:$id"
internal fun tileIconTag(id: String) = "tileicon:$id"

/**
 * The fitted (Configs) tile: a 26 dp glyph with a small label under it, hugged by a faint plate - no 128 dp card.
 * It takes the height its row was given; when that is short (big font, display zoom) the glyph drops to 22 dp and
 * the label to one line at 9 sp, so the grid shrinks its labels before it would ever scroll.
 */
@Composable
internal fun CompactTile(tileId: String, label: String, @DrawableRes iconRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "tile-press")
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.padding(2.dp)) {
        val roomy = maxHeight >= 60.dp
        val iconDp = if (maxHeight >= 52.dp) 26.dp else 22.dp
        Column(
            Modifier.fillMaxSize()
                .onSizeChanged { TileMetrics.tile(tileId, it.width, it.height, density) }
                .scale(scale)
                .clip(RoundedCornerShape(10.dp)).background(p.surface.copy(alpha = 0.45f))
                .clickable(interactionSource = src, indication = LocalIndication.current) { Haptics.tap(view); onClick() }
                .extInstallRing(tileId, iconDp, iconTop = 4.dp)
                .padding(horizontal = 2.dp, vertical = 2.dp).testTag(tileTag(tileId)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(iconRes), contentDescription = null, tint = p.textPrimary,
                modifier = Modifier.size(iconDp).testTag(tileIconTag(tileId))
                    .onSizeChanged { TileMetrics.icon(tileId, it.width, it.height, density) })
            Text(label, color = p.textPrimary, fontSize = if (roomy) 10.sp else 9.sp, lineHeight = if (roomy) 11.sp else 10.sp,
                maxLines = if (roomy) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp).testTag("tilelabel:$tileId"))
        }
    }
}
