package com.diegonmarcos.superapp.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.launch

/**
 * Compose popups anchored under a tapped View — what the status-strip bubbles (system, network, battery, calendar, music)
 * were as hand-built PopupWindows. The content is Compose under CloudKitTheme; the window is Compose's own [Popup], hosted
 * by a zero-size ComposeView added to the activity, so lifecycle and saved-state owners resolve exactly as for the rest of
 * the page (a ComposeView inside a bare PopupWindow has none).
 */
object KitPopup {

    /** START/END: the bubble's left/right edge on the anchor's; EDGE: its left edge on the window's (x = 0). */
    enum class Align { START, END, EDGE, CENTER }

    /** Show [content] under [anchor]. [close] dismisses it. Returns the closer for the caller to dismiss from outside, or null when no activity can host it. */
    fun show(anchor: View, align: Align = Align.END, content: @Composable (close: () -> Unit) -> Unit): (() -> Unit)? {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        return show(anchor, IntRect(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height), align, content)
    }

    /** As above for a Compose anchor: [rect] is its bounds in the window, [from] any View of that window. */
    fun show(from: View, rect: IntRect, align: Align = Align.END, content: @Composable (close: () -> Unit) -> Unit): (() -> Unit)? {
        val act = ComposeAsk.activityOf(from.context) ?: return null
        if (act.isFinishing) return null
        val gap = (6 * from.resources.displayMetrics.density).toInt()
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
        return if (runCatching { act.addContentView(host, ViewGroup.LayoutParams(0, 0)); true }.getOrElse { false }) ::close else null
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

/**
 * A Compose bottom sheet on the activity's window - what BottomSheetDialog was. Hosted like [KitPopup]: a zero-size
 * ComposeView on the activity, the sheet in Compose's own window. [content] gets a [close] that slides it away.
 */
object KitSheet {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    fun show(act: android.app.Activity, background: Color = Color(0xFF140E1F), content: @Composable (close: () -> Unit) -> Unit): Boolean {
        if (act.isFinishing) return false
        val host = ComposeView(act)
        var closed = false
        fun remove() {
            if (closed) return
            closed = true
            (host.parent as? ViewGroup)?.removeView(host)
        }
        host.setContent {
            CloudKitTheme(LauncherPalette.kit(act)) {
                val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val close: () -> Unit = {
                    scope.launch { state.hide() }.invokeOnCompletion { remove() }
                }
                androidx.compose.material3.ModalBottomSheet(onDismissRequest = ::remove, sheetState = state, containerColor = background) {
                    content(close)
                }
            }
        }
        return runCatching { act.addContentView(host, ViewGroup.LayoutParams(0, 0)); true }.getOrElse { false }
    }
}

/**
 * A full-window Compose dialog on the activity - a dimmed backdrop with the content centred, tap outside to dismiss. What the
 * hand-built `Dialog(Theme_Translucent_NoTitleBar_Fullscreen)` menus were. Hosted like [KitPopup].
 */
object KitDialog {
    fun show(act: android.app.Activity, content: @Composable (close: () -> Unit) -> Unit): Boolean {
        if (act.isFinishing) return false
        val host = ComposeView(act)
        var closed = false
        fun close() {
            if (closed) return
            closed = true
            (host.parent as? ViewGroup)?.removeView(host)
        }
        host.setContent {
            CloudKitTheme(LauncherPalette.kit(act)) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = ::close,
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(
                            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null, onClick = ::close),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        // Taps INSIDE the content must not reach the backdrop.
                        androidx.compose.foundation.layout.Box(Modifier.clickable(
                            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null, onClick = {})) { content(::close) }
                    }
                }
            }
        }
        return runCatching { act.addContentView(host, ViewGroup.LayoutParams(0, 0)); true }.getOrElse { false }
    }

}
