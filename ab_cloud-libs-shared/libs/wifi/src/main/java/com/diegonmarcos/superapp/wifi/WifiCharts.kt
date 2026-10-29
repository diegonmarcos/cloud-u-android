package com.diegonmarcos.superapp.wifi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.pow

/** A stable colour per BSSID, so an AP keeps its colour across the list, the spectrum and the time graph (#1263). */
fun apColor(bssid: String): Color {
    val h = (bssid.hashCode().toLong() and 0xffffffffL) % 360
    return Color.hsv(h.toFloat(), 0.62f, 0.95f)
}

private const val DBM_TOP = -20f
private const val DBM_BOTTOM = -100f

private fun DrawScope.yOf(dbm: Float, top: Float, h: Float): Float = top + (DBM_TOP - dbm.coerceIn(DBM_BOTTOM, DBM_TOP)) / (DBM_TOP - DBM_BOTTOM) * h

private fun DrawScope.label(tm: TextMeasurer, s: String, x: Float, y: Float, c: Color, size: Int = 9) {
    val r = tm.measure(s, TextStyle(fontSize = size.sp, color = c))
    drawText(r, topLeft = Offset((x - r.size.width / 2f).coerceIn(0f, (this.size.width - r.size.width).coerceAtLeast(0f)), y))
}

private fun DrawScope.dbmGrid(tm: TextMeasurer, left: Float, top: Float, w: Float, h: Float, grid: Color, text: Color) {
    for (d in -30 downTo -90 step 10) {
        val y = yOf(d.toFloat(), top, h)
        drawLine(grid, Offset(left, y), Offset(left + w, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val r = tm.measure("$d", TextStyle(fontSize = 8.sp, color = text))
        drawText(r, topLeft = Offset(0f, y - r.size.height / 2f))
    }
}

/**
 * The channel graph of one band: each AP a bell over the MHz it occupies (flat top, Gaussian skirts), peak at its smoothed RSSI,
 * labelled with its SSID; the channel numbers of the plan on the x axis, DFS channels marked. [highlight] is drawn thicker.
 */
@Composable
fun SpectrumChart(aps: List<AccessPoint>, band: Band, plan: ChannelPlan, highlight: String?, height: Dp, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = ChannelMath.axis(band)
    val channels = plan.channels(band).ifEmpty { (if (band == Band.B24) (1..13) else emptyList<Int>()).map { PlanChannel(it, false, "") } }
    Canvas(modifier.fillMaxWidth().height(height).testTag("wifi:spectrum")) {
        val left = 26f; val top = 6f; val bottomPad = 30f
        val w = size.width - left - 4f; val h = size.height - top - bottomPad
        fun x(f: Float) = left + (f - axis.first) / (axis.last - axis.first).toFloat() * w
        dbmGrid(tm, left, top, w, h, grid, text)
        // channel ticks: every channel of 2.4, and every 4th 20 MHz channel (36, 52, ...) elsewhere so labels never collide
        channels.forEachIndexed { i, pc ->
            val f = ChannelMath.freq(band, pc.ch); if (f <= 0) return@forEachIndexed
            val xx = x(f.toFloat())
            drawLine(grid, Offset(xx, top), Offset(xx, top + h), 1f)
            val every = if (band == Band.B24) 1 else if (band == Band.B5) 2 else 6
            if (i % every == 0) label(tm, pc.ch.toString(), xx, top + h + 2f, if (pc.dfs) Color(0xFFE2B85C) else text)
        }
        val base = yOf(DBM_BOTTOM, top, h)
        for (ap in aps.filter { it.band == band }.sortedBy { it.smoothed }) {
            val col = apColor(ap.bssid)
            val peak = yOf(ap.smoothed.toFloat(), top, h)
            for (s in ap.spans) {
                val x0 = x(s.first.toFloat()); val x1 = x(s.last.toFloat())
                val skirt = (x1 - x0) * 0.12f
                val p = Path().apply {
                    moveTo(x0 - skirt, base)
                    val steps = 24
                    for (k in 0..steps) {
                        val t = k / steps.toFloat()
                        val xx = x0 - skirt + t * (x1 - x0 + 2 * skirt)
                        val edge = when { xx < x0 + skirt -> (x0 + skirt - xx) / (2 * skirt); xx > x1 - skirt -> (xx - (x1 - skirt)) / (2 * skirt); else -> 0f }
                        val g = exp(-(edge.coerceIn(0f, 1f) * 2.2f).pow(2))
                        lineTo(xx, base - (base - peak) * g)
                    }
                    lineTo(x1 + skirt, base); close()
                }
                drawPath(p, col.copy(alpha = 0.16f))
                drawPath(p, col, style = Stroke(if (ap.bssid == highlight) 4f else 1.8f))
            }
            label(tm, ap.displaySsid.take(14), x(ap.centerMhz.toFloat()), (peak - 13f).coerceAtLeast(0f), col, 8)
        }
    }
}

/** RSSI over time of several series (each AP's history), last [windowMs]; the newest sample at the right edge. */
@Composable
fun TimeChart(series: List<Pair<String, List<RssiSample>>>, windowMs: Long, height: Dp, modifier: Modifier = Modifier, now: Long = System.currentTimeMillis()) {
    val tm = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(height).testTag("wifi:timegraph")) {
        val left = 26f; val top = 6f
        val w = size.width - left - 4f; val h = size.height - top - 16f
        dbmGrid(tm, left, top, w, h, grid, text)
        label(tm, "-${windowMs / 1000}s", left + 12f, top + h + 2f, text, 8)
        label(tm, "now", left + w - 10f, top + h + 2f, text, 8)
        for ((id, pts) in series) {
            val visible = pts.filter { now - it.t <= windowMs }
            if (visible.isEmpty()) continue
            val col = apColor(id)
            val p = Path()
            visible.forEachIndexed { i, s ->
                val xx = left + w - (now - s.t) / windowMs.toFloat() * w
                val yy = yOf(s.rssi.toFloat(), top, h)
                if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
                drawCircle(col, 2.2f, Offset(xx, yy))
            }
            drawPath(p, col, style = Stroke(2f))
        }
    }
}

/** A small RSSI sparkline for an AP row. */
@Composable
fun Sparkline(points: List<Int>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(64.dp, 20.dp)) {
        if (points.size < 2) return@Canvas
        val lo = -100f; val hi = -20f
        val p = Path()
        points.forEachIndexed { i, v ->
            val xx = i / (points.size - 1f) * size.width
            val yy = size.height - (v.coerceIn(-100, -20) - lo) / (hi - lo) * size.height
            if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
        }
        drawPath(p, color, style = Stroke(1.6f))
    }
}

