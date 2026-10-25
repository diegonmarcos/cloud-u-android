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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags

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
fun GitPageFrame(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).testTag(DriveTags.SYNC_GIT_LIST),
            contentPadding = PaddingValues(bottom = DriveMetrics.sectionInset),
            content = content,
        )
    }
}
