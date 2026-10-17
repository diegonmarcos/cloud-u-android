package com.diegonmarcos.superapp.launcher

import android.view.View
import android.view.ViewGroup

/**
 * Keeps the toolbar island BELOW the status strip, whatever height the strip settles at.
 *
 * The island's top margin is the strip's height plus a gap. The strip used to be a View whose height was final at its first
 * layout; as a ComposeView its first layout is the height of an empty composition and it grows as its readings arrive, so the
 * margin has to track every layout of the strip rather than be measured once.
 */
object StripIslandGap {

    /** Margin the island needs under a strip [stripHeight] tall. Zero height (not laid out yet) asks for nothing. */
    fun marginFor(stripHeight: Int, gapPx: Int): Int? = if (stripHeight > 0) stripHeight + gapPx else null

    /** Install once per strip: every layout that changes its height re-seats [island]. Idempotent on repeat calls. */
    fun follow(strip: View, island: View, gapPx: Int) {
        installed.remove(strip)?.let { strip.removeOnLayoutChangeListener(it) }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> seat(strip, island, gapPx) }
        installed[strip] = listener
        strip.addOnLayoutChangeListener(listener)
        seat(strip, island, gapPx)
    }

    private fun seat(strip: View, island: View, gapPx: Int) {
        val want = marginFor(strip.height, gapPx) ?: return
        val lp = island.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != want) {
            lp.topMargin = want
            island.layoutParams = lp
        }
    }

    private val installed = java.util.WeakHashMap<View, View.OnLayoutChangeListener>()
}
