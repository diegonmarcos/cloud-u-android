package com.diegonmarcos.cloudme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.bottomnav.BottomNavCollapse
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom

/**
 * Cloud Me's bottom nav: libs:nav-bar-bottom's bar (#1154, Cloud Search's coloured selection) fed from [Sections.bottom] (#531).
 *
 * The stock Material BottomNavigationView is gone; the bar is the fleet's one Compose island.
 * What stays Cloud Me's own is small: WHICH sections (the bar sections of build.json, in
 * `order`) and what a tap on a launch section does (it leaves the app and the pill stays on the
 * page you are still on). The island's look and its system-bar clearance are the lib's.
 *
 * 2026-10-16 owner decision: all apps must be Compose. [MeBody] is the shell's content-above-island
 * column, shared by MainActivity and MeBottomNavTest so the test measures the bar the app shows.
 */
object MeBottomNav {

    /** Test tag of the content slot [MeBody] lays out ABOVE the island. */
    const val CONTENT_TAG = "me:content"

    /** The section whose item the pill sits on: a bar section lights its item, an off-bar section
     *  (reached from the Settings entries, a tile or restored state) leaves none lit. */
    fun pillFor(sectionId: String?): String? =
        sectionId?.takeIf { id -> Sections.bottom().any { it.id == id } }
}

/**
 * The island over build.json's bar sections. [onOpen] shows a section inside the app, [onTarget]
 * launches a section's `target`. Opening moves the pill; launching does not, so the bar keeps
 * pointing at the page the user comes back to.
 */
@Composable
fun MeBottomBar(
    selectedSectionId: String?,
    onOpen: (String) -> Unit,
    onTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
) {
    val entries = Sections.bottom().map { navEntry(it.id, it.label, it.icon) }
    NavBarBottom(
        entries = entries,
        selectedId = MeBottomNav.pillFor(selectedSectionId),
        onSelect = { e ->
            val section = Sections.byId(e.id)
            when {
                section == null -> Unit
                section.target.isNotBlank() -> onTarget(section.target)
                else -> onOpen(e.id)
            }
        },
        dark = true,
        modifier = modifier,
        collapsed = collapsed,
    )
}

/**
 * The shell's body: [content] above the island, ending where the island's clearance begins so
 * nothing the page draws can hide under the bar. Scrolling the content collapses the island to
 * icons (#673) and scrolling back up restores the labels: the content slot carries the nested-scroll
 * connection, so any scrolling page inside it drives the bar.
 */
@Composable
fun MeBody(
    selectedSectionId: String?,
    onOpen: (String) -> Unit,
    onTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
    collapse: BottomNavCollapse = rememberBottomNavCollapse(),
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(collapse).testTag(MeBottomNav.CONTENT_TAG)) {
            content()
        }
        MeBottomBar(selectedSectionId, onOpen, onTarget, collapsed = collapse.collapsed)
    }
}
