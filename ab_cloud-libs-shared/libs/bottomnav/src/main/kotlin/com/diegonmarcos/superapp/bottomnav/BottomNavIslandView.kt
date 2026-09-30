package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.painterResource

/** One item as a View-based host declares it: a stable id, its label, and a drawable resource. */
public data class BottomNavViewItem(val id: String, val label: String, @DrawableRes val icon: Int)

/**
 * [BottomNavIsland] for an app whose shell is still an XML layout (superapp, cloud-me,
 * cloud-wallet — #531). It is a Compose HOST, not a View port: everything it draws is the
 * island, so the geometry every app shows is the one this module measures. What it adds is the
 * View-side contract those shells already speak:
 *
 *  - [items] / [selectedId] are plain properties. Setting [selectedId] only MOVES the pill; it
 *    never calls back, so a shell syncing the bar to where it navigated needs no re-entry guard.
 *  - a tap on an unselected item calls [onSelect], a tap on the selected one [onReselect]. The
 *    host decides whether the pill moves, by setting [selectedId].
 *  - [insets] null = the live window's (#477). A shell that already pads its own root for the
 *    system bars passes zero, or the island would clear the bar twice.
 *  - [colorScheme] carries the host's View theme into the island's pill and ink, so a themed
 *    launcher keeps its own light capsule. null = whatever MaterialTheme is around it.
 *  - [collapseOnScrollIn] drives [collapsed] from the host's own scrolling content (#673). Until
 *    it existed, [collapsed] was a property nothing in a View shell ever wrote, so #532's
 *    scroll-collapse reached the Compose hosts only.
 */
public class BottomNavIslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    public var items: List<BottomNavViewItem> by mutableStateOf(emptyList())
    public var selectedId: String? by mutableStateOf(null)
    public var collapsed: Boolean by mutableStateOf(false)
    public var insets: WindowInsets? by mutableStateOf(null)
    public var colorScheme: ColorScheme? by mutableStateOf(null)
    public var itemModifier: (String) -> Modifier by mutableStateOf<(String) -> Modifier>({ Modifier })
    public var onSelect: (String) -> Unit = {}
    public var onReselect: (String) -> Unit = {}

    // #673 the scroll driver's state. [collapseOnScrollIn] REPLACES rather than stacks: the
    // superapp re-configures the bar on every Apps/Admin mode change, and a listener per call
    // would leave the bar driven by as many observers as the user has switched modes.
    private var collapseContent: ViewGroup? = null
    private var collapseListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var collapseOffset: Int = 0

    /**
     * Drive [collapsed] from the scrolling views inside [content] (#673): scrolling the content
     * down collapses the island to icons, scrolling back up restores the labels.
     *
     * The rule is [collapseFor] — the SAME function [BottomNavCollapse] applies to its own delta,
     * so a View shell and a Compose shell collapse on one definition rather than two that can
     * drift. Attached ONCE, to the window's observer, so fragments swapped into [content] later
     * drive the bar with no re-attachment and a host adopting the bar writes no Kotlin beyond
     * this call. It only observes: nothing here consumes a scroll or an inset, so #477's
     * read-never-consume rule holds by construction.
     *
     * Scoped to [content]'s own subtree, so scrolling elsewhere in the window — a navigation
     * drawer's list — measures a zero delta and leaves the bar alone.
     *
     * LIMITATION: the offset is [scrolledOffset], which reads [View.getScrollY]. That is the real
     * scrolled distance for every surface the View shells use (android.widget.ScrollView and
     * androidx NestedScrollView) and always 0 for a RecyclerView, which scrolls by moving its
     * children instead. A RecyclerView content pane would leave the bar expanded. [scrolledOffset]
     * is the ONE function that has to change for that, which is why the limitation is localized
     * there rather than spread across the shells.
     */
    public fun collapseOnScrollIn(content: ViewGroup) {
        detachCollapse()
        collapseContent = content
        collapseListener = ViewTreeObserver.OnScrollChangedListener {
            val now = scrolledOffset(content)
            collapsed = collapseFor(collapsed, (now - collapseOffset).toFloat())
            collapseOffset = now
        }
        attachCollapse()
    }

    /** Re-arm on the live observer, removing first so no call can leave two listeners behind. */
    private fun attachCollapse() {
        val listener = collapseListener ?: return
        val content = collapseContent ?: return
        val observer = content.viewTreeObserver?.takeIf { it.isAlive } ?: return
        observer.removeOnScrollChangedListener(listener)
        observer.addOnScrollChangedListener(listener)
        collapseOffset = scrolledOffset(content)
    }

    private fun detachCollapse() {
        val listener = collapseListener ?: return
        collapseContent?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(listener)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The observer a detached view hands out is a floating one. Re-arm on the window's.
        attachCollapse()
    }

    override fun onDetachedFromWindow() {
        detachCollapse()
        super.onDetachedFromWindow()
    }

    @Composable
    override fun Content() {
        val scheme = colorScheme
        if (scheme == null) Island() else MaterialTheme(colorScheme = scheme) { Island() }
    }

    @Composable
    private fun Island() {
        val entries = items.map { BottomNavEntry(it.id, it.label, painterResource(it.icon)) }
        val tap: (BottomNavEntry) -> Unit = { if (it.id == selectedId) onReselect(it.id) else onSelect(it.id) }
        val modify = itemModifier
        val fixed = insets
        if (fixed == null) {
            BottomNavIsland(entries, selectedId, tap, collapsed = collapsed, itemModifier = { modify(it.id) })
        } else {
            BottomNavIsland(entries, selectedId, tap, collapsed = collapsed, insets = fixed, itemModifier = { modify(it.id) })
        }
    }
}

/**
 * THE collapse rule of the fleet, one declaration (#673). A downward delta collapses the island to
 * icons, an upward one restores the labels, and no movement changes nothing. Both drivers call
 * this: [BottomNavCollapse] with its nested-scroll delta in a Compose shell, and
 * [BottomNavIslandView.collapseOnScrollIn] with its measured scroll delta in a View shell. A
 * second copy of this `if` is how the two host styles would end up collapsing on different rules.
 */
internal fun collapseFor(collapsed: Boolean, delta: Float): Boolean = when {
    delta > 0f -> true
    delta < 0f -> false
    else -> collapsed
}

/**
 * How far [root]'s subtree is scrolled, in pixels, as the sum of every view's own scroll offset.
 * A sum rather than a search: which descendant scrolls changes every time the host swaps a
 * fragment, and summing needs no knowledge of that. Only views that actually scroll contribute,
 * so an unscrolled tree reads 0 and a delta of 0 leaves the bar where it is.
 *
 * This is the one place the driver's RecyclerView limitation lives — see
 * [BottomNavIslandView.collapseOnScrollIn].
 */
internal fun scrolledOffset(root: View): Int {
    var total = root.scrollY
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) total += scrolledOffset(root.getChildAt(i))
    }
    return total
}

/** The island's test tags, for a HOST's tests that measure the island it configured. */
public object BottomNavTags {
    public const val ISLAND: String = TAG_ISLAND
    public fun item(id: String): String = itemTag(id)
    public fun icon(id: String): String = iconTag(id)
    public fun label(id: String): String = labelTag(id)
}
