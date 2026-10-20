package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Cloud ▸ WebApps (#1062): only the web apps, auto-scaled to FILL one screen with no scrolling. The column count is the one
 * (4..8) whose square cell is biggest for the pane at hand ([layout]); rows then share the height equally, so the glyph, plate
 * and label grow with the screen instead of leaving the lower half empty. A tile's id is its https URL, which the shell opens
 * in the embedded browser. No title, no tab strip (the page declares `hide_tabs`), no APK row.
 */
class WebAppsGridFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val args = requireArguments()
        val ids = args.getStringArray(ARG_IDS) ?: emptyArray()
        val labels = args.getStringArray(ARG_LABELS) ?: emptyArray()
        val icons = args.getIntArray(ARG_ICONS) ?: IntArray(0)
        val p = LocalKitPalette.current
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val (cols, rows) = layout(maxWidth.value, maxHeight.value, ids.size)
            val cellW = maxWidth / cols
            val cellH = maxHeight / rows
            val plate: Dp = min(cellW.value * 0.72f, cellH.value * 0.62f).coerceIn(24f, 84f).dp
            val labelDp = (cellH.value * 0.13f).coerceIn(9f, 13f)
            Column(Modifier.fillMaxSize()) {
                for (r in 0 until rows) Row(Modifier.fillMaxWidth().height(cellH)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        if (i >= ids.size) { Box(Modifier.size(cellW, cellH)); continue }
                        Column(Modifier.size(cellW, cellH).clickable {
                            (activity as? TileGridFragment.TileClickListener)?.onTileClicked(ids[i])
                        }.testTag(tileTag(ids[i])), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                            Box(Modifier.size(plate).clip(RoundedCornerShape(plate / 3.4f))
                                .background(p.textPrimary.copy(alpha = 0.08f)).testTag("tileplate:${ids[i]}"),
                                contentAlignment = Alignment.Center) {
                                Icon(painterResource(icons.getOrNull(i)?.takeIf { it != 0 } ?: R.drawable.ic_world),
                                    contentDescription = null, tint = p.textPrimary,
                                    modifier = Modifier.size(plate * 0.62f).testTag(tileIconTag(ids[i])))
                            }
                            // Fixed dp regardless of the system font scale: the grid fits the screen, the text follows the grid.
                            Text(labels[i], color = p.textPrimary, fontSize = (labelDp / fontScale).sp,
                                lineHeight = (labelDp * 1.15f / fontScale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 1.dp, end = 1.dp)
                                    .testTag("tilelabel:${ids[i]}"))
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_IDS = "ids"
        private const val ARG_LABELS = "labels"
        private const val ARG_ICONS = "icons"

        /** Columns and rows for [n] tiles in a [wDp] x [hDp] pane: the column count (4..8) giving the biggest square cell. */
        internal fun layout(wDp: Float, hDp: Float, n: Int): Pair<Int, Int> {
            var best = 4 to ceil(n / 4f).toInt(); var bestCell = -1f
            for (c in 4..8) {
                val r = max(1, ceil(n / c.toFloat()).toInt())
                val cell = min(wDp / c, hDp / r)
                if (cell > bestCell + 0.01f) { bestCell = cell; best = c to r }
            }
            return best
        }

        fun newInstance(tiles: List<TileGridFragment.Tile>) = WebAppsGridFragment().apply {
            arguments = bundleOf(
                ARG_IDS to tiles.map { it.id }.toTypedArray(),
                ARG_LABELS to tiles.map { it.label }.toTypedArray(),
                ARG_ICONS to tiles.map { it.iconRes }.toIntArray(),
            )
        }
    }
}