/** Latency / throughput samples as a line with its own scale. */
@Composable
fun ValueChart(values: List<Double>, color: Color, unit: String, height: Dp, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.15
        val p = Path()
        values.forEachIndexed { i, v ->
            val xx = if (values.size == 1) 0f else i / (values.size - 1f) * size.width
            val yy = size.height - (v / max).toFloat() * (size.height - 12f)
            if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
        }
        drawPath(p, color, style = Stroke(2f))
        label(tm, "%.0f %s".format(java.util.Locale.ROOT, max / 1.15, unit), 30f, 0f, text, 8)
    }
}

/** A 0..10 rating bar. */
@Composable
fun RatingBar(rating: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val c = when { rating >= 7.5 -> WifiOk; rating >= 4.5 -> WifiWarn; else -> WifiBad }
    Canvas(modifier.height(8.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(4f, 4f))
        drawRoundRect(c, size = Size(size.width * (rating / 10.0).toFloat().coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(4f, 4f))
    }
}

/** The heat colour of a mean RSSI: red (weak) to green (strong). */
fun heatColor(dbm: Double): Color {
    if (dbm.isNaN()) return Color.Transparent
    val f = ((dbm + 90) / 50).coerceIn(0.0, 1.0).toFloat()
    return Color.hsv(f * 120f, 0.75f, 0.85f)
}
