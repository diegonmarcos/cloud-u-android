package com.diegonmarcos.cloudc3

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem

/**
 * #648 cloud-c3's bottom nav: libs:bottomnav's island fed from build.json::ui.bottom_nav (#868, through NavDecl).
 *
 * The five items come from the ONE tab declaration, in declared order, so reordering that
 * array reorders the bar and nothing else. An icon name in the declaration is resolved to a
 * DRAWABLE by name — the cloud-me contract — which is why each declared icon has a drawable
 * of that exact name in res/drawable and why test-c3-shell.sh asserts the pair exists in
 * both directions: a name with no drawable resolves to 0 and draws a blank square.
 *
 * Every tap is a DESTINATION here. Unlike cloud-mail's table and cloud-me's sections, no
 * cloud-c3 tab launches another app: the three sibling APKs are rows INSIDE the Apps tab,
 * not tabs of their own, so the pill always moves and never has to stay behind.
 *
 * 2026-10-16 owner decision: all apps must be Compose — the shell draws the island as the
 * [BottomNavIsland] composable, the same one the browser, me and wallet shells use.
 */
object C3BottomNav {

    /**
     * The declared tabs as island items. A declared icon that names no drawable would
     * resolve to 0 and draw nothing, so it is reported rather than silently blank —
     * the tester fails the build on it, and this keeps the runtime honest too.
     */
    fun items(ctx: Context): List<BottomNavViewItem> = Declarations.nav.viewItems { name ->
        @Suppress("DiscouragedApi")
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
    }

    /** True when every declared tab icon resolves to a real drawable in this APK. */
    fun iconsResolve(ctx: Context): Boolean = items(ctx).all { it.icon != 0 }

    /** The tab id the pill sits on: only a declared tab lights an item. */
    fun pillFor(tabId: String?): String? = tabId?.takeIf { id -> Declarations.tabs.any { it.id == id } }

    /**
     * The island. [onOpen] shows a tab's page in the shell's one container. The pill always moves,
     * because every tab is a destination; re-tapping the tab already shown rebuilds nothing.
     */
    @Composable
    fun Island(selectedId: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
        val ctx = LocalContext.current
        val entries = items(ctx).map { BottomNavEntry(it.id, it.label, painterResource(it.icon)) }
        BottomNavIsland(
            entries = entries,
            selectedId = pillFor(selectedId),
            onSelect = { e -> if (e.id != selectedId) onOpen(e.id) },
            modifier = modifier,
        )
    }
}
