package com.diegonmarcos.superapp.launcher

import android.view.View
import android.widget.ScrollView
import androidx.core.view.isVisible

/** Anchor links WITHIN one aggregator stack.
 *
 *  A tile whose target reads `anchor:<id>` does not navigate anywhere — it
 *  scrolls the page it is already on to the view registered under `<id>`.
 *  That is the whole feature, and it is deliberately generic: nothing here
 *  knows about C3, or about any particular panel kind. Any stack that
 *  declares anchors gets the behaviour, and the mechanism travels with
 *  whichever module ends up owning stacks.
 *
 *  Ids are DATA, never a map in Kotlin and never derived from a label. A
 *  panel names itself in build.json (`"anchor": "pub-urls"`) and a tile
 *  points at it (`"target": "anchor:pub-urls"`). A panel that draws several
 *  headers additionally DECLARES them — `"anchors": [{"id": "stack/vms",
 *  "group": "providers", "subgroup": "VMs"}, …]` — so one card's sub-tables
 *  are addressable without splitting the card into four panels the data does
 *  not have, and so the full set of anchor targets is readable out of
 *  build.json without running the app. See [Sections.PanelAnchor].
 *
 *  This registry stays a registry: it is handed ids and views, and has no
 *  opinion about where either came from.
 */
class StackAnchors {

    private val targets = LinkedHashMap<String, View>()
    private var host: ScrollView? = null

    /** Called once per stack build, before any panel is registered. */
    fun reset(scroll: ScrollView?) {
        targets.clear()
        host = scroll
    }

    fun register(id: String, view: View) {
        if (id.isNotBlank() && !targets.containsKey(id)) targets[id] = view
    }

    /** True when [target] was an `anchor:` link and it was handled — the
     *  caller must then NOT fall through to the navigating dispatcher. An
     *  unknown id returns false rather than scrolling to the top, so a stale
     *  anchor is inert instead of quietly jumping the page somewhere wrong. */
    fun dispatch(target: String): Boolean =
        target.startsWith(PREFIX) && scrollTo(target.removePrefix(PREFIX))

    private fun scrollTo(id: String): Boolean {
        val scroll = host ?: return false
        val view = targets[id] ?: return false
        // A collapsed panel body measures zero high, so its children all sit
        // at the same y — open every hidden ancestor before measuring or the
        // scroll lands on the card above.
        var p: View? = view
        while (p != null && p !== scroll) {
            val cur: View = p
            cur.isVisible = true
            p = cur.parent as? View
        }
        scroll.post { scroll.smoothScrollTo(0, offsetIn(view, scroll)) }
        return true
    }

    /** Distance from the top of the scrolling content to [view], summed over
     *  the parent chain — the view is nested inside a card inside the column,
     *  so its own `top` alone is meaningless. */
    private fun offsetIn(view: View, scroll: ScrollView): Int {
        var y = 0
        var v: View? = view
        while (v != null && v !== scroll) {
            val cur: View = v
            y += cur.top
            v = cur.parent as? View
        }
        return y.coerceAtLeast(0)
    }

    companion object {
        const val PREFIX = "anchor:"
    }
}
