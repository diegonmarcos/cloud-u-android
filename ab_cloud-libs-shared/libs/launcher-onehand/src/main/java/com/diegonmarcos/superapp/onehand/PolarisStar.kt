package com.diegonmarcos.superapp.onehand

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Polaris — the home screen's TOP star, and the fifth of the set.
 *
 * Lives in libs:launcher-onehand with Sirius, Canopus and Centauri, for the
 * reason the owner gave when the other three moved here: ALL STARS in one-hand.
 *
 * It is the only star that opens no menu. A tap navigates straight to the one
 * destination declared in build.json::onehand.search_star.target — by default
 * `action:open_search`, the same string the Sirius inner ring already carries
 * for Search, so the star and the ring reach one surface and cannot drift.
 *
 * WHY NO MENU. Every menu in this package fans UPWARD: [ArcMenu] caps its
 * radius at `min(cx, width - cx, cy - topInset) - margin`, and [CircularMenu]
 * lays its levels out as upward half-moons for the same reason. At the top of
 * the screen `cy - topInset` is negative, that cap collapses to its floor and
 * the menu draws off the top edge. A star up here can only be a single
 * destination — which is exactly what Search is. Giving it an `actions[]` ring
 * would be flexibility no one could ever use.
 *
 * Glyph, size, tap padding and the home-only visibility rule all come from
 * `onehand.circular_menu.star`, the block every other star reads, so the five
 * stay one family and one size.
 */
class PolarisStar(
    private val activity: Activity,
    private val star: TextView,
    /** Where a tap goes. The app supplies its own tile dispatcher, so this
     *  library never has to know what a target string means. */
    private val navigate: (String) -> Unit,
) {

    private val shared get() = CircularMenu.config()
    private val own get() = CircularMenu.searchStar()

    /** Wire glyph, size, vertical place and tap once; call [update] after. */
    fun setup() {
        val cfg = shared
        val search = own
        if (!cfg.enabled || !search.enabled) { star.visibility = View.GONE; return }
        star.text = cfg.starGlyph
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.starSizeSp.toFloat())
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, cfg.starTapPadDp.toFloat(),
            activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // The view is laid out at the top of the frame, so the declared offset
        // is applied as a translation exactly the way the other stars apply
        // theirs — a post-layout nudge that re-measures nothing. Unlike them
        // there is no island to anchor against and none is wanted: the top edge
        // does not move, so one number from build.json is the whole placement.
        star.translationY = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, search.topOffsetDp.toFloat(),
            activity.resources.displayMetrics)
        star.setOnClickListener { navigate(search.target) }
    }

    /** Show only on the section the whole star family is gated to (`home`). */
    fun update(currentSection: String) {
        val cfg = shared
        star.visibility =
            if (cfg.enabled && own.enabled && currentSection == cfg.showOnSection) View.VISIBLE
            else View.GONE
    }
}
