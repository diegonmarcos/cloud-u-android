package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.diegonmarcos.superapp.extinstall.ComposeAsk
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.CloudKitTheme

/**
 * iOS-style folder-action fan menu — long-press the Home bottom-nav
 * slot, slide finger UP to one of the bubbles without releasing,
 * lift finger ON the bubble to commit its action.
 *
 *   ╭────────╮  ╭────────╮
 *   │ Tabs   │  │ Configs│
 *   ╰────╮ ╭─╯  ╰─╮ ╭────╯
 *        │ │      │ │
 *        ╰─╯      ╰─╯
 *
 * Public surface = [show], which returns a [Controller] the host
 * activity drives with the raw MotionEvent stream.
 */
object HomeFanMenu {

    interface Controller {
        /** Update which bubble is highlighted, based on finger screen position. */
        fun updateFinger(rawX: Float, rawY: Float)
        /** Commit the currently-highlighted bubble's action (if any) and dismiss. */
        fun commit()
        /** Dismiss without committing. */
        fun dismiss()
    }

    /** Home's own fixed 4-bubble layout — DERIVED from the ONE declarations
     *  (the ui.pages page registry plus ui.sections), never a hand-written
     *  Kotlin list, so the fan cannot drift from the site map. Home keeps
     *  its own fixed 4-bubble layout rather than the generic
     *  [show] overload (which renders build.json::sections[*].pages) per the
     *  user's "Home stays as it is" instruction.
     *
     *  Everything here is looked up, not restated: the section bubble reads
     *  [Sections.byId] for its label and icon, the three page bubbles read
     *  the ui.pages registry head-on so a rename of the destination moves the
     *  bubble with it. No destination string or label is written in Kotlin.
     */
    fun homeItems(ctx: android.content.Context): List<Pair<String, Pair<Int, String>>> {
        val out = mutableListOf<Pair<String, Pair<Int, String>>>()
        Sections.byId("config")?.let { cfg ->
            out += "section:config" to
                (iconRes(ctx, cfg.iconName) to cfg.label)
        }
        // ui.pages registry ids, in Home's fixed order.
        for (id in listOf("recent-apps", "apptabs-grid", "update")) {
            Pages.byId(id)?.let { pg ->
                out += pg.target to (iconRes(ctx, pg.icon) to pg.label)
            }
        }
        return out
    }

    private fun iconRes(ctx: android.content.Context, name: String): Int =
        if (name.isBlank()) 0 else Sections.iconResFor(ctx, name)

    /** What the fan draws and the finger moves: the armed bubble, and where each bubble sits on the SCREEN. */
    internal class FanState {
        var highlighted by mutableIntStateOf(-1)
        val centers = mutableStateMapOf<Int, androidx.compose.ui.geometry.Offset>()
    }

