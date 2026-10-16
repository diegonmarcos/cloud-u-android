package com.diegonmarcos.superapp.media

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import kotlin.math.roundToInt

/**
 * A thumbnail loaded by the shared Coil loader (its GIF/WebP decoders animate the drawable). Shared by
 * the media panel and the keyboard's emoji search bar, so neither needs an ImageView or a Coil
 * dependency of its own.
 */
@Composable
fun MediaRemoteImage(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var drawable by remember(url) { mutableStateOf<Drawable?>(null) }
    DisposableEffect(url) {
        val request = ImageRequest.Builder(ctx).data(url).target(onSuccess = { drawable = it }).build()
        val handle = MediaRuntime.imageLoader(ctx).enqueue(request)
        onDispose { handle.dispose() }
    }
    val d = drawable
    if (d != null) {
        val painter = remember(d) { DrawablePainter(d) }
        DisposableEffect(painter) {
            painter.start()
            onDispose { painter.stop() }
        }
        Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier)
    }
}

/** Draws an Android [Drawable] (animated ones included) into a Compose canvas. */
internal class DrawablePainter(private val d: Drawable) : Painter() {
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
