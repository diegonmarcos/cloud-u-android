package com.diegonmarcos.superapp.onehand

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * The one base of this library's gesture overlays (arc menu, circular menu, edge-menu preview, radial
 * menu). Each is a full-window Compose canvas: [paint] draws the frame on the Compose canvas's native
 * canvas, so the geometry and paints are exactly what the View's onDraw used to run, and [redraw] is the
 * old invalidate(). Overlay windows (WindowManager roots) carry no view-tree owners, so the base is
 * libs:ui-kit's KitImeComposeView, which installs a minimal always-resumed set on the root.
 */
internal abstract class OverlayCanvasView(ctx: Context) : KitImeComposeView(ctx) {

    private var tick by mutableIntStateOf(0)

    /** Schedule a repaint (what invalidate() was). */
    protected fun redraw() { tick++ }

    /** Draw one frame; [w]/[h] are the window size in px. */
    protected abstract fun paint(c: android.graphics.Canvas, w: Float, h: Float)

    // The overlays paint raw colours of their own on a transparent window; no kit role is read.
    final override fun palette(): KitPalette = KitPalette(
        surface = Color.Transparent, surfaceSelected = Color.Transparent, textPrimary = Color.White,
        textSecondary = Color.White, accent = Color.White, hairline = Color.Transparent, tileInk = Color.Black,
    )

    @Composable
    final override fun Body() {
        Canvas(Modifier.fillMaxSize()) {
            tick   // the draw phase observes this read: redraw() repaints
            drawIntoCanvas { paint(it.nativeCanvas, size.width, size.height) }
        }
    }
}
