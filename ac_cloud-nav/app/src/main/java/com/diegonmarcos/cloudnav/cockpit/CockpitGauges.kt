package com.diegonmarcos.cloudnav.cockpit

import android.graphics.Canvas as NativeCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudnav.CockpitMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Professional-grade cockpit instruments, drawn on a Compose Canvas (no XML, no assets) so they
 * scale to any panel slot. Each reads the shared [CockpitFeed] the
 * [com.diegonmarcos.cloudnav.routes.NavigationFragment] fills from GPS fixes + [CockpitSensors];
 * a change in the feed redraws only the gauges that read it.
 *
 * Registry: [CockpitGauge] maps a build.json gauge id to the right instrument (FIRE rule 6 — the
 * id list is data, this is just the render registry).
 */
class CockpitMetric(val line1: String, val line2: String? = null)

/** The live values the instruments show. Setters the Navigation page calls; a value nobody feeds stays at its rest value. */
class CockpitFeed {
    var speed by mutableDoubleStateOf(0.0); private set
    var speedUnit by mutableStateOf("km/h"); private set
    var heading by mutableFloatStateOf(0f); private set
    var pitch by mutableFloatStateOf(0f); private set
    var roll by mutableFloatStateOf(0f); private set
    var altitude by mutableDoubleStateOf(0.0); private set
    var verticalMps by mutableDoubleStateOf(0.0); private set
    val metrics = mutableStateMapOf<String, CockpitMetric>()

    fun onSpeed(v: Double, unit: String) { speed = max(0.0, v); speedUnit = unit }
    fun onHeading(deg: Float) { heading = ((deg % 360f) + 360f) % 360f }
    fun onAttitude(p: Float, r: Float) { pitch = p.coerceIn(-90f, 90f); roll = r }
    fun onAltitude(v: Double) { altitude = v }
    fun onVerticalMps(v: Double) { verticalMps = v }
    fun onMetric(id: String, value: String, second: String? = null) { metrics[id] = CockpitMetric(value, second) }

    /** Back to rest: a mode switch starts every instrument from nothing, as a fresh panel did. */
    fun reset() {
        speed = 0.0; speedUnit = "km/h"; heading = 0f; pitch = 0f; roll = 0f; altitude = 0.0; verticalMps = 0.0
        metrics.clear()
    }
}

object CockpitGauges {

    const val BG = 0xFF10141C.toInt()
    const val SUB = 0xFFA9B0BD.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()

    /** Full-scale of the speed dial, per unit/vehicle (derived — not a magic literal). */
    fun speedMax(mode: CockpitMode): Int = when (mode.speedUnit) {
        "kn"  -> if (mode.id == "airplane") 500 else 60   // boat vs aircraft groundspeed
        "mph" -> 120
        else  -> 180                                       // km/h
    }

    fun speedUnitLabel(u: String) = when (u) { "kn" -> "kn"; "mph" -> "mph"; else -> "km/h" }
    fun kmhTo(u: String, kmh: Double) = when (u) { "kn" -> kmh / 1.852; "mph" -> kmh / 1.609344; else -> kmh }
    fun metersTo(u: String, m: Double) = if (u == "ft") m * 3.28084 else m

    /** The label a text readout shows for a gauge id (an unknown id is labelled with itself, upper-cased). */
    fun metricLabel(id: String): String = when (id) {
        "grade" -> "GRADE"; "avg" -> "AVG"; "dist" -> "DIST"; "eta" -> "ETA"; "position" -> "POSITION"; "track" -> "TRACK"
        else -> id.uppercase()
    }
}

object CockpitTags {
    fun gauge(id: String) = "cockpit:gauge:$id"
}

