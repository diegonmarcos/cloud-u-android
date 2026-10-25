package com.diegonmarcos.superapp.bottomnav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * The ONE scroll connection of an app's chrome (#1157): scrolling the page down hides the first row and compacts the second
 * row and the bottom nav, scrolling up restores all three. The shared scaffolds ([NavChromeScroll]) provide it; the bars read it.
 */
public val LocalNavChromeCollapse: ProvidableCompositionLocal<BottomNavCollapse?> =
    staticCompositionLocalOf { null }

/**
 * Owns the chrome's scroll connection unless an outer host already does: [content] gets it, and everything inside (the
 * first row, the second row, the bottom nav, and any scrollable page) is wired to the same one.
 */
@Composable
public fun NavChromeScroll(modifier: Modifier = Modifier, content: @Composable (BottomNavCollapse) -> Unit) {
    val inherited = LocalNavChromeCollapse.current
    if (inherited != null) { content(inherited); return }
    val own = rememberBottomNavCollapse()
    CompositionLocalProvider(LocalNavChromeCollapse provides own) {
        Box(modifier.fillMaxSize().nestedScroll(own)) { content(own) }
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
