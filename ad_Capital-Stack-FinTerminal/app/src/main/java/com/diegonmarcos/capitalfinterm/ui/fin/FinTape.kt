package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinLogic
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Ticker tape (spec 2.1, tag fin:tape): 28dp, surfaceRaised; symbol, last, signed chg % per item, 16dp apart with a hairline.
 * Scrolls at 40dp/s and loops (the items drawn twice); a touch stops it (the user drags it); reduce-motion = a swipeable row.
 * Loading: 5 skeleton pairs. Error or empty: hidden (it never shows wrong prices).
 */
@Composable
fun FinTape(items: List<FinQuote>?, stale: Boolean, onOpen: (FinQuote) -> Unit) {
    val p = LocalFinPalette.current
    if (items != null && items.isEmpty()) return
    val still = LocalReduceMotion.current
    val scroll = rememberScrollState()
    val pxPerFrame = with(LocalDensity.current) { (40.dp.toPx() / 60f) }
    if (items != null && !still) {
        LaunchedEffect(items.size) {
            while (isActive) {
                delay(16)
                if (scroll.isScrollInProgress) continue
                val half = scroll.maxValue / 2
                if (half <= 0) continue
                val next = scroll.value + pxPerFrame
                scroll.scrollTo(if (next >= half) (next - half).toInt() else next.toInt().coerceAtLeast(scroll.value + 1))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().height(FinDim.tape).background(p.surfaceRaised).clipToBounds().testTag("fin:tape"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stale) { Spacer(Modifier.width(6.dp)); FinDot(p.amber) }
        Row(Modifier.horizontalScroll(scroll).alpha(if (stale) 0.7f else 1f), verticalAlignment = Alignment.CenterVertically) {
            if (items == null) {
                repeat(5) { Spacer(Modifier.width(12.dp)); FinSkeleton(48.dp, 12.dp); Spacer(Modifier.width(4.dp)); FinSkeleton(48.dp, 12.dp) }
            } else {
                val loop = if (still) items else items + items
                loop.forEachIndexed { i, q ->
                    Row(Modifier.clickable { onOpen(q) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(q.symbol.uppercase(), color = p.text2, style = FinType.label)
                        Spacer(Modifier.width(4.dp))
                        Text(FinLogic.cell(q.last), color = finFlashInk(q.last, q.dir, p.text), style = FinType.numSmall)
                        Spacer(Modifier.width(4.dp))
                        val pct = if (FinLogic.missing(q.pct)) FinLogic.change(q.chg) else FinLogic.change(q.pct) + "%"
                        FinChange(pct, style = FinType.numSmall)
                    }
                    if (i < loop.size - 1) Box(Modifier.width(1.dp).height(12.dp).background(p.hairline))
                }
            }
        }
    }
}
