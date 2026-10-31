package com.diegonmarcos.cgomniverse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "app_shell"
    const val PAGE = "app_page"
    const val AVATAR = "avatar"
    const val AVATAR_SCENE = "avatar_scene"
    const val AVATAR_RESET = "avatar_reset"
    const val STORE = "store"
    const val STORE_COUNT = "store_count"
    const val GALAXY = "galaxy"
    const val GALAXY_LOADED = "galaxy_loaded"
    const val GALAXY_ENTER = "galaxy_enter"
    const val WORLD = "world"
    const val WORLD_WEB = "world_web"
    const val WORLD_CLOSE = "world_close"
    const val WORLD_RELOAD = "world_reload"
    const val WORLD_DESKTOP = "world_desktop"
    const val WORLD_GPU = "world_gpu"
    const val WORLD_GPU_CHROME = "world_gpu_chrome"
    const val WORLD_BROWSER = "world_browser"
    const val WORLD_VR = "world_vr"
    const val WORLD_FALLBACK = "world_fallback"
    const val WORLD_FALLBACK_BROWSER = "world_fallback_browser"
    const val WORLD_OFFLINE = "world_offline"
    const val WORLD_OFFLINE_BROWSER = "world_offline_browser"
    const val WORLD_OFFLINE_WAYBACK = "world_offline_wayback"
    fun statusBadge(id: String): String = "status_badge:$id"
    fun entry(title: String): String = "entry:$title"
    fun avatarGroup(id: String): String = "avatar_group:$id"
    fun avatarOption(group: String, id: String): String = "avatar_option:$group:$id"
    fun chip(group: String, chip: String): String = "chip:$group:$chip"
    fun world(id: String): String = "world:$id"
    fun openGalaxy(id: String): String = "open_galaxy:$id"
    fun openBrowser(id: String): String = "open_browser:$id"
    fun galaxyWorld(id: String): String = "galaxy_world:$id"
}

/**
 * The shell: the shared first row (Profile at the top right opens Cloud Account, the gear opens the About section), the shared
 * bottom nav, the shared page gradient and the shared scroll collapse, all from libs (#1154, #1157). Which sections exist is
 * build.json::ui, read through [Decl.nav]; what a section's page shows is ui.stacks, drawn by [SectionPage]. A world opened from the
 * Store or Galaxy is drawn full screen over the shell ([WorldView]) and stays Galaxy's loaded world after it is closed.
 */
@Composable
fun AppShell() {
    val nav = Decl.nav
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_ABOUT) }
    val sec = nav.section(section)
    // the world Galaxy has loaded (kept while the user browses the other pages) and whether it fills the screen now
    var worldId by rememberSaveable { mutableStateOf<String?>(null) }
    var inWorld by rememberSaveable { mutableStateOf(false) }
    val world = worldId?.let { id -> Decl.worlds.firstOrNull { it.id == id } }
    val enter: (World) -> Unit = { w -> worldId = w.id; section = Decl.SECTION_GALAXY; inWorld = true }
    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, null) }.orEmpty(),
            onNavigate = { e -> section = e.sectionId },
            onAccount = rememberAccountLauncher(),
            onConfigs = { section = Decl.SECTION_ABOUT },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = nav.islandEntries(),
                selectedId = section,
                onSelect = { section = it.id },
                modifier = Modifier.testTag(Tags.SHELL),
            ) {
                SectionPage(section, world, enter)
            }
        }
        if (inWorld && world != null) WorldView(world, onClose = { inWorld = false })
    }
}
