package com.diegonmarcos.superapp.extinstall

import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The install progress ring on an `extapp:` tile, in Compose: a circular ring over the tile's icon
 * plus a small state label. It reads [ExtAppInstalls] (the one store) through a listener bound to
 * the composition, so it survives scrolling, leaving and returning to the page, and a new process
 * (the worker keeps publishing; the next composition reads the row).
 *
 * [iconTop] / [iconSize] are where the tile puts its icon (the ring is centred on it).
 */
@Composable
fun Modifier.extInstallRing(tileId: String, iconSize: Dp, iconTop: Dp = 8.dp): Modifier {
    val pkg = remember(tileId) { ExtTileRing.packageFor(tileId) } ?: return this
    val ctx = LocalContext.current
    val view = LocalView.current
    var progress by remember(pkg) { mutableStateOf(ExtInstallViews.tile(ExtAppInstalls.snapshot(pkg))) }
    DisposableEffect(pkg) {
        val l: (String) -> Unit = { p -> if (p == pkg) progress = ExtInstallViews.tile(ExtAppInstalls.snapshot(pkg)) }
        ExtAppInstalls.addListener(l)
        ExtAppInstalls.refresh(ctx)
        progress = ExtInstallViews.tile(ExtAppInstalls.snapshot(pkg))
        // Install finished: launch it if the user is still on the screen with this tile (ExtTileRing.onInstalled).
        ExtTileRing.showing(pkg, view)
        onDispose { ExtAppInstalls.removeListener(l); ExtTileRing.hidden(pkg, view) }
    }
    val bad = LocalKitPalette.current.bad
    val accent = LocalKitPalette.current.accent
    return this.drawWithContent {
        drawContent()
        val p = progress ?: return@drawWithContent
        val d = density
        val iconPx = iconSize.toPx()
        val cx = size.width / 2f
        val cy = iconTop.toPx() + iconPx / 2f
        val r = iconPx / 2f + 4 * d
        val topLeft = Offset(cx - r, cy - r)
        val box = Size(2 * r, 2 * r)
        val stroke = 3 * d
        drawArc(Color.White.copy(alpha = 0.27f), 0f, 360f, false, topLeft, box, style = Stroke(stroke))
        when {
            p.phase == ExtPhase.FAILED || p.phase == ExtPhase.CANCELLED ->
                drawArc(bad, 0f, 360f, false, topLeft, box, style = Stroke(stroke))
            p.fraction >= 0f ->
                drawArc(accent, -90f, 360f * p.fraction, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
            else -> // indeterminate: a quarter arc, turning with the wall clock
                drawArc(accent, (System.currentTimeMillis() / 8 % 360).toFloat(), 90f, false, topLeft, box,
                    style = Stroke(stroke, cap = StrokeCap.Round))
        }
        drawIntoCanvas { c ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = 9 * d; textAlign = Paint.Align.CENTER
            }
            val tw = paint.measureText(p.label) + 8 * d
            val ty = minOf(size.height - 3 * d, cy + r + 12 * d)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1E1B2E.toInt() }
            c.nativeCanvas.drawRoundRect(cx - tw / 2, ty - 10 * d, cx + tw / 2, ty + 3 * d, 6 * d, 6 * d, pill)
            c.nativeCanvas.drawText(p.label, cx, ty, paint)
        }
    }
}
