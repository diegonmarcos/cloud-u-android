package com.diegonmarcos.superapp.zoomies

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import kotlin.math.roundToInt

/**
 * A single animated pet that sits in status-bar Line 0 above its Line-1 tool.
 *
 * The animal is FIXED per tool ([setAnimal]); the tool's strength only changes
 * the GAIT ([setGait]) — idle → walk → walk_fast → run — so a weak tool ambles
 * and a strong one sprints. Frames come from the vendored zoomies GIFs at
 * `assets/zoomies/<animal>/<variant>_<gait>.gif` (CC BY-ND, used unmodified).
 *
 * Rendering is dependency-free: API 28+ decodes the GIF to an
 * [AnimatedImageDrawable] and plays it; API 26-27 shows a static first frame
 * (still conveys which animal/level, just not animated).
 *
 * 2026-10-16 owner decision: all apps must be Compose. The pet is a Compose view (an [Image] over
 * a painter that plays the decoded drawable); the status strip still adds it like any View and
 * drives it through [animate], [setAnimal] and [setGait].
 */
class PetStrengthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    private var animal by mutableStateOf("")
    private var variant by mutableStateOf("")
    // Not `gait`: a delegated private property still gets a setGait(String), the public setter below.
    private var gaitName by mutableStateOf("")
    private var on by mutableStateOf(true)

    /** Configs → Launcher → Others → "Animal animations". OFF = don't render
     *  the pet at all (view GONE); ON = render + animate. */
    var animate: Boolean = true
        set(v) {
            if (v == field) return
            field = v
            on = v
            visibility = if (v) VISIBLE else GONE
        }

    /** Fix the animal + colour variant for this tool. Reloads if a gait is set. */
    fun setAnimal(animal: String, variant: String) {
        this.animal = animal
        this.variant = variant
    }

    /** Set the gait (idle/walk/walk_fast/run) from the tool's current strength.
     *  No-ops if unchanged, so the per-tool ticker can call it freely. */
    fun setGait(gait: String) {
        gaitName = gait
    }

    /** Resolve the gait GIF, falling back to an available gait when the exact
     *  one isn't bundled (e.g. 3-gait animals like skeleton have no walk_fast).
     *  Order: requested → idle → run → walk → walk_fast. */
    private fun resolvePath(animal: String, variant: String, gait: String): String? {
        val dir = "zoomies/$animal"
        val avail = runCatching { context.assets.list(dir)?.toSet() }.getOrNull()
            ?: return "$dir/${variant}_$gait.gif"
        for (g in listOf(gait, "idle", "run", "walk", "walk_fast")) {
            val f = "${variant}_$g.gif"
            if (avail.contains(f)) return "$dir/$f"
        }
        return null
    }

    private fun load(path: String): Drawable? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.assets, path)
            val drawable = ImageDecoder.decodeDrawable(src)
            (drawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable
        } else {
            // API 26-27: no AnimatedImageDrawable — first frame, static.
            context.assets.open(path).use { BitmapDrawable(resources, BitmapFactory.decodeStream(it)) }
        }
    }.getOrNull() // Missing/oddly-encoded asset: draw nothing rather than crash the whole status strip.

    @Composable
    override fun Content() {
        if (!on || animal.isEmpty() || gaitName.isEmpty()) return
        val path = remember(animal, variant, gaitName) { resolvePath(animal, variant, gaitName) } ?: return
        val drawable = remember(path) { load(path) } ?: return
        val painter = remember(drawable) { DrawablePainter(drawable) }
        DisposableEffect(painter) {
            painter.start()
            onDispose { painter.stop() }
        }
        Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** Plays an Android [Drawable] (animated ones included) on a Compose canvas. */
private class DrawablePainter(private val d: Drawable) : Painter() {
    private var tick by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { tick++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what) }
    }

    fun start() { d.callback = callback; (d as? Animatable)?.start() }
    fun stop() { (d as? Animatable)?.stop(); d.callback = null }

    override val intrinsicSize: Size
        get() = if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0)
            Size(d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()) else Size.Unspecified

    override fun DrawScope.onDraw() {
        tick // read, so a frame the drawable invalidates redraws
        drawIntoCanvas { c ->
            d.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            d.draw(c.nativeCanvas)
        }
    }
}
