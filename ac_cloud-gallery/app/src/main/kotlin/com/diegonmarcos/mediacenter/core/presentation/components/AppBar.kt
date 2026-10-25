/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.core.presentation.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.diegonmarcos.mediacenter.core.LocalScrollToTop
import com.diegonmarcos.mediacenter.core.ScrollToTopController
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen
import com.diegonmarcos.mediacenter.shell.GalleryChrome
import com.diegonmarcos.mediacenter.shell.GalleryPageState
import com.diegonmarcos.mediacenter.shell.GalleryTab

/**
 * The app's chrome. A bottom-nav tab (Screenshots, Downloads, Camera, Home, Timeline, Maps, Objects)
 * is drawn inside the shared nav bars ([GalleryChrome]: first row, second row, bottom nav, page
 * background, one scroll-collapse); the bars already clear the system insets, so a tab page is
 * given no padding. Every other screen (viewer, settings, an album) is drawn bare with the
 * Scaffold's insets, as before.
 *
 * [content] is composed exactly once and moved between the two hosts, so crossing from a tab
 * to a sub-screen never rebuilds the NavHost.
 */
@Stable
@Composable
fun AppBarContainer(
    navController: NavController,
    bottomBarState: Boolean,
    paddingValues: PaddingValues,
    isScrolling: Boolean,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val tab = GalleryTab.ofRoute(backStackEntry?.destination?.route)
    val scrollToTop = LocalScrollToTop.current
    val pages = remember { GalleryPageState() }
    val latest by rememberUpdatedState(content)
    val body = remember { movableContentOf<PaddingValues> { padding -> latest(padding) } }
    if (tab == null) {
        body(paddingValues)
    } else {
        GalleryChrome(
            tab = tab,
            pages = pages,
            onSelectTab = { onTabClick(navController, it, scrollToTop) },
            onConfigs = { navController.navigate(Screen.SettingsScreen()) { launchSingleTop = true } },
            onSearch = { navController.navigate(Screen.SearchScreen()) { launchSingleTop = true } },
        ) {
            body(NO_PADDING)
        }
    }
}

private val NO_PADDING = PaddingValues(0.dp)

private fun onTabClick(
    navController: NavController,
    tab: GalleryTab,
    scrollToTop: ScrollToTopController,
) {
    // Re-tapping the already-selected tab scrolls that screen back to the top (#1039). Otherwise open the tab.
    if (tab.route == navController.currentDestination?.route) {
        scrollToTop.requestScrollToTop(tab.route)
    } else {
        navController.navigate(tab.route) {
            // Pop up to the start destination so tab switches do not pile up on the back stack.
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}
