package com.diegonmarcos.superapp.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.battery.EnergyLedger
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * The shell's decorative layers, drawn in Compose: the galaxy backdrop behind everything, the Dynamic Island's four-channel
 * wave and its prismatic neon frame, and the thin line behind the music mini-island. They were four hand-written Views
 * (GalaxyBackdropView, IslandWaveView, PrismaticShimmerView, WaveLineView), each with its own ValueAnimator, attach/detach
 * bookkeeping and a public applyXPref() the Configs page had to find by id to reach. Here each is one composable on a frame
 * clock, and the three Configs switches are state ([ShellFxState]) the layers read, so a flip reaches them with no lookup.
 */

/** The live Configs ▸ Launcher ▸ Others switches the layers obey. Each defaults to on; [refresh] re-reads the store. */
object ShellFxState {
    var stars by mutableStateOf(true)
        private set
    var island by mutableStateOf(true)
        private set
    var pets by mutableStateOf(true)
        private set

    /** Tests set this so the layers draw one still frame; a looping frame clock never lets Compose's idle wait return. */
    internal var frozen by mutableStateOf(false)

    /** Re-read "Stars animation", "Island animation" and "Pets". Called at start and whenever one of them flips. */
    fun refresh(ctx: Context) {
        val p = runCatching { LauncherSettingsPrefs(ctx) }.getOrNull()
        stars = runCatching { p?.anim("stars_anim") }.getOrNull() ?: true
        island = runCatching { p?.anim("island_anim") }.getOrNull() ?: true
        pets = runCatching { p?.anim("pets_anim") }.getOrNull() ?: true
    }

    /** Hook the decorative ComposeViews declared in activity_main.xml up to their composables. */
    fun install(activity: Activity) {
        refresh(activity)
        activity.findViewById<ComposeView>(R.id.galaxy_backdrop)?.setContent { GalaxyBackdrop() }
        activity.findViewById<ComposeView>(R.id.dynamic_island_wave)?.setContent { IslandWave() }
        activity.findViewById<ComposeView>(R.id.dynamic_island_prism)?.setContent { PrismaticShimmer() }
        activity.findViewById<ComposeView>(R.id.music_playing_wave)?.setContent { WaveLine() }
    }
}

/** A looping 0..1 phase on the frame clock, [periodMs] per lap; frozen (keeping its place) while [running] is false. */
@Composable
private fun rememberPhase(periodMs: Long, running: Boolean): Float {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, periodMs, ShellFxState.frozen) {
        if (!running || ShellFxState.frozen) return@LaunchedEffect
        val start = withFrameNanos { it }
        val base = phase
        while (true) {
            val now = withFrameNanos { it }
            phase = (base + (now - start) / (periodMs * 1_000_000f)) % 1f
        }
    }
    return phase
}

// ── Galaxy backdrop ────────────────────────────────────────────────────────────

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)

/** Each comet travels DIAGONALLY across the full screen, on its own phase of the 18 s lap. */
private class Comet(val phase: Float, val sx: Float, val sy: Float, val ex: Float, val ey: Float, val travel: Float)

private val STARS: List<Star> = Random(20260531L).let { rng ->
    (0..220).map {
        Star(rng.nextFloat(), rng.nextFloat(), rng.nextFloat() * 2.2f + 0.4f, rng.nextFloat() * (PI * 2f).toFloat())
    }
}
private val COMETS = listOf(
    Comet(0.00f, -0.15f, -0.10f, 1.20f, 0.95f, 0.35f),
    Comet(0.42f, 1.15f, 0.05f, -0.20f, 1.05f, 0.40f),
    Comet(0.78f, -0.15f, 0.85f, 1.10f, -0.10f, 0.32f),
)

/**
 * Procedural galaxy backdrop: 220 twinkling stars (slow alpha sin-modulation) and 3 cross-screen comets (long bright tails,
 * full-diagonal travel, each on its own phase). Seeded for a stable star layout across launches. Off ("Stars animation")
 * freezes the loop - the stars stay drawn - to save battery.
 */
