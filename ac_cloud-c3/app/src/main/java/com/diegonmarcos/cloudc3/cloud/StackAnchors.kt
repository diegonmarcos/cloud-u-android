package com.diegonmarcos.cloudc3.cloud

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Anchor links WITHIN one aggregator stack — MOVED from the SuperApp's launcher package
 *  with the stack pages (#648).
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
 *  build.json without running the app. See [Stacks.PanelAnchor].
 *
 *  This registry stays a registry: it is handed ids and a place on screen, and has no
 *  opinion about where either came from.
 *
 *  2026-10-16 owner decision: all apps must be Compose. A target is the layout position of the
 *  composable that claimed the id, reached through [mark]; the page scrolls its [ScrollState] there.
 */
class StackAnchors {

    private class Target(val panel: Int, var coords: LayoutCoordinates? = null)

    private val targets = LinkedHashMap<String, Target>()
    private var scroll: ScrollState? = null
    private var column: LayoutCoordinates? = null
    private var scope: CoroutineScope? = null
    private var open: (Int) -> Unit = {}

    /** Called once per stack build, before any panel is registered. [open] expands a collapsed
     *  panel by index: a collapsed body measures zero high, so its anchors sit at the card's top
     *  until it is open. */
    fun reset(scroll: ScrollState?, scope: CoroutineScope?, open: (Int) -> Unit) {
        targets.clear()
        this.scroll = scroll
        this.scope = scope
        this.open = open
        column = null
    }

    /** The scrolling content's own layout, the origin every target is measured from. */
    fun host(): Modifier = Modifier.onGloballyPositioned { column = it }

    /** Claim [id] for the composable [mark] is applied to, inside panel [panel]. */
    fun register(id: String, panel: Int) {
        if (id.isNotBlank() && !targets.containsKey(id)) targets[id] = Target(panel)
    }

    /** The modifier that records where [id] is drawn; a no-op for an id nobody registered. */
    fun mark(id: String): Modifier {
        val t = targets[id] ?: return Modifier
        return Modifier.onGloballyPositioned { t.coords = it }
    }

    /** True when [target] was an `anchor:` link and it was handled — the
     *  caller must then NOT fall through to the navigating dispatcher. An
     *  unknown id returns false rather than scrolling to the top, so a stale
     *  anchor is inert instead of quietly jumping the page somewhere wrong. */
    fun dispatch(target: String): Boolean =
        target.startsWith(PREFIX) && scrollTo(target.removePrefix(PREFIX))

    private fun scrollTo(id: String): Boolean {
        val state = scroll ?: return false
        val scope = scope ?: return false
        val t = targets[id] ?: return false
        open(t.panel)
        scope.launch {
            // One frame for the opened panel to lay out, then measure and scroll there.
            withFrameNanos { }
            val origin = column ?: return@launch
            val coords = t.coords ?: return@launch
            val y = (coords.positionInRoot().y - origin.positionInRoot().y).toInt().coerceAtLeast(0)
            state.animateScrollTo(y)
        }
        return true
    }

    companion object {
        const val PREFIX = "anchor:"
    }
}
