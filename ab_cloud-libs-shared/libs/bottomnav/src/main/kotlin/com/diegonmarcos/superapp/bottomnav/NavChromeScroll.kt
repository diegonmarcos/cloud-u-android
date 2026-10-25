package com.diegonmarcos.superapp.bottomnav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The ONE scroll connection of an app's chrome (#1157): scrolling the page down hides the first row and compacts the second
 * row and the bottom nav, scrolling up restores all three. The shared scaffolds ([NavChromeScroll]) provide it; the bars read it.
 */
public val LocalNavChromeCollapse: ProvidableCompositionLocal<BottomNavCollapse?> =
    staticCompositionLocalOf { null }

/**
 * The View-tag key the chrome's scroll connection is published under on the window's root View. The SAME literal is in
 * libs:ui-kit's KitInterop.kt (ChromeScrollGuardTest holds both equal): an application-specific id (package byte >= 2).
 */
public const val CHROME_SINK_TAG: Int = 0x7e001157

/**
 * Owns the chrome's scroll connection unless an outer host already does: [content] gets it, and everything inside (the
 * first row, the second row, the bottom nav, and any scrollable page) is wired to the same one.
 */
@Composable
public fun NavChromeScroll(modifier: Modifier = Modifier, content: @Composable (BottomNavCollapse) -> Unit) {
    val inherited = LocalNavChromeCollapse.current
    if (inherited != null) { content(inherited); return }
    val own = rememberBottomNavCollapse()
    // A Fragment's ComposeView is its own composition and the View-parent nested-scroll hand-off did not reach this one on the
    // emulator, so the connection is ALSO published on the window's root View under CHROME_SINK_TAG; ui-kit's KitPage (which
    // cannot depend on this lib) reads it from there and reports its scrolls straight to it.
    val root = androidx.compose.ui.platform.LocalView.current.rootView
    androidx.compose.runtime.DisposableEffect(root, own) {
        root.setTag(CHROME_SINK_TAG, own)
        onDispose { if (root.getTag(CHROME_SINK_TAG) === own) root.setTag(CHROME_SINK_TAG, null) }
    }
    CompositionLocalProvider(LocalNavChromeCollapse provides own) {
        Box(modifier.fillMaxSize().chromeScroll(own)) { content(own) }
    }
}

/**
 * Slides a bar away while the chrome is HIDDEN (a downward drag in progress) and back otherwise; the bar leaves the tree
 * and its row/column gives its room to the page. [hidden] defaults to the chrome's shared state.
 */
@Composable
public fun ChromeBarVisibility(
    modifier: Modifier = Modifier,
    hidden: Boolean = LocalNavChromeCollapse.current?.hidden ?: false,
    content: @Composable () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = !hidden,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) { content() }
}

/**
 * The chrome's scroll wiring for the box that holds the page: the nested-scroll connection, plus a watcher of the raw touch that
 * consumes nothing. The watcher is what makes the machine independent of how a page scrolls (a Fragment, a WebView, a View list):
 * the finger LIFTING is the release (HIDDEN becomes SHRUNK), and a finger dragging DOWN the screen is a scroll up, which restores
 * DEFAULT even when the page has nothing left to scroll and so reports no delta.
 */
public fun Modifier.chromeScroll(collapse: BottomNavCollapse): Modifier =
    this.nestedScroll(collapse).pointerInput(collapse) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            var down = false
            var startX = 0f
            var startY = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when {
                    event.changes.none { it.pressed } -> { if (down) { collapse.onFinger(false); collapse.onRelease() }; down = false }
                    !down -> { down = true; collapse.onFinger(true); startX = change.position.x; startY = change.position.y }
                    else -> {
                        val dy = change.position.y - startY
                        val dx = change.position.x - startX
                        if (dy > slop && dy > kotlin.math.abs(dx)) collapse.restore()
                    }
                }
            }
        }
    }