/** The instrument for [id]; an id with no dial is a labelled text readout. */
@Composable
fun CockpitGauge(id: String, mode: CockpitMode, feed: CockpitFeed, modifier: Modifier = Modifier) {
    val m = modifier.testTag(CockpitTags.gauge(id))
    val accent = mode.accent
    when (id) {
        "speed"    -> GaugeCanvas(m) { g -> drawSpeed(g, feed.speed, feed.speedUnit, CockpitGauges.speedMax(mode), accent) }
        "heading"  -> GaugeCanvas(m) { g -> drawCompass(g, feed.heading, accent) }
        "attitude" -> GaugeCanvas(m) { g -> drawAttitude(g, feed.pitch, feed.roll, accent) }
        "altitude" -> GaugeCanvas(m) { g -> drawAltitude(g, feed.altitude, mode.altUnit, accent) }
        "vspeed"   -> GaugeCanvas(m) { g -> drawVSpeed(g, feed.verticalMps, mode.altUnit, accent) }
        else       -> GaugeCanvas(m) { g -> drawMetric(g, CockpitGauges.metricLabel(id), feed.metrics[id], accent) }
    }
}

// ── shared drawing context ──────────────────────────────────────────────────
private class Gx(val c: NativeCanvas, val w: Float, val h: Float, val d: Float) {
    fun dp(v: Float) = v * d
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CockpitGauges.BG }
    private val panelRect = RectF()
    fun drawPanel() {
        panelRect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
        c.drawRoundRect(panelRect, dp(16f), dp(16f), bgPaint)
    }
    fun text(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = dp(size); textAlign = align
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    fun paint(color: Int, stroke: Float? = null, cap: Paint.Cap? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (stroke != null) { style = Paint.Style.STROKE; strokeWidth = stroke }
        if (cap != null) strokeCap = cap
    }
}

/** A canvas the size of its slot; [draw] runs in the draw phase, so the state it reads redraws it. */
@Composable
private fun GaugeCanvas(modifier: Modifier, draw: (Gx) -> Unit) {
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas { c -> draw(Gx(c.nativeCanvas, size.width, size.height, density)) }
    }
}

// ── circular speed dial ──────────────────────────────────────────────────────
private fun drawSpeed(g: Gx, value: Double, unit: String, maxValue: Int, accent: Int) {
    g.drawPanel()
    val stroke = g.dp(9f)
    val track = g.paint(0xFF2A3140.toInt(), stroke, Paint.Cap.ROUND)
    val arc = g.paint(accent, stroke, Paint.Cap.ROUND)
    val pad = g.dp(16f) + stroke
    val cx = g.w / 2f; val cy = g.h / 2f
    val r = min(cx, cy) - pad
    val oval = RectF(cx - r, cy - r, cx + r, cy + r)
    val startAngle = 135f; val sweepMax = 270f
    g.c.drawArc(oval, startAngle, sweepMax, false, track)
    val frac = min(1.0, value / maxValue).toFloat()
    g.c.drawArc(oval, startAngle, sweepMax * frac, false, arc)
    g.c.drawText(value.roundToInt().toString(), cx, cy + g.dp(6f), g.text(30f, CockpitGauges.TEXT, bold = true))
    g.c.drawText(unit, cx, cy + g.dp(24f), g.text(11f, CockpitGauges.SUB))
}

