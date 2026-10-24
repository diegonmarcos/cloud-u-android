package com.diegonmarcos.cloudme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.bottomnav.AppTopBarScaffold
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse

/**
 * The shell Compose screen: the fleet's top bar over [banner] and [MeBody] (content above the island).
 *
 * Scrolling the page DOWN hides the top row and collapses the island's labels to icons; scrolling UP brings both
 * back (#1150). One nested-scroll connection ([rememberBottomNavCollapse], carried by MeBody's content slot) drives
 * both, so they can never disagree. The drawer opens by tapping the hamburger only.
 */
@Composable
fun MeShell(
    title: String,
    selectedSectionId: String?,
    onOpen: (String) -> Unit,
    onTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val collapse = rememberBottomNavCollapse()
    val settings = Sections.settings()
    AppTopBarScaffold(
        decl = MeTopBar.decl,
        title = title,
        onAccount = { settings.firstOrNull()?.let { onOpen(it.id) } },
        onConfigs = { settings.lastOrNull()?.let { onOpen(it.id) } },
        modifier = modifier,
        onAi = { onTarget(MeTopBar.AI_TARGET) },
        barVisible = !collapse.collapsed,
    ) {
        MeDrawer(onOpen)
        Column(Modifier.fillMaxSize()) {
            banner()
            MeBody(selectedSectionId, onOpen, onTarget, Modifier.weight(1f), collapse = collapse, content = content)
        }
    }
}
