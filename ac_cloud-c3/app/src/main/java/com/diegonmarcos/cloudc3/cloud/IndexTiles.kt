package com.diegonmarcos.cloudc3.cloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The card grid an INDEX row is drawn with — MOVED from the SuperApp's launcher package
 *  with the stack pages (#648).
 *
 *  Extracted (there) out of AggregatorStackFragment.renderTileRow so the identical
 *  look is reachable from a page that is NOT a stack. Icons come either as a drawable
 *  res or as a short glyph; neither is invented here: a tile with no icon of either
 *  kind draws its label alone rather than borrowing a picture that means something
 *  else.
 *
 *  2026-10-16 owner decision: all apps must be Compose — [Grid] is a composable.
 */
object IndexTiles {

    /** Standard index-tile height, also the height of the padding cells that
     *  keep a short last row tile-sized instead of stretched. */
    private const val TILE_HEIGHT_DP = 96

    data class Cell(
        val label: String,
        /** Drawable resource, or 0 when there is none. */
        val iconRes: Int = 0,
        /** Glyph shown when [iconRes] is 0 — an emoji is a perfectly good icon. */
        val glyph: String = "",
        val onClick: () -> Unit,
    )

    /** [cells] laid out `cols` per row, wrapping, last row padded. A single
     *  row that already fits within `cols` comes out exactly as the old flat
     *  horizontal row did. */
    @Composable
    fun Grid(cols: Int, cells: List<Cell>, modifier: Modifier = Modifier) {
        val columns = cols.coerceAtLeast(1)
        Column(modifier.fillMaxWidth()) {
            for (row in cells.chunked(columns)) {
                Row(Modifier.fillMaxWidth()) {
                    for (cell in row) Box(Modifier.weight(1f)) { TileCard(cell) }
                    repeat(columns - row.size) { Box(Modifier.weight(1f).height(TILE_HEIGHT_DP.dp)) }
                }
            }
        }
    }

    @Composable
    private fun TileCard(cell: Cell) {
        val p = LocalKitPalette.current
        Column(
            Modifier.fillMaxWidth().height(TILE_HEIGHT_DP.dp).padding(4.dp)
                .clip(RoundedCornerShape(12.dp)).background(p.surfaceSelected)
                .testTag("c3:tile:${cell.label}")
                .clickable { cell.onClick() }.padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                cell.iconRes != 0 -> Image(painterResource(cell.iconRes), contentDescription = null, Modifier.size(28.dp))
                cell.glyph.isNotBlank() -> Text(cell.glyph, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
            Text(cell.label, color = p.textPrimary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
        }
    }
}
