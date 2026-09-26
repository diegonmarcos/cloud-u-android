package com.diegonmarcos.clouddrive.ui

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
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.bottomNavInsets
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse

/**
 * #579 the shell (cloud-drive-redesign.md §2): the tab content above the fleet's
 * bottom-nav island. The tabs are build.json::ui.tabs — id, label, icon — in declared
 * order; the selected one is saved across process death; the island collapses to icons
 * on scroll and reads its own bottom inset while the content consumes it once (the
 * libs:bottomnav BottomNavBar pattern, with this app's entries). Re-tapping the
 * selected tab is handed to the screen as [onReselect].
 *
 * The dispatch on a tab id is the ONE place Kotlin names the ids; test-drive-shell.sh
 * diffs it against the declaration in both directions.
 *
 * #603 [select] is a DECLARED tab id a screen asked for (an Apps tile's route): the
 * shell selects it once and calls [onSelected] so the request is not re-applied on
 * every recomposition. An id that is not declared is ignored, never a blank shell.
 */
@Composable
fun DriveShell(select: String? = null, onSelected: () -> Unit = {}, content: @Composable (tabId: String, reselectTick: Int) -> Unit) {
    val tabs = Declarations.tabs
    var selected by rememberSaveable { mutableStateOf(Declarations.defaultTab.takeIf { d -> tabs.any { it.id == d } } ?: tabs.firstOrNull()?.id ?: "") }
    var reselectTick by rememberSaveable { mutableStateOf(0) }
    val collapse = rememberBottomNavCollapse()
    val insets = bottomNavInsets()
    val entries = tabs.map { BottomNavEntry(it.id, it.label, IconCatalog.painter(it.icon)) }
    LaunchedEffect(select) {
        if (select != null && tabs.any { it.id == select }) selected = select
        if (select != null) onSelected()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(DriveTags.SHELL)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))
                .nestedScroll(collapse)
                .testTag(DriveTags.CONTENT),
        ) {
            AnimatedContent(targetState = selected, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "drive_tab") { id ->
                Box(Modifier.fillMaxSize().testTag(DriveTags.tab(id))) {
                    if (tabs.any { it.id == id }) content(id, reselectTick)
                    else EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
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
