package com.diegonmarcos.cloudnav.sky

import android.graphics.Canvas as NativeCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * What the sky shows: the catalogue, the observer, where the phone points (the sensor direction)
 * and the manual drag/pinch offset on top of it, so pan and zoom work even without a working sensor.
 * The data and the pointing are state, so setting them redraws [SkyScreen].
 */
class SkyState {
    var sensorAzimuthDeg by mutableDoubleStateOf(0.0)
    var sensorPitchDeg by mutableDoubleStateOf(0.0)
    var observerLatDeg by mutableDoubleStateOf(0.0)
    var observerLonDeg by mutableDoubleStateOf(0.0)

    var stars by mutableStateOf<List<Star>>(emptyList())
    var constellationLines by mutableStateOf<List<ConstellationSegment>>(emptyList())
    var milkyWay by mutableStateOf<List<SkyPolygon>>(emptyList())
    var bodies by mutableStateOf<List<BodyPosition>>(emptyList())

    var panAzimuthDeg by mutableDoubleStateOf(0.0); private set
    var panPitchDeg by mutableDoubleStateOf(0.0); private set
    var fovDeg by mutableDoubleStateOf(70.0); private set

    /** Drag by ([dx], [dy]) px of scroll distance (previous minus current position) over a [widthPx] x [heightPx] view. */
    fun pan(dx: Float, dy: Float, widthPx: Float, heightPx: Float) {
        val degPerPxX = fovDeg / widthPx.coerceAtLeast(1f)
        val degPerPxY = fovDeg / heightPx.coerceAtLeast(1f)
        panAzimuthDeg += dx * degPerPxX
        panPitchDeg = (panPitchDeg - dy * degPerPxY).coerceIn(-89.0, 89.0)
    }

    /** Pinch by [factor] (>1 zooms in, narrowing the field of view). */
    fun zoom(factor: Float) { fovDeg = (fovDeg / factor).coerceIn(MIN_FOV, MAX_FOV) }

    companion object {
        const val MIN_FOV = 15.0
        const val MAX_FOV = 150.0
    }
}

/** The projection of the sky onto the screen; pure, so it is tested without a screen. */
object SkyProjection {
    /** Screen (x, y) of a point at [altDeg]/[azDeg] looking at [lookAzDeg]/[lookAltDeg]; null if it is outside the field of view. */
    fun project(altDeg: Double, azDeg: Double, lookAzDeg: Double, lookAltDeg: Double, fovDeg: Double, width: Float, height: Float): FloatArray? {
        var dAz = azDeg - lookAzDeg
        dAz = ((dAz + 180.0).mod(360.0)) - 180.0
        val dAlt = altDeg - lookAltDeg
        val half = fovDeg / 2
        if (abs(dAz) > half || abs(dAlt) > half) return null

        val x = (width / 2) + (dAz / half) * (width / 2)
        val y = (height / 2) - (dAlt / half) * (height / 2)
        return floatArrayOf(x.toFloat(), y.toFloat())
    }
}

object SkyTags {
    const val CANVAS = "sky:canvas"
    const val STATUS = "sky:status"
}

/**
 * AR-style sky renderer: stars, full IAU constellation lines, Milky Way outline, and the
 * Sun/planets (from real bundled data — see CelestialData/PlanetEphemeris/CREDITS.md),
 * projected against a "looking direction" that's the device's sensor orientation PLUS a
 * manual drag offset, so pan/pinch-zoom always work even without a working sensor.
 *
 * Moons are deliberately NOT rendered: at naked-eye AR-view scale even Jupiter's Galilean
 * moons are sub-pixel/invisible without a telescope-grade zoom this view doesn't have —
 * adding them would be a token gesture, not a real feature, so it's skipped and disclosed
 * rather than faked.
 */
@Composable
fun SkyScreen(state: SkyState, status: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().testTag(SkyTags.CANVAS).pointerInput(state) {
            detectTransformGestures { _, pan, zoom, _ ->
                // pan is the finger's travel; the scroll distance is the opposite of it.
                state.pan(-pan.x, -pan.y, size.width.toFloat(), size.height.toFloat())
                if (zoom != 1f) state.zoom(zoom)
            }
        }) {
            drawIntoCanvas { c -> drawSky(c.nativeCanvas, state, size.width, size.height) }
        }
        Text(status, Modifier.align(Alignment.TopStart).padding(12.dp).testTag(SkyTags.STATUS), color = ComposeColor(0xCCFFFFFF), fontSize = 13.sp)
    }
}

private fun drawSky(canvas: NativeCanvas, s: SkyState, width: Float, height: Float) {
    val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x442AD8FF; strokeWidth = 2.5f; style = Paint.Style.STROKE }
    val milkyWayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF; style = Paint.Style.FILL }
    val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54A.toInt() }
    val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7EC8E3.toInt() }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); textSize = 26f }
    val bodyLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54A.toInt(); textSize = 30f }
    val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER }
    val bgPaint = Paint().apply { color = Color.parseColor("#05070d") }

    canvas.drawRect(0f, 0f, width, height, bgPaint)
    if (width <= 0f || height <= 0f) return

    val lookAz = s.sensorAzimuthDeg + s.panAzimuthDeg
    val lookAlt = s.sensorPitchDeg + s.panPitchDeg
    val fov = s.fovDeg
    val now = System.currentTimeMillis()
    val lat = s.observerLatDeg; val lon = s.observerLonDeg

    fun projectRaDec(raDeg: Double, decDeg: Double): FloatArray? {
        val (alt, az) = SkyMath.altAz(raDeg, decDeg, lat, lon, now)
        return SkyProjection.project(alt, az, lookAz, lookAlt, fov, width, height)
    }

    for (poly in s.milkyWay) {
        val path = Path()
        var started = false
        var lastVisible = true
        for (pt in poly.ring) {
            val p = projectRaDec(pt[0], pt[1])
            if (p == null) { lastVisible = false; continue }
            if (!started || !lastVisible) { path.moveTo(p[0], p[1]); started = true }
            else path.lineTo(p[0], p[1])
            lastVisible = true
        }
        if (started) canvas.drawPath(path, milkyWayPaint)
    }

    for (seg in s.constellationLines) {
        val pa = projectRaDec(seg.a[0], seg.a[1]) ?: continue
        val pb = projectRaDec(seg.b[0], seg.b[1]) ?: continue
        canvas.drawLine(pa[0], pa[1], pb[0], pb[1], linePaint)
    }

    var visibleCount = 0
    for (st in s.stars) {
        val p = projectRaDec(st.raDeg, st.decDeg) ?: continue
        visibleCount++
        val r = (5.0 - st.mag).coerceIn(0.6, 6.0).toFloat()
        canvas.drawCircle(p[0], p[1], r, starPaint)
    }

    for (b in s.bodies) {
        val p = projectRaDec(b.raDeg, b.decDeg) ?: continue
        visibleCount++
        if (b.name == "Sun") {
            canvas.drawCircle(p[0], p[1], 16f, sunPaint)
            canvas.drawText("Sun", p[0] + 20f, p[1], bodyLabelPaint)
        } else {
            canvas.drawCircle(p[0], p[1], 6f, planetPaint)
            canvas.drawText(b.name, p[0] + 12f, p[1], labelPaint)
        }
    }

    if (visibleCount == 0) {
        canvas.drawText("Nothing this direction — drag to look around", width / 2f, height / 2f, hintPaint)
    }
}