@Composable
fun GalaxyBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    val time = rememberPhase(18_000L, ShellFxState.stars)
    Canvas(modifier) {
        EnergyLedger.track("ui.galaxy") {
            val w = size.width; val h = size.height
            val starInk = Color(0xFFE9D8FD)
            for (s in STARS) {
                val twinkle = (sin(time * 12f + s.phase) * 0.4f + 0.6f).absoluteValue
                val a = (twinkle * 220f).toInt().coerceIn(40, 255)
                drawCircle(starInk.copy(alpha = a / 255f), s.r, Offset(s.x * w, s.y * h))
            }
            for (c in COMETS) {
                val cycle = (time + c.phase) % 1f
                if (cycle > c.travel) continue
                val prog = cycle / c.travel
                val headX = (c.sx + (c.ex - c.sx) * prog) * w
                val headY = (c.sy + (c.ey - c.sy) * prog) * h
                val dirX = (c.ex - c.sx) * w
                val dirY = (c.ey - c.sy) * h
                val len = kotlin.math.sqrt(dirX * dirX + dirY * dirY)
                val tailLen = 220f
                val ux = dirX / len; val uy = dirY / len
                // Tail = many short stroke segments, each fainter than the last.
                val seg = 12
                for (i in 0 until seg) {
                    val t0 = i.toFloat() / seg
                    val t1 = (i + 1f) / seg
                    val a = ((1f - t0) * 230f).toInt().coerceIn(0, 255)
                    drawLine(Color(0xFFB794F4).copy(alpha = a / 255f),
                        Offset(headX - ux * tailLen * t0, headY - uy * tailLen * t0),
                        Offset(headX - ux * tailLen * t1, headY - uy * tailLen * t1),
                        strokeWidth = 3.5f - t0 * 3f, cap = StrokeCap.Round)
                }
                drawLine(Color.White, Offset(headX - ux * 4f, headY - uy * 4f), Offset(headX + ux * 4f, headY + uy * 4f),
                    strokeWidth = 5f, cap = StrokeCap.Round)
            }
        }
    }
}

// ── Dynamic Island wave ────────────────────────────────────────────────────────

/** One channel: three harmonics and a slow amplitude modulator. list[0] paints FIRST (back), list[3] LAST (front). */
private class Wave(
    val color: Color, val baseFreq: Float, val h2: Float, val h3: Float, val phaseSpeed: Float,
    val baseAmp: Float, val ampModFreq: Float, val ampModPhase: Float,
    /** Fraction of the height where this channel's centreline sits (0 = top). Staggered so the layers do not collapse. */
    val baselineYFrac: Float,
)

/**
 * Four independent channels, each with its own composite harmonic and slow amplitude modulator, so they bounce, dip and
 * surge out of phase like a real audio waveform. Pastel lilac to soft violet, semi-transparent so the layers blend; BACK
 * waves get the largest amplitude and the deepest baseline, FRONT waves the smallest and the highest, so all four stay
 * visible. Clipped to the pill (radius = half the inset height) so no wave bleeds past its rounded ends. "Island animation"
 * off freezes the waves.
 */
