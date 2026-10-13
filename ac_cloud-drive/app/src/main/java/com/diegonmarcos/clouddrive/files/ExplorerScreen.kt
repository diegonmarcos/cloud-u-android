package com.diegonmarcos.clouddrive.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.topIslandInsets
import com.diegonmarcos.superapp.bottomnav.PageTabs

/**
 * EXPLORER (was Files): two sub-pages from ui.sections[explorer].pages, drawn by the fleet's
 * PageTabs. Local is the file manager ([FilesScreen]); Cloud is the former Volumes tab, moved here
 * whole and renamed, its four classes now Cloud's own sub-pages. This file routes; it draws nothing
 * of either page. The `when` is the ONE place Kotlin names the two page ids.
 *
 * [page] is a declared page id another screen asked for (Home's Volumes card, Data's "show in
 * Explorer"); it is applied once and [onPageConsumed] clears it.
 */
@Composable
fun ExplorerScreen(
    page: String?,
    onPageConsumed: () -> Unit,
    local: @Composable () -> Unit,
    cloud: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.navPages(Declarations.SECTION_EXPLORER)
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    Column(modifier.fillMaxSize().testTag(DriveTags.EXPLORER)) {
        PageTabs(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gap).testTag(DriveTags.EXPLORER_STRIP))
        // The strip already cleared the status bar; the page's own top island must not clear it twice.
        Box(Modifier.weight(1f).consumeWindowInsets(topIslandInsets()).testTag(DriveTags.explorerPage(current))) {
            when (current) {
                "local" -> local()
                "cloud" -> cloud()
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
    }
}
