package com.diegonmarcos.cloudc3.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.bottomNavInsets
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse

/**
 * #648 the shell: the tab content above the fleet's bottom-nav island. The tabs are
 * build.json::ui.tabs — id, label, icon — in DECLARED ORDER, which is why reordering that
 * array is the whole change needed to reorder the nav. The selected tab is saved across
 * process death; the island collapses to icons on scroll and reads its own bottom inset
 * while the content consumes it once (the libs:bottomnav pattern, with this app's entries).
 * Re-tapping the selected tab is handed to the screen as a bumped tick.
 *
 * THE FOUR SHARED SYMBOLS are the reuse point of libs:bottomnav — [BottomNavEntry],
 * [BottomNavIsland], [bottomNavInsets], [rememberBottomNavCollapse]. This app deliberately
 * does NOT use that module's `bottomNavItems`: that list is cloud-mail's item table and
 * merely lives on the shared shelf, so consuming it here would couple this app's nav to
 * another shipping app's tabs.
 *
 * [C3Screens.Content] is the ONE place Kotlin names the tab ids; test/test-c3-shell.sh
 * diffs that dispatch against the declaration in BOTH directions, so a declared tab with
 * no screen and a screen with no declared tab are each a build failure.
 */
@Composable
fun C3Shell(content: @Composable (tabId: String, reselectTick: Int) -> Unit) {
    val tabs = Declarations.tabs
    var selected by rememberSaveable {
        mutableStateOf(
            Declarations.defaultTab.takeIf { d -> tabs.any { it.id == d } }
                ?: tabs.firstOrNull()?.id
                ?: "",
        )
    }
    var reselectTick by rememberSaveable { mutableStateOf(0) }
    val collapse = rememberBottomNavCollapse()
    val insets = bottomNavInsets()
    val entries = tabs.map { BottomNavEntry(it.id, it.label, IconCatalog.painter(it.icon)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(C3Tags.SHELL),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))
                .nestedScroll(collapse)
                .testTag(C3Tags.CONTENT),
        ) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "c3_tab",
            ) { id ->
                Box(Modifier.fillMaxSize().testTag(C3Tags.tab(id))) {
                    // An id the declaration does not carry renders the stated empty state,
                    // never a blank pane: a mismatch is VISIBLE at runtime as well as fatal
                    // in the tester.
                    if (tabs.any { it.id == id }) content(id, reselectTick)
                    else EmptyState(
                        icon = Declarations.iconDefault,
                        title = stringResource(R.string.chrome_unknown_tab),
                        detail = id,
                    )
                }
            }
        }
        BottomNavIsland(
            entries = entries,
            selectedId = selected,
            onSelect = { entry -> if (entry.id == selected) reselectTick++ else selected = entry.id },
            collapsed = collapse.collapsed,
            insets = insets,
        )
    }
}

/** The shell's test tags, for the Robolectric layout-tree test that measures it. */
object C3Tags {
    const val SHELL: String = "c3_shell"
    const val CONTENT: String = "c3_content"
    fun tab(id: String): String = "c3_tab_$id"
}