    /** items ordering = render order = bubble index. Top entry FIRST so
     *  bubbles[0] is the centered top bubble; commit() reads items[idx]
     *  by the same index the finger-detection loop fills in. 1 item ⇒
     *  top-only; 2-4 items ⇒ 1 top + rest along the bottom row. */
    fun show(host: View, anchor: android.graphics.Rect, items: List<Pair<String, Pair<Int, String>>>, onPick: (target: String) -> Unit): Controller {
        val ctx = host.context
        val state = FanState()
        val act = ComposeAsk.activityOf(ctx)
        val root = ComposeView(ctx)
        var shown = false
        fun dismissNow() { (root.parent as? ViewGroup)?.removeView(root) }
        val controller = object : Controller {
            override fun updateFinger(rawX: Float, rawY: Float) {
                state.highlighted = nearest(state.centers, rawX, rawY)
            }

            override fun commit() {
                val idx = state.highlighted
                if (idx in items.indices) onPick(items[idx].first)
                dismiss()
            }

            override fun dismiss() { runCatching { dismissNow() } }
        }
        if (act == null || act.isFinishing) return controller
        // Window <-> screen: the popup is positioned in WINDOW coordinates, the finger and the anchor are on the SCREEN.
        val onScreen = IntArray(2); val inWindow = IntArray(2)
        host.getLocationOnScreen(onScreen); host.getLocationInWindow(inWindow)
        val dx = onScreen[0] - inWindow[0]; val dy = onScreen[1] - inWindow[1]
        val animate = anim(host)
        root.setContent {
            CloudKitTheme(LauncherPalette.kit(act)) {
                Popup(
                    popupPositionProvider = FanPosition(anchor, dx, dy, (8 * host.resources.displayMetrics.density).toInt(),
                        (12 * host.resources.displayMetrics.density).toInt()),
                    // Not focusable and not dismissable: the main activity drives the gesture, the fan only draws it.
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false, dismissOnBackPress = false, clippingEnabled = false),
                ) {
                    Fan(items, state, animate, dx, dy)
                }
            }
        }
        shown = runCatching { act.addContentView(root, ViewGroup.LayoutParams(0, 0)); true }.getOrElse { false }
        return controller
    }

    /** Index of the bubble nearest the finger, or -1 beyond the ~140 px activation radius. */
    internal fun nearest(centers: Map<Int, androidx.compose.ui.geometry.Offset>, x: Float, y: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for ((i, c) in centers) {
            val ddx = x - c.x; val ddy = y - c.y
            val d = ddx * ddx + ddy * ddy
            // Activation radius (in px²) — only highlight when finger is reasonably close (within ~140px of centre).
            if (d < 140f * 140f && d < bestD) { best = i; bestD = d }
        }
        return best
    }

    /** Centred over the pressed item (kept 8 dp inside the window), 12 dp above it. */
    private class FanPosition(
        private val anchor: android.graphics.Rect, private val dx: Int, private val dy: Int,
        private val margin: Int, private val gap: Int,
    ) : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val cx = anchor.centerX() - dx
            val x = (cx - popupContentSize.width / 2).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
            return IntOffset(x, anchor.top - dy - popupContentSize.height - gap)
        }
    }

    /** The old OvershootInterpolator(1.6). */
    private val OVERSHOOT = Easing { t -> val x = t - 1f; x * x * ((1.6f + 1f) * x + 1.6f) + 1f }

    /** The fan: bubbles[0] in a centred top row, the rest along the bottom row. */
    @Composable
    internal fun Fan(items: List<Pair<String, Pair<Int, String>>>, state: FanState, animate: Boolean, dx: Int = 0, dy: Int = 0) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        Column(Modifier.padding(14.dp).testTag("fan:menu"), horizontalAlignment = Alignment.CenterHorizontally) {
            Row { items.firstOrNull()?.let { Bubble(0, it.second.first, it.second.second, state, animate, 0f, -20f, dx, dy) } }
            Row {
                items.drop(1).forEachIndexed { k, it ->
                    // The outer bubbles slide inward toward the centred one; all rise from below.
                    Bubble(k + 1, it.second.first, it.second.second, state, animate, (1f - k) * 60f, 20f, dx, dy)
                }
            }
        }
    }

    @Composable
    private fun Bubble(index: Int, iconRes: Int, label: String, state: FanState, animate: Boolean, fromX: Float, fromY: Float, dx: Int, dy: Int) {
        val armed = state.highlighted == index
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Duration 0 when animations are off — the grow is what tells you which bubble is armed, so it still applies,
        // it just snaps instead of easing.
        val scale by animateFloatAsState(if (armed) 1.35f else 1f, tween(if (animate) 110 else 0), label = "fan-scale")
        val enter = remember { Animatable(if (animate) 0f else 1f) }
        LaunchedEffect(Unit) { if (animate) enter.animateTo(1f, tween(220, easing = OVERSHOOT)) }
        // Cell wider than the bubble so the scale-up does not clip horizontally.
        Column(
            Modifier.width(60.dp).height(66.dp)
                .graphicsLayer {
                    val e = enter.value
                    translationX = (1f - e) * fromX * density.density
                    translationY = (1f - e) * fromY * density.density
                    alpha = e.coerceIn(0f, 1f)
                }
                .onGloballyPositioned {
                    val p = it.positionInWindow()
                    // Centre of the BUBBLE (the top 40 dp of the cell), on the screen.
                    state.centers[index] = androidx.compose.ui.geometry.Offset(
                        p.x + dx + it.size.width / 2f, p.y + dy + 20f * density.density)
                }.testTag("fan:bubble:$index"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(40.dp).scale(scale).clip(CircleShape)
                .background(if (armed) Color(0xFF7C3AED) else Color(0xFF1A0033))
                .border(2.dp, Color(0x99B794F4), CircleShape).padding(8.dp)) {
                if (iconRes != 0) Icon(painterResource(iconRes), contentDescription = null, tint = Color(0xFFE9D8FD))
            }
            Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }

    /** Configs → "All animations" master switch. Defaults to on if prefs blow up. */
    private fun anim(v: View): Boolean =
        runCatching { LauncherSettingsPrefs(v.context).anim() }.getOrDefault(true)
}