// ── compass rose ─────────────────────────────────────────────────────────────
private fun drawCompass(g: Gx, heading: Float, accent: Int) {
    g.drawPanel()
    val ring = g.paint(0xFF2A3140.toInt(), g.dp(2f))
    val tick = g.paint(CockpitGauges.SUB).apply { strokeWidth = g.dp(2f) }
    val cardinal = g.text(13f, CockpitGauges.TEXT, bold = true)
    val cx = g.w / 2f; val cy = g.h / 2f
    val r = min(cx, cy) - g.dp(22f)
    g.c.drawCircle(cx, cy, r, ring)

    g.c.save()
    g.c.rotate(-heading, cx, cy)
    for (a in 0 until 360 step 30) {
        val rad = Math.toRadians(a.toDouble())
        val outer = r; val inner = r - g.dp(if (a % 90 == 0) 12f else 7f)
        val sx = cx + (sin(rad) * outer).toFloat(); val sy = cy - (cos(rad) * outer).toFloat()
        val ex = cx + (sin(rad) * inner).toFloat(); val ey = cy - (cos(rad) * inner).toFloat()
        g.c.drawLine(sx, sy, ex, ey, tick)
        val label = when (a) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> null }
        if (label != null) {
            val lx = cx + (sin(rad) * (r - g.dp(24f))).toFloat()
            val ly = cy - (cos(rad) * (r - g.dp(24f))).toFloat() + g.dp(5f)
            cardinal.color = if (label == "N") accent else CockpitGauges.TEXT
            g.c.drawText(label, lx, ly, cardinal)
        }
    }
    g.c.restore()

    // Fixed lubber line (top) + centre heading readout.
    val path = Path().apply {
        moveTo(cx, cy - r - g.dp(2f)); lineTo(cx - g.dp(7f), cy - r + g.dp(10f)); lineTo(cx + g.dp(7f), cy - r + g.dp(10f)); close()
    }
    g.c.drawPath(path, g.paint(accent))
    g.c.drawText("%03d°".format(heading.roundToInt() % 360), cx, cy + g.dp(4f), g.text(24f, CockpitGauges.TEXT, bold = true))
    g.c.drawText("HEADING", cx, cy + g.dp(20f), g.text(10f, CockpitGauges.SUB))
}

// ── artificial horizon (attitude indicator) ──────────────────────────────────
private fun drawAttitude(g: Gx, pitch: Float, roll: Float, accent: Int) {
    val sky = g.paint(0xFF3A7BD5.toInt())
    val ground = g.paint(0xFF8A5A2B.toInt())
    val horizon = g.paint(Color.WHITE).apply { strokeWidth = g.dp(2f) }
    val ladder = g.paint(0xCCFFFFFF.toInt()).apply { strokeWidth = g.dp(1.5f) }
    val ladderTxt = g.text(9f, Color.WHITE)
    val wing = g.paint(accent).apply { strokeWidth = g.dp(3f) }
    val cx = g.w / 2f; val cy = g.h / 2f
    val r = min(cx, cy) - g.dp(6f)
    val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
    g.c.save(); g.c.clipPath(clip)

    // pixels per degree of pitch
    val ppd = r / 45f
    g.c.save()
    g.c.rotate(-roll, cx, cy)
    val horizonY = cy + pitch * ppd
    g.c.drawRect(cx - r * 2, horizonY - r * 3, cx + r * 2, horizonY, sky)
    g.c.drawRect(cx - r * 2, horizonY, cx + r * 2, horizonY + r * 3, ground)
    g.c.drawLine(cx - r * 1.5f, horizonY, cx + r * 1.5f, horizonY, horizon)
    // pitch ladder every 10°
    for (p in -30..30 step 10) {
        if (p == 0) continue
        val y = cy + (pitch - p) * ppd
        val w = if (p % 20 == 0) g.dp(26f) else g.dp(15f)
        g.c.drawLine(cx - w, y, cx + w, y, ladder)
        g.c.drawText(abs(p).toString(), cx + w + g.dp(10f), y + g.dp(3f), ladderTxt)
    }
    g.c.restore()
    g.c.restore()

    // Fixed aircraft symbol (centre) + bank arc pointer.
    g.c.drawLine(cx - g.dp(24f), cy, cx - g.dp(8f), cy, wing)
    g.c.drawLine(cx + g.dp(8f), cy, cx + g.dp(24f), cy, wing)
    g.c.drawCircle(cx, cy, g.dp(2.5f), wing)
    val bank = Path().apply {
        moveTo(cx, cy - r); lineTo(cx - g.dp(6f), cy - r + g.dp(11f)); lineTo(cx + g.dp(6f), cy - r + g.dp(11f)); close()
    }
    g.c.save(); g.c.rotate(-roll, cx, cy); g.c.drawPath(bank, wing); g.c.restore()
}

