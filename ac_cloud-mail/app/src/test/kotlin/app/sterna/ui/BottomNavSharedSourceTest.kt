package app.sterna.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument as [app.sterna.ui.home.HomeDestinationWiringTest],
 * for the same reason: nothing in this module can run a Composable or a NavHost, so the wiring
 * between SternaApp's NavHost and the shared bottom-navigation island is a link nothing behavioural
 * can reach. Cutting that link leaves every other test in the repository green (#493's dominant
 * risk), and this file stands over it.
 *
 * The bar is the ONE shared declaration in `ab_cloud-libs-shared/libs/bottomnav`
 * (com.diegonmarcos.superapp.bottomnav.*), consumed BY REFERENCE. This app must render THAT bar and
 * must not carry a private nav copy of its own — a fifth copy is the exact defect #228 / #261 were
 * raised for. Both directions are asserted so a regression to a private copy fails loudly.
 */
class BottomNavSharedSourceTest {

    @Test
    fun `SternaApp renders the SHARED bottom nav, not an app-local copy`() {
        val sternaApp = codeLines("app/src/main/kotlin/app/sterna/ui/SternaApp.kt")
        assertTrue(
            "SternaApp must draw the bottom nav from the shared module " +
                "(com.diegonmarcos.superapp.bottomnav.BottomNavBar). Pointed back at an " +
                "app-local `app.sterna.ui.navigation.BottomNavBar`, this app silently reverts to " +
                "a fifth private copy of the nav (#228/#261 defect).",
            sternaApp.contains("import com.diegonmarcos.superapp.bottomnav.BottomNavBar"),
        )
        // The bar must actually be drawn on the destinations it owns, not merely imported.
        assertTrue(
            "SternaApp imports the shared BottomNavBar but no longer renders it on a destination " +
                "it owns (inbox / home / rss). An import that never draws is a nav that is wired-nothing.",
            listOf("\"inbox\"", "\"home\"", "\"rss\"").all { route ->
                sternaApp.any { it.startsWith("BottomNavBar(nav = nav, currentRoute = $route") }
            },
        )
    }

    @Test
    fun `no private bottom-nav copy remains in this app tree`() {
        assertFalse(
            "an app-local `app.sterna.ui.navigation.BottomNavBar.kt` reappeared. The nav must " +
                "live ONCE in ab_cloud-libs-shared/libs/bottomnav and be shared by reference — a " +
                "per-app copy is the exact #228/#261 defect this ticket exists to prevent.",
            File(root, "app/src/main/kotlin/app/sterna/ui/navigation/BottomNavBar.kt").isFile,
        )
        assertFalse(
            "an app-local `app.sterna.ui.navigation.BottomNav.kt` (the item declaration) " +
                "reappeared. The items/icons/labels/contract are the ONE declaration in the " +
                "shared module; a second copy here can drift from it.",
            File(root, "app/src/main/kotlin/app/sterna/ui/navigation/BottomNav.kt").isFile,
        )
    }

    // ── instrument (copied from HomeDestinationWiringTest) ───────────────────────────────

    /** [relPath]'s code lines: trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(relPath: String): List<String> =
        File(root, relPath).readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private companion object {
        private const val STERNA_APP_HINT = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, STERNA_APP_HINT).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}