package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinLogic

/**
 * Heatmap tile (spec 2.7, tag fin:heat:<symbol>): 64dp min, 6dp radius, fill interpolated down -> surfaceRaised -> up by change %
 * (clamped at +-scale %), 85 % alpha; text ink, blend capped at FinLogic.HEAT_MAX_BLEND. Stale: desaturated to 50 %.
 */
@Composable
fun FinHeatTile(q: FinQuote, scale: Double, stale: Boolean, showLast: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalFinPalette.current
    val h = FinLogic.heat(q.pctValue, scale)
    val target = if (h >= 0) p.up else p.down
    var fill = lerp(p.surfaceRaised, target, FinLogic.HEAT_MAX_BLEND * kotlin.math.abs(h)).copy(alpha = 0.85f)
    if (stale) fill = lerp(fill, p.surfaceRaised, 0.5f)
    val ink = p.text
    Column(
        modifier.height(64.dp).clip(RoundedCornerShape(6.dp)).background(fill).clickable(onClick = onClick).padding(8.dp).testTag("fin:heat:${q.symbol}"),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(q.symbol, color = ink, style = FinType.symbol, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (showLast) Text(FinLogic.cell(q.last), Modifier.weight(1f), color = ink, style = FinType.numSmall, maxLines = 1) else Box(Modifier.weight(1f))
            Text(if (FinLogic.missing(q.pct)) FinLogic.cell(q.pct) else FinLogic.change(q.pct) + "%", color = ink, style = FinType.num, maxLines = 1)
        }
    }
}

/** An adaptive grid of heat tiles (96dp min width, 4dp gap), drawn without a lazy grid so it can sit inside a card. */
@Composable
fun FinHeatGrid(items: List<FinQuote>, width: Dp, scale: Double, stale: Boolean, onOpen: (FinQuote) -> Unit) {
    val cols = ((width.value + 4f) / (96f + 4f)).toInt().coerceIn(2, 8)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { q -> FinHeatTile(q, scale, stale, showLast = width / cols >= 96.dp, modifier = Modifier.weight(1f)) { onOpen(q) } }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
