package com.diegonmarcos.superapp.launcher

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.system.Trace
import com.google.android.material.appbar.MaterialToolbar
import java.util.Random

/**
 * Toolbar/bottom-nav "liveliness" effects, extracted from MainActivity so the
 * Activity isn't the home for ~250 lines of pure UI animation glue:
 *   • long-press fan on the bottom-nav items ([HomeFanMenu])
 *   • a tooltip-consumer that suppresses OEM long-press tooltips on the toolbar
 *   • a periodic low-amplitude "jitter" tic on the drawer hamburger
 *
 * Owns its own handlers/state (no getter/setter plumbing back into the Activity).
 * Lifecycle: [install] once from onCreate; [resume]/[pause] from the Activity.
 */
class LauncherToolbarFx(
    private val activity: AppCompatActivity,
    private val bottomNav: BottomNavIslandView,
    private val onTile: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random()

    private var jitterRunnable: Runnable? = null

    /** Each nav item's capsule on screen, by section id: where its fan opens and the origin
     *  its finger positions are raised to screen coordinates from. */
    private val anchors = mutableMapOf<String, Rect>()

    /** One-time setup from onCreate. */
    fun install() {
        installNavFanMenus()
        installTooltipConsumer()
    }

    fun resume() = scheduleHamburgerJitter()
    fun pause() = cancelHamburgerJitter()

    // ── long-press fan menu on every bottom-nav item ─────────────────────
    /** Home keeps its own fixed 4-bubble layout ([HomeFanMenu.homeItems]);
     *  the other items render their build.json::sections[*].pages
     *  list (Sections.Section.pages) — empty ⇒ no fan menu for that item.
     *  The island is Compose (#531), so the fan hangs on each item's capsule
     *  through BottomNavIslandView.itemModifier instead of a child View. */
    private fun installNavFanMenus() {
        bottomNav.itemModifier = { id -> fanItemsFor(id).let { if (it.isEmpty()) Modifier else fanGesture(id, it) } }
    }

    private fun fanItemsFor(sectionId: String): List<Pair<String, Pair<Int, String>>> {
        val ctx = bottomNav.context
        if (sectionId == "home") return HomeFanMenu.homeItems(ctx)
        val section = Sections.byId(sectionId) ?: return emptyList()
        // The fan menu IS the section's page list — one declaration
        // in build.json feeds the section grid, this menu, the
        // Sirius ring and the tablet detail pane.
        // ponytail: geometry tops out at 4 bubbles (1 top + 3 along
        // the bottom row), so a longer page list is truncated here.
        // Widen HomeFanMenu's layout if a section ever needs more.
        return section.pages.take(4).map { p ->
            val target = if (p.action.isNotBlank()) p.action else "page:$sectionId/${p.id}"
            target to (Sections.iconResFor(ctx, p.iconName ?: "") to p.label)
        }
    }

    /**
     * Hold an item still for [FAN_DELAY_MS] and the fan opens over it; slide to a bubble and
     * lift to pick it. Watched on the Initial pass and consumed only once the fan is open, so a
     * plain tap still selects the item, and a fan gesture never also selects it on release.
     */
    private fun fanGesture(sectionId: String, items: List<Pair<String, Pair<Int, String>>>): Modifier {
        return Modifier
            .onGloballyPositioned { c ->
                val o = c.positionOnScreen()
                anchors[sectionId] = Rect(o.x.toInt(), o.y.toInt(), (o.x + c.size.width).toInt(), (o.y + c.size.height).toInt())
            }
            .pointerInput(sectionId, items) {
                val slop = FAN_SLOP_DP * density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val early = withTimeoutOrNull(FAN_DELAY_MS) { awaitLiftOrDrift(down.id, down.position, slop) }
                    val anchor = anchors[sectionId]
                    if (early != null || anchor == null) return@awaitEachGesture
                    Trace.i(TAG, "fan open: nav item $sectionId")
                    bottomNav.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val fan = HomeFanMenu.show(bottomNav, anchor, items) { target -> onTile(target) }
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        if (change == null) { fan.dismiss(); break }
                        fan.updateFinger(anchor.left + change.position.x, anchor.top + change.position.y)
                        change.consume()
                        if (!change.pressed) { fan.commit(); break }
                    }
                }
            }
    }

    /** Returns as soon as the finger lifts or wanders past [slop]: a tap or a scroll, not a hold. */
    private suspend fun AwaitPointerEventScope.awaitLiftOrDrift(id: PointerId, start: Offset, slop: Float) {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: return
            if (!change.pressed || (change.position - start).getDistance() > slop) return
        }
    }

    // ── tooltip consumer (OEM long-press tooltip suppression) ───────────
    private fun installTooltipConsumer() {
        val consumer = View.OnLongClickListener { Trace.i(TAG, "tooltip consumer fired id=${it.id}"); true }
        fun applyToChildren(vg: ViewGroup) {
            for (i in 0 until vg.childCount) vg.getChildAt(i).setOnLongClickListener(consumer)
        }
        val hookListener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { child?.setOnLongClickListener(consumer) }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        }
        val toolbar = activity.findViewById<View>(R.id.toolbar) as? ViewGroup ?: return
        toolbar.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.setOnLongClickListener(consumer)
                if (child is ViewGroup) { child.setOnHierarchyChangeListener(hookListener); applyToChildren(child) }
            }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        applyToChildren(toolbar)
    }

    // ── hamburger jitter — random low-amplitude tic every 3–5s ──────────
    private fun scheduleHamburgerJitter() {
        cancelHamburgerJitter()
        // Configs → "All animations" off → never arm the jitter loop at all.
        if (!runCatching {
                com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(activity).anim()
            }.getOrDefault(true)) return
        val toolbar: MaterialToolbar = activity.findViewById(R.id.toolbar) ?: return
        toolbar.post { findToolbarNavIcon(toolbar)?.let { postNextJitter(it) } }
    }

    private fun cancelHamburgerJitter() {
        jitterRunnable?.let { handler.removeCallbacks(it) }
        jitterRunnable = null
    }

    private fun findToolbarNavIcon(toolbar: ViewGroup): View? {
        val navDrawable = (toolbar as? MaterialToolbar)?.navigationIcon
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            if (v is ImageButton && (navDrawable == null || v.drawable == navDrawable)) return v
        }
        return null
    }

    private fun postNextJitter(view: View) {
        val delayMs = 3000L + rng.nextInt(2000)
        jitterRunnable = Runnable { playHamburgerJitter(view); postNextJitter(view) }
        handler.postDelayed(jitterRunnable!!, delayMs)
    }

    private fun playHamburgerJitter(view: View) {
        val flavour = rng.nextInt(3)
        val durMs = 240L + rng.nextInt(160)
        val rotPeak = (rng.nextFloat() * 8f + 4f) * if (rng.nextBoolean()) 1f else -1f
        val xPeak = (rng.nextFloat() * 4f + 2f) * if (rng.nextBoolean()) 1f else -1f
        val animators = mutableListOf<android.animation.ObjectAnimator>()
        if (flavour != 1) animators += android.animation.ObjectAnimator.ofFloat(
            view, "translationX", 0f, xPeak, -xPeak * 0.6f, xPeak * 0.25f, 0f).apply { duration = durMs }
        if (flavour != 0) animators += android.animation.ObjectAnimator.ofFloat(
            view, "rotation", 0f, rotPeak, -rotPeak * 0.6f, rotPeak * 0.25f, 0f).apply { duration = durMs }
        android.animation.AnimatorSet().apply { playTogether(*animators.toTypedArray()); start() }
    }

    private companion object {
        const val TAG = "LauncherToolbarFx"
        /** Hold time before the fan opens, and how far the finger may wander first (dp). */
        const val FAN_DELAY_MS = 380L
        const val FAN_SLOP_DP = 48f
    }
}