@Composable
fun IslandWave(modifier: Modifier = Modifier.fillMaxSize()) {
    val waves = remember {
        listOf(
            Wave(Color(0x99B69EF9), 1.6f, 2.7f, 3.5f, 1.1f, 0.85f, 0.45f, 0.0f, 0.88f),
            Wave(Color(0xB3CABDFB), 1.9f, 2.4f, 4.1f, 1.5f, 0.70f, 0.60f, 1.3f, 0.82f),
            Wave(Color(0xCCDDD6F3), 2.3f, 3.1f, 4.7f, 0.9f, 0.55f, 0.32f, 2.5f, 0.76f),
            Wave(Color(0xE6EDE9FE), 2.8f, 3.6f, 5.2f, 1.8f, 0.40f, 0.78f, 0.7f, 0.70f),
        )
    }
    // 358.8 s for 1000 time units: the harmonic and modulator rates all scale off the same `time`.
    val time = rememberPhase(358_800L, ShellFxState.island) * 1000f
    val density = LocalDensity.current.density
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (w == 0f || h == 0f) return@Canvas
        val inset = 2f * density
        val radius = min(w - 2 * inset, h - 2 * inset) * 0.5f
        val clip = Path().apply { addRoundRect(RoundRect(inset, inset, w - inset, h - inset, CornerRadius(radius))) }
        clipPath(clip) {
            val step = (w / 90f).coerceAtLeast(1f)
            val twoPi = (2f * PI).toFloat()
            for (wave in waves) {
                val baseline = h * wave.baselineYFrac
                // Slow amplitude modulator - the music-channel "bounce", 0.35 to 1.0 so the wave never fully disappears.
                val ampMod = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * wave.ampModFreq + wave.ampModPhase))
                val amp = wave.baseAmp * ampMod * (h * 0.45f)
                val path = Path()
                var x = 0f
                var first = true
                while (x <= w) {
                    val t = x / w
                    // Three harmonics with slightly different phase speeds, so they slide against each other.
                    val a1 = t * wave.baseFreq * twoPi + time * wave.phaseSpeed
                    val a2 = t * wave.baseFreq * wave.h2 * twoPi + time * wave.phaseSpeed * 1.3f
                    val a3 = t * wave.baseFreq * wave.h3 * twoPi + time * wave.phaseSpeed * 0.8f
                    val composite = (sin(a1.toDouble()) * 0.55 + sin(a2.toDouble()) * 0.30 + sin(a3.toDouble()) * 0.15).toFloat()
                    val y = baseline + composite * amp
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    x += step
                }
                path.lineTo(w, h); path.lineTo(0f, h); path.close()
                drawPath(path, wave.color)
            }
        }
    }
}

// ── Prismatic neon frame ───────────────────────────────────────────────────────

private val RAINBOW = listOf(
    Color(0xFFFF1744), Color(0xFFFF9100), Color(0xFFFFEA00), Color(0xFF00E676),
    Color(0xFF00E5FF), Color(0xFF2979FF), Color(0xFFAA00FF), Color(0xFFFF1744), // back to red closes the loop
)

/** Full-spectrum rainbow chasing around a pill perimeter (3.6 s a lap); radius = height / 2. */
@Composable
fun PrismaticShimmer(modifier: Modifier = Modifier.fillMaxSize(), cornerRadiusDp: Float = -1f) {
    val progress = rememberPhase(3_600L, true)
    val density = LocalDensity.current.density
    Canvas(modifier) {
        val sw = 3f
        val topLeft = Offset(sw, sw)
        val rectSize = Size(size.width - 2 * sw, size.height - 2 * sw)
        val centre = Offset(topLeft.x + rectSize.width / 2f, topLeft.y + rectSize.height / 2f)
        val r = if (cornerRadiusDp < 0f) rectSize.height / 2f else cornerRadiusDp * density
        rotate(progress * 360f, centre) {
            drawRoundRect(Brush.sweepGradient(RAINBOW, centre), topLeft, rectSize, CornerRadius(r), style = Stroke(sw))
        }
    }
}

// ── Music mini-island line ─────────────────────────────────────────────────────

/**
 * Thin scrolling-sinusoid line behind the music mini-island: the sum of a slow body wave and a fast carrier, so it beats
 * and "breathes" without competing with the marquee text. A different visual language from [IslandWave] on purpose.
 */
@Composable
fun WaveLine(modifier: Modifier = Modifier.fillMaxSize()) {
    val phase = rememberPhase(3_200L, true) * (2.0 * PI).toFloat()
    val density = LocalDensity.current.density
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val cy = h / 2f
        val amp = h * 0.22f
        val freqBody = (2.0 * PI / w).toFloat() * 2.4f
        val freqCarrier = (2.0 * PI / w).toFloat() * 7.0f
        val path = Path().apply { moveTo(0f, cy) }
        val step = (2f * density).coerceAtLeast(1f)
        var x = 0f
        while (x <= w) {
            val body = sin(x * freqBody + phase)
            val carrier = sin(x * freqCarrier - phase * 1.7f) * 0.30
            path.lineTo(x, cy + ((body + carrier) * amp).toFloat())
            x += step
        }
        drawPath(path, Color(0xB0C4B5FD), style = Stroke(1f * density, cap = StrokeCap.Round))
    }
}
