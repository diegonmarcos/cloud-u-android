package com.diegonmarcos.clouddrive.configs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.clouddrive.backups.BackupsScreen
import com.diegonmarcos.clouddrive.backups.MirrorRunner
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.ToolbarIsland

/**
 * #609 CONFIGS: ONE island whose strip is build.json::ui.configs.pages — Backups (moved
 * in from its own tab at #603), General (#579's Configs cards) and Others. Git, Rclone
 * and Mounts moved OUT to the new Sync tab (SyncScreen). The strip is tabs inside the
 * tab, not a stack — back leaves the app. Every one of the three is a screen that
 * already existed: this file routes, it does not draw a card.
 *
 * The dispatch on a page id is the ONE place Kotlin names them; test-drive-shell.sh diffs
 * it against the declaration in both directions.
 *
 * [page] is a DECLARED page id another screen asked for (an Apps tile's route); it is
 * applied once and [onPageConsumed] clears the request.
 */
@Composable
fun ConfigsScreen(
    mirrors: MirrorRunner,
    prefs: DrivePrefs,
    actions: DriveActions,
    hasAccess: Boolean,
    rcloneVersion: String?,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.configs.pages
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }

    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = stringResource(R.string.configs_title), subtitle = pages.firstOrNull { it.id == current }?.label)
        // #868 the strip is libs:bottomnav's PageTabs over ui.sections[configs].pages.
        PageTabs(pages = Declarations.navPages("configs"), selectedId = current, onSelect = { current = it.id }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.CONFIGS_STRIP), underTopChrome = false)
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "configs_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "backups" -> BackupsScreen(mirrors, prefs)
                "general" -> GeneralPage(prefs, actions, hasAccess)
                "others" -> OthersPage(prefs, actions, rcloneVersion)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
    }
}
