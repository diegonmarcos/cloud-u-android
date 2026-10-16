package com.diegonmarcos.superapp.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.diegonmarcos.superapp.extinstall.ComposeAsk
import com.diegonmarcos.superapp.uikit.CloudKitTheme

/**
 * Compose popups anchored under a tapped View — what the status-strip bubbles (system, network, battery, calendar, music)
 * were as hand-built PopupWindows. The content is Compose under CloudKitTheme; the window is Compose's own [Popup], hosted
 * by a zero-size ComposeView added to the activity, so lifecycle and saved-state owners resolve exactly as for the rest of
 * the page (a ComposeView inside a bare PopupWindow has none).
 */
object KitPopup {

    /** START/END: the bubble's left/right edge on the anchor's; EDGE: its left edge on the window's (x = 0). */
    enum class Align { START, END, EDGE }

    /** Show [content] under [anchor]. [close] dismisses it. False when no activity can host it. */
    fun show(anchor: View, align: Align = Align.END, content: @Composable (close: () -> Unit) -> Unit): Boolean {
        val act = ComposeAsk.activityOf(anchor.context) ?: return false
        if (act.isFinishing) return false
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val rect = IntRect(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height)
        val gap = (6 * anchor.resources.displayMetrics.density).toInt()
        val host = ComposeView(act)
        var closed = false
        fun close() {
            if (closed) return
            closed = true
            (host.parent as? ViewGroup)?.removeView(host)
        }
        host.setContent {
            CloudKitTheme(LauncherPalette.kit(act)) {
                Popup(popupPositionProvider = Below(rect, align, gap), onDismissRequest = ::close,
                    properties = PopupProperties(focusable = true)) {
                    content(::close)
                }
            }
        }
        return runCatching { act.addContentView(host, ViewGroup.LayoutParams(0, 0)); true }.getOrElse { false }
    }

    /** Under the anchor, edge-aligned to it, kept inside the window; above it when there is no room below. */
    internal class Below(private val anchor: IntRect, private val align: Align, private val gap: Int) : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
        ): IntOffset {
            val x = (when (align) { Align.END -> anchor.right - popupContentSize.width; Align.START -> anchor.left; Align.EDGE -> 0; Align.CENTER -> (windowSize.width - popupContentSize.width) / 2 })
                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
            val below = anchor.bottom + gap
            val y = if (below + popupContentSize.height <= windowSize.height) below
            else (anchor.top - gap - popupContentSize.height).coerceAtLeast(0)
            return IntOffset(x, y)
        }
    }
}

/** The dark-glass bubble every strip popup wears: 0xEE111111, a 1 dp hairline, 12 dp corners, 12 dp padding. */
@Composable
fun KitBubble(modifier: Modifier = Modifier, scroll: ScrollState? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(shape = shape, color = Color(0xEE111111), shadowElevation = 8.dp,
        modifier = modifier.border(1.dp, Color(0x44FFFFFF), shape).background(Color.Transparent, shape)) {
        Column(Modifier.let { if (scroll != null) it.verticalScroll(scroll) else it }.padding(12.dp), content = content)
    }
}
