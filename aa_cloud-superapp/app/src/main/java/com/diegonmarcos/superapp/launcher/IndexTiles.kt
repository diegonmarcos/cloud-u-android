package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The card grid an INDEX row is drawn with ([IndexTileGrid]).
 *
 *  Shared so the identical look is reachable from a page that is NOT a stack — Configs ▸ About is a hand-built page with no
 *  build.json panels at all, and its index has to be the same object as C3 ▸ Topology's or the two drift apart.
 *
 *  Icons come either as a drawable res (build.json tiles name one) or as a short glyph (Configs ▸ About's macro headers are
 *  already prefixed with one). Neither is invented here: a tile with no icon of either kind draws its label alone rather
 *  than borrowing a picture that means something else.
 */
object IndexTiles {

    /** Standard index-tile height, also the height of the padding cells that keep a short last row tile-sized instead of
     *  stretched. */
    internal const val TILE_HEIGHT_DP = 96

    /** A label is two lines at most, END-ellipsized: a long unbroken word clips instead of pushing into its neighbour. */
    internal const val LABEL_MAX_LINES = 2

    data class Cell(
        val label: String,
        /** Drawable resource, or 0 when there is none. */
        val iconRes: Int = 0,
        /** Glyph shown when [iconRes] is 0 — an emoji is a perfectly good icon and every About macro header already carries one. */
        val glyph: String = "",
        val onClick: () -> Unit,
    )

    /** How many cards each row holds for [count] cells at [cols] per row: full rows, then the remainder. The last row is
     *  padded with empty cells when drawn, so a short row stays tile-sized. */
    internal fun rowSizes(count: Int, cols: Int): List<Int> {
        val c = cols.coerceAtLeast(1)
        return List((count + c - 1) / c) { i -> minOf(c, count - i * c) }
    }
}

/** The same card grid in Compose: [cells] laid out `cols` per row, wrapping, the last row padded so a short row stays
 *  tile-sized instead of stretching. Configs ▸ About and C3 ▸ Topology draw their index with the one object. */
@Composable
fun IndexTileGrid(cols: Int, cells: List<IndexTiles.Cell>, modifier: Modifier = Modifier) {
    val columns = cols.coerceAtLeast(1)
    Column(modifier) {
        for ((r, row) in cells.chunked(columns).withIndex()) {
            Row(Modifier.fillMaxWidth()) {
                for (cell in row) IndexTileCard(cell, columns, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f).height(IndexTiles.TILE_HEIGHT_DP.dp)) }
            }
        }
    }
}

@Composable
private fun IndexTileCard(cell: IndexTiles.Cell, cols: Int, modifier: Modifier) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.density
    val padDp = GridColumns.cellPadDp(cols)
    // Page padding 16 dp a side, card margin 4 dp a side, then the inner pad.
    val cellPx = GridColumns.cellPx(ctx.resources.displayMetrics.widthPixels - (32 * density).toInt(), cols)
    val iconDp = GridColumns.iconPx((28 * density).toInt(), cellPx, ((8 + 2 * padDp) * density).toInt(), (16 * density).toInt()) / density
    Column(
        modifier.padding(4.dp).height(IndexTiles.TILE_HEIGHT_DP.dp).clip(RoundedCornerShape(12.dp))
            .background(p.surface).clickable { cell.onClick() }.padding(padDp.dp).testTag("index:" + cell.label),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        when {
            cell.iconRes != 0 -> Icon(painterResource(cell.iconRes), null, tint = p.textPrimary, modifier = Modifier.size(iconDp.dp))
            cell.glyph.isNotBlank() -> Text(cell.glyph, fontSize = 20.sp, textAlign = TextAlign.Center)
        }
        Text(cell.label, color = p.textPrimary, fontSize = (GridColumns.labelSp(cols) + 1f).sp, maxLines = IndexTiles.LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
