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
 *  Ids are DATA, never a map in Kotlin. A panel names itself in build.json
 *  (`"anchor": "pub-urls"`) and a tile points at it (`"target":
 *  "anchor:pub-urls"`). A `cloud_dashboard` additionally registers
 *  `<panel anchor>/<group id>` and `<panel anchor>/<slugged subgroup label>`
 *  for every header it draws, so one card's sub-tables are addressable
 *  without splitting the card into four panels that the data does not have.
 *
 *  FIRST REGISTRATION WINS. The Stack card draws a group header "DBs
 *  (storage)" immediately followed by a subgroup header "DBs" — both slug to
 *  `dbs`, and the group header is the one worth landing on.
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

    /** Register a header drawn INSIDE panel [panelAnchor]. No-op when the
     *  panel declared no anchor, so a card nobody points at costs nothing. */
    fun registerChild(panelAnchor: String, key: String, view: View) {
        if (panelAnchor.isNotBlank()) register("$panelAnchor/${slug(key)}", view)
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

        /** Lower-case, non-alphanumerics collapsed to nothing — "MCP & API"
         *  and "APIs" become `mcpapi` and `apis`. Used only for headers whose
         *  label is the sole identifier the data carries (cloud_services.json
         *  subgroups have no id of their own). */
        fun slug(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }
    }
}
