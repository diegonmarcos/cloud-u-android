package com.diegonmarcos.capitalfinterm.ui.fin

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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinLogic

/**
 * Quote header (spec 2.5, tag fin:quote): 112dp on surface. Symbol + name + watch star; last in display type (its ink flashes)
 * with the signed change and change %; source, time and the stale dot. Loading: three skeleton lines. Missing: "No quote" + Retry.
 */
@Composable
fun FinQuoteHeader(q: FinQuote?, source: String, stale: Boolean, watched: Boolean, onWatch: () -> Unit, onRetry: () -> Unit) {
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().height(112.dp).background(p.surface).padding(12.dp).testTag("fin:quote"), verticalArrangement = Arrangement.SpaceBetween) {
        if (q == null) {
            FinSkeleton(88.dp, 16.dp); FinSkeleton(160.dp, 28.dp); FinSkeleton(200.dp, 12.dp)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q.symbol, color = p.text, style = FinType.title)
            Spacer(Modifier.width(8.dp))
            Text(q.name, Modifier.weight(1f), color = p.text2, style = FinType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (watched) Icons.Filled.Star else Icons.Outlined.StarOutline, "watch", Modifier.size(24.dp).clickable(onClick = onWatch).testTag("fin:watchstar"),
                tint = if (watched) p.accent else p.text3)
        }
        if (FinLogic.missing(q.last)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("No quote (source did not answer)", Modifier.weight(1f), color = p.text2, style = FinType.body)
                FinChip("Retry", false, onClick = onRetry)
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(q.last, color = finFlashInk(q.last, q.dir, p.text), style = FinType.display)
                Spacer(Modifier.weight(1f))
                FinChange(FinLogic.change(q.chg), style = FinType.num.copy(fontSize = FinType.title.fontSize))
                Spacer(Modifier.width(8.dp))
                FinChange(if (FinLogic.missing(q.pct)) FinLogic.cell(q.pct) else FinLogic.change(q.pct) + "%", style = FinType.num.copy(fontSize = FinType.title.fontSize))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(source, q.date.replace('T', ' ').take(16)).filter { it.isNotBlank() }.joinToString(" · "), color = p.text3, style = FinType.caption, maxLines = 1)
            if (stale) { Spacer(Modifier.width(6.dp)); FinDot(p.amber); Text(" stale", color = p.amber, style = FinType.caption) }
        }
    }
}