// ── vertical altitude tape ───────────────────────────────────────────────────
private fun drawAltitude(g: Gx, value: Double, unit: String, accent: Int) {
    g.drawPanel()
    val line = g.paint(CockpitGauges.SUB).apply { strokeWidth = g.dp(1.5f) }
    val scaleTxt = g.text(10f, CockpitGauges.SUB, align = Paint.Align.RIGHT)
    val cx = g.w / 2f; val cy = g.h / 2f
    val step = if (unit == "ft") 100.0 else 20.0     // one labelled tick
    val ppUnit = g.dp(0.35f)                          // px per unit — compact tape
    val rightX = g.w - g.dp(10f)
    g.c.save(); g.c.clipRect(g.dp(4f), g.dp(6f), g.w - g.dp(4f), g.h - g.dp(6f))
    var mark = (value / step).roundToInt() * step - 4 * step
    var i = 0
    while (i <= 8) {
        val y = cy + ((value - mark) * ppUnit).toFloat()
        g.c.drawLine(rightX - g.dp(10f), y, rightX, y, line)
        g.c.drawText(mark.roundToInt().toString(), rightX - g.dp(14f), y + g.dp(4f), scaleTxt)
        mark += step; i++
    }
    g.c.restore()
    // Centre readout box.
    val box = RectF(g.dp(4f), cy - g.dp(15f), g.w - g.dp(4f), cy + g.dp(15f))
    g.c.drawRoundRect(box, g.dp(5f), g.dp(5f), g.paint(accent))
    g.c.drawText(value.roundToInt().toString(), cx, cy + g.dp(6f), g.text(16f, 0xFF10141C.toInt(), bold = true))
    g.c.drawText("ALT $unit", cx, g.dp(18f), g.text(10f, CockpitGauges.SUB))
}

// ── vertical speed indicator ─────────────────────────────────────────────────
private fun drawVSpeed(g: Gx, mps: Double, altUnit: String, accent: Int) {
    g.drawPanel()
    val scale = g.paint(0xFF2A3140.toInt(), g.dp(4f), Paint.Cap.ROUND)
    val bar = g.paint(accent, g.dp(10f), Paint.Cap.ROUND)
    val zero = g.paint(CockpitGauges.SUB).apply { strokeWidth = g.dp(1.5f) }
    val cx = g.w / 2f; val cy = g.h / 2f
    val half = min(g.h, g.w) / 2f - g.dp(30f)
    g.c.drawLine(cx, cy - half, cx, cy + half, scale)
    g.c.drawLine(cx - g.dp(18f), cy, cx + g.dp(18f), cy, zero)
    // Display in ft/min for aircraft, else m/s. Full scale ±maxDisp.
    val display: Double; val maxDisp: Double; val unit: String
    if (altUnit == "ft") { display = mps * 196.85; maxDisp = 2000.0; unit = "ft/min" }
    else { display = mps; maxDisp = 5.0; unit = "m/s" }
    val frac = (display / maxDisp).coerceIn(-1.0, 1.0)
    val endY = cy - (frac * half).toFloat()
    g.c.drawLine(cx, cy, cx, endY, bar)
    g.c.drawText(if (display >= 0) "+%.0f".format(display) else "%.0f".format(display), cx, g.h - g.dp(20f), g.text(15f, CockpitGauges.TEXT, bold = true))
    g.c.drawText(unit, cx, g.h - g.dp(6f), g.text(10f, CockpitGauges.SUB))
}

// ── text readout (avg / dist / eta / grade / position / track) ────────────────
private fun drawMetric(g: Gx, label: String, metric: CockpitMetric?, accent: Int) {
    g.drawPanel()
    val line1 = metric?.line1 ?: "—"
    val line2 = metric?.line2
    val cx = g.w / 2f; val cy = g.h / 2f
    if (line2 == null) {
        g.c.drawText(line1, cx, cy + g.dp(4f), g.text(22f, CockpitGauges.TEXT, bold = true))
    } else {
        val mid = g.text(14f, CockpitGauges.TEXT, bold = true)
        g.c.drawText(line1, cx, cy - g.dp(6f), mid)
        g.c.drawText(line2, cx, cy + g.dp(14f), mid)
    }
    g.c.drawText(label, cx, g.h - g.dp(12f), g.text(11f, accent))
}
