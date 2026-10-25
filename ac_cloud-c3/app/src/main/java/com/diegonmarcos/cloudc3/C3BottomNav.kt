package com.diegonmarcos.cloudc3

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom

/**
 * #648 cloud-c3's bottom nav: libs:bottomnav's island fed from build.json::ui.bottom_nav (#868, through NavDecl).
 *
 * The five items come from the ONE tab declaration, in declared order, so reordering that
 * array reorders the bar and nothing else. An icon name in the declaration is a libs:ui-icons catalog
 * entry (#1160), the same for every app; a name the catalog lacks fails the nav-icons guard.
 *
 * Every tap is a DESTINATION here. Unlike cloud-mail's table and cloud-me's sections, no
 * cloud-c3 tab launches another app: the three sibling APKs are rows INSIDE the Apps tab,
 * not tabs of their own, so the pill always moves and never has to stay behind.
 *
 * 2026-10-16 owner decision: all apps must be Compose — the shell draws the island as the
 * [NavBarBottom] composable (libs:ui-nav-bars, #1154), the same one the browser, me and wallet shells use.
 */
object C3BottomNav {

    /** The tab id the pill sits on: only a declared tab lights an item. */
    fun pillFor(tabId: String?): String? = tabId?.takeIf { id -> Declarations.tabs.any { it.id == id } }

    /**
     * The island. [onOpen] shows a tab's page in the shell's one container. The pill always moves,
     * because every tab is a destination; re-tapping the tab already shown rebuilds nothing.
     */
    @Composable
    fun Island(selectedId: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
        val entries = Declarations.nav.islandEntries()
        NavBarBottom(
            entries = entries,
            selectedId = pillFor(selectedId),
            onSelect = { e -> if (e.id != selectedId) onOpen(e.id) },
            dark = true,
            modifier = modifier,
        )
    }
}
