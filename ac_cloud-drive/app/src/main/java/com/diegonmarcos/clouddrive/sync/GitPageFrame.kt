package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.superapp.perms.PermsPromptAbove

/**
 * THE FRAME OF THE GIT PAGE's scroller: pull to refresh around ONE bounded LazyColumn (no scroller is ever nested in it).
 *
 * The bottom bar belongs to the shared scaffold (libs:ui-nav-bars' NavBarBottomHost): it clears the system bar itself and
 * CONSUMES that inset for the content above it. This frame therefore reads the inset the same way every Compose screen does,
 * through the window-insets modifier: whatever the scaffold has already consumed costs nothing here, whatever it has not
 * consumed (the bar overlaying the content, the keyboard) is cleared here, so the last row can always scroll fully above
 * the bar, in every scroll state. The trailing breathing room is the page's own metric. GitPageFrameTest pins it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitPageFrame(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), content: LazyListScope.() -> Unit) {
    // #1358 the git engine's missing All files access is said ONCE, above every row (EngineGrantBanner draws nothing otherwise);
    // #1372 it sits ABOVE the pull-to-refresh box, so the refresh spinner never draws over it.
    PermsPromptAbove(prompt = { EngineGrantBanner() }, modifier = modifier.fillMaxSize()) {
        // #1356 the refresh is a thin bar along the top edge, never a floating disc over the search field or the top nav.
        PullToRefreshBox(
            isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize(),
            indicator = {
                if (refreshing) androidx.compose.material3.LinearProgressIndicator(
                    Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter).testTag(DriveTags.SYNC_GIT_REFRESH_BAR),
                )
            },
        ) {
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).testTag(DriveTags.SYNC_GIT_LIST),
                state = state,
                contentPadding = PaddingValues(bottom = DriveMetrics.sectionInset),
                content = content,
            )
        }
    }
}
