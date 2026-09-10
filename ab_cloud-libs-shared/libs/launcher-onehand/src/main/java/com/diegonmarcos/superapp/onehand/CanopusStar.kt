package com.diegonmarcos.superapp.onehand

import android.app.Activity
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Home-screen Canopus star — triggers [ArcMenu] (the bottom half-moon menu)
 * on touch.
 *
 * Lives in libs:launcher-onehand along with Sirius and Centauri — ALL THREE home
 * stars are lib-side. Canopus's CONTENT is app-specific (a build.json
 * section's pages), so unlike Centauri it cannot be fully self-contained:
 * the app supplies an [ArcMenu.Host] (built from its own SectionPages) and
 * the bottom-nav island [View] to anchor against, at construction time.
 * Everything else — glyph, size, touch-forwarding, position, visibility
 * gating — is generic and lives here.
 *
 * Size/glyph/position read from build.json::onehand.circular_menu.star —
 * same config block as Sirius so both stars are always the same size.
 *
 * This is the app's ONLY Host-driven arc star, so it is what every arc star
 * whose content comes from the app is built from — Canopus itself (bottom of
 * the column, Configs) and Recent Tabs (right half of the midway row, the
 * in-app pages the owner last opened). The three things that differ between
 * them are parameters below rather than a second copy of this file: a copy is
 * how the two existing stars already drifted on tap-anchoring, and a third
 * would only widen that.
 *
 * @param anchorFraction how far down the gap from the star's laid-out position
 *   to the island's top edge it parks. 1 = right above the island (Canopus);
 *   0.5 = the midway row Centauri already occupies.
 * @param sizeBumpSp added to the shared `size_sp`, so a star can be a touch
 *   larger without a second size in the data.
 * @param offsetXDp horizontal nudge from the centre line, positive = right.
 *   0 keeps a star centred; the midway pair passes ±`pair_offset_x_dp`.
 */
class CanopusStar(
    private val activity: Activity,
    private val star: TextView,
    private val island: View?,
    private val host: ArcMenu.Host,
    private val anchorFraction: Float = 1f,
    private val sizeBumpSp: Float = 0f,
    private val offsetXDp: Int = 0,
) {

    private val cfg get() = CircularMenu.config()   // size/glyph same as Sirius
    private var session: ArcMenu.Session? = null
    private var islandLayoutListener: View.OnLayoutChangeListener? = null

    /**
     * Recompute translationY to park the star 12dp above the island's TOP,
     * and apply it — but only once both views actually have a measured
     * height, otherwise there is nothing real to anchor against yet.
     *
     * IMPORTANT: `getLocationInWindow` reports the CURRENT on-screen
     * position, which already includes any translationY from a previous
     * call. If we measured from that directly, a second call would read
     * back its own output and compute an offset of ~0 (star snaps to its
     * untranslated position), and a third call would move it again —
     * an oscillation. So we first subtract the star's own current
     * translationY to recover `base`, its UNTRANSLATED layout position,
     * and compute the new offset from that. This makes the whole function
     * idempotent: calling it any number of times in a row yields the same
     * translationY. Do not "simplify" this away.
     */
    private fun tryAnchor() {
        val isl = island ?: return
        if (isl.height <= 0 || star.height <= 0) return
        val s = IntArray(2); star.getLocationInWindow(s)
        val n = IntArray(2); isl.getLocationInWindow(n)
        val gap = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics)
        val base = s[1] - star.translationY
        star.translationY = (n[1] - gap - (base + star.height)) * anchorFraction
    }

    /** Wire glyph/size/position/tap once; call [update] after for initial state. */
    fun setup() {
        val c = cfg
        if (!c.enabled) { star.visibility = View.GONE; return }
        star.text = c.starGlyph
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.starSizeSp.toFloat() + sizeBumpSp)
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, c.starTapPadDp.toFloat(), activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // Horizontal place on its row. translationX (not a layout margin) for
        // the same reason translationY carries the vertical anchor: the star is
        // a layout_gravity="center" child, so shifting it is a post-layout
        // nudge and nothing else in the frame has to be re-measured.
        star.translationX = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, offsetXDp.toFloat(),
            activity.resources.displayMetrics)
        // Anchor just above the bottom nav island so the arc opens upward.
        if (island != null) {
            // Re-run the anchor on every REAL layout change of either view —
            // first measurement (no race with a "not measured yet" fallback
            // that lands somewhere else), window-insets settling, rotation,
            // gesture-nav vs 3-button nav, multi-window resize, and the
            // island showing/hiding per section. Safe to call repeatedly
            // because tryAnchor() is idempotent (see its kdoc).
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tryAnchor() }
            islandLayoutListener = listener
            island.addOnLayoutChangeListener(listener)
            star.addOnLayoutChangeListener(listener)
            tryAnchor() // covers the warm-start case where both are already laid out
            // Detach when the star leaves the window so we don't leak the Activity.
            star.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    island.removeOnLayoutChangeListener(listener)
                    star.removeOnLayoutChangeListener(listener)
                    star.removeOnAttachStateChangeListener(this)
                    islandLayoutListener = null
                }
            })
        } else {
            // Genuine fallback: no island at all to anchor against.
            star.translationY = star.rootView.height * c.starBottomPct * anchorFraction
        }
        // Forward press→drag→release to the arc-menu.
        star.setOnTouchListener { _, e ->
            val decor = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: return@setOnTouchListener false
            val s = IntArray(2); star.getLocationInWindow(s)
            val d = IntArray(2); decor.getLocationInWindow(d)
            val x = s[0] - d[0] + e.x
            val y = s[1] - d[1] + e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Anchor at the star's CENTRE, not the touched pixel. The
                    // glyph carries tap padding on every side, so opening at
                    // e.x/e.y shifted the whole arc — and its screen-edge clamp —
                    // by up to a finger-width, and it came out arranged
                    // differently on every single press.
                    val ax = s[0] - d[0] + star.width / 2f
                    val ay = s[1] - d[1] + star.height / 2f
                    session = ArcMenu.open(decor, ax, ay, host)
                    session?.feed(x, y, MotionEvent.ACTION_DOWN)
                }
                MotionEvent.ACTION_MOVE -> session?.feed(x, y, MotionEvent.ACTION_MOVE)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    session?.feed(x, y, e.actionMasked); session = null
                    star.performClick()
                }
            }
            true
        }
    }

    /** Show only on the `home` section (same as circular-menu's show_on_section). */
    fun update(currentSection: String) {
        val c = cfg
        star.visibility = if (c.enabled && currentSection == c.showOnSection) View.VISIBLE else View.GONE
    }
}
