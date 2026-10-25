package com.diegonmarcos.superapp.navbartop1

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1157 per-app guard: every app on the shared nav bars draws its chrome with the shared scaffold (NavBarTopFirstRowScaffold,
 * which owns the ONE scroll connection) AND the shared bottom nav, and none hides the first row from its own state. A page
 * scrolled inside that scaffold then hides the first row and compacts the second row and the bottom nav; scrolling up restores
 * them (NavChromeScrollTest holds the behaviour). A missing app directory FAILS.
 */
class ChromeScrollGuardTest {
    private val apps = listOf(
        "ac_cloud-mail", "ac_cloud-me", "ac_cloud-drive", "ac_cloud-nav", "ac_cloud-account", "ac_cloud-store", "ac_cloud-c3", "ac_cloud-tasks", "ac_cloud-contacts", "ac_cloud-news", "ac_cloud-writer", "ac_cloud-search", "ac_cloud-browser", "ac_cloud-dialer", "ac_cloud-superintel", "ac_cloud-notes", "ac_cloud-tools-small-llms",
    )

    private fun sources(app: String): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            // Cloud Notes is a Capacitor project: its Android app sits under the vendored tree.
            val root = listOf("$app/app/src/main", "$app/packages/frontend/apps/android/App/app/src/main").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (root != null) return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            dir = dir.parentFile
        }
        throw AssertionError("$app/app/src/main not found above ${File("").absolutePath}")
    }

    @Test fun `every app draws its chrome with the shared scaffold and the shared bottom nav`() {
        for (app in apps) {
            val text = sources(app).joinToString("\n") { it.readText() }
            assertTrue("$app has no NavBarTopFirstRowScaffold (the scroll connection's owner)", "NavBarTopFirstRowScaffold(" in text)
            assertTrue("$app has no shared bottom nav", "NavBarBottomHost(" in text || "NavBarBottom(" in text)
        }
    }

    @Test fun `no app hides the first row from its own state`() {
        val own = Regex("""barVisible\s*=\s*[^\n]*collapse""", RegexOption.IGNORE_CASE)
        for (app in apps) {
            val hits = sources(app).filter { own.containsMatchIn(it.readText()) }.map { it.name }
            assertEquals("$app hand-rolls the first row's visibility", emptyList<String>(), hits)
        }
    }

    /** The scroll surfaces a Compose nestedScroll cannot see (a WebView, a Fragment page, a View list) and the token proving each app feeds them to the ONE collapse. */
    private val webScrollers = mapOf(
        "ac_cloud-browser" to "onScrollDelta(",
        "ac_cloud-contacts" to "onScrollDelta(",
        "ac_cloud-news" to "onScrollDelta(",
        "ac_cloud-dialer" to "ChromeScrollHooks.install(",
        "ac_cloud-tasks" to "ChromeWebScroll.attach(",
        "ac_cloud-me" to "ChromeWebScroll.attach(",
        "ac_cloud-notes" to "ChromeWebScroll.attach(",
    )

    /** Apps whose WebViews are secondary screens (own Activity, dialog, editor or print helper), never the page under the chrome. */
    private val secondaryWebViewsOnly = setOf("ac_cloud-nav", "ac_cloud-mail", "ac_cloud-calc", "ac_cloud-drive")

    @Test fun `every WebView page under the chrome feeds the one collapse connection`() {
        val webView = Regex("""\bWebView\(""")
        for (app in apps) {
            val text = sources(app).joinToString("\n") { it.readText() }
            val token = webScrollers[app]
            if (token != null) assertTrue("$app hosts a scrolling page but never feeds it to the chrome collapse ($token)", token in text)
            else if (app !in secondaryWebViewsOnly) assertTrue("$app builds a WebView but is not in webScrollers: wire it to BottomNavCollapse.onScrollDelta (ChromeWebScroll) and list it", !webView.containsMatchIn(text))
        }
    }

    @Test fun `fragment pages hosted in a Compose shell hand their scroll to the chrome through the View parents`() {
        // A Fragment's ComposeView is its own composition: LocalNavChromeCollapse is empty there and nested scrolls stop at the
        // View boundary (the Store/Nav/C3 regression). KitPage is the one place every KitComposeFragment page passes through.
        val kit = File("ab_cloud-libs-shared/libs/ui-kit/src/main/kotlin/com/diegonmarcos/superapp/uikit/KitInterop.kt").let { f ->
            var dir: File? = File("").absoluteFile
            var found: File? = null
            while (dir != null && found == null) { File(dir, f.path).takeIf { it.isFile }?.let { found = it }; dir = dir.parentFile }
            found ?: throw AssertionError("KitInterop.kt not found")
        }.readText()
        assertTrue("KitPage no longer wires fragment pages to the View parents' nested scroll", "rememberNestedScrollInteropConnection()" in kit && "fun KitPage" in kit)
        assertTrue("KitPage must wrap every page, with or without a host sink", !Regex("""sink == null\) content\(\)""").containsMatchIn(kit))
        // And the bridge into the host composition must stay: the hosting AndroidFragment/FragmentContainerView sits under the scaffold.
        for (app in listOf("ac_cloud-store", "ac_cloud-nav", "ac_cloud-c3")) {
            val text = sources(app).joinToString("\n") { it.readText() }
            assertTrue("$app hosts fragments outside the shared scaffold", "AndroidFragment<" in text || "FragmentContainerView(" in text)
            assertTrue("$app has no shared scaffold above its fragments", "NavBarTopFirstRowScaffold(" in text)
        }
    }

    @Test fun `ChromeWebScroll ends in the collapse connection`() {
        var dir: File? = File("").absoluteFile
        var f: File? = null
        while (dir != null && f == null) { File(dir, "ab_cloud-libs-shared/libs/bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/ChromeWebScroll.kt").takeIf { it.isFile }?.let { f = it }; dir = dir.parentFile }
        val text = (f ?: throw AssertionError("ChromeWebScroll.kt not found")).readText()
        assertTrue("ChromeWebScroll no longer feeds onScrollDelta", "setOnScrollChangeListener" in text && "onScrollDelta(" in text && "addJavascriptInterface" in text)
    }

    /** Every file of an app's main sources, with its path relative to the app's main/java root. */
    private fun driveFiles(): List<File> = sources("ac_cloud-drive")

    @Test fun `every Cloud Drive page is routed through the one shell and its scroll container rides the shared connection`() {
        val files = driveFiles()
        val main = files.first { it.name == "MainActivity.kt" }.readText()
        val shell = files.first { it.name == "DriveShell.kt" }.readText()
        // The shell: NavBarBottomHost inside NavBarTopFirstRowScaffold, so the ONE connection (LocalNavChromeCollapse) reaches every page.
        assertTrue("DriveShell lost the shared scaffold", "NavBarTopFirstRowScaffold(" in shell && "NavBarBottomHost(" in shell)
        assertTrue("MainActivity must draw its pages through DriveShell", "DriveShell(" in main)
        // Each routed page: `"id" -> SomeScreen(...)` in MainActivity. Its package holds a scrollable Compose container.
        val routes = Regex("""^\s*"([a-z_]+)"\s*->\s*([A-Z]\w+)\(""", RegexOption.MULTILINE).findAll(main).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("MainActivity routes no page?", routes.size >= 8)
        // A page whose scrolling lives in a shared lib (libs:ai's chat page) is checked by that lib; every other page must own a container here.
        val libOwned = setOf("ai")
        for ((id, screen) in routes.filter { it.first !in libOwned }) {
            val def = files.firstOrNull { Regex("""fun\s+$screen\(""").containsMatchIn(it.readText()) } ?: throw AssertionError("page $id: no definition of $screen")
            val pkg = files.filter { it.parentFile == def.parentFile }.joinToString("\n") { it.readText() }
            assertTrue("page $id ($screen) has no scrollable Compose container (LazyColumn / verticalScroll / pager) for the chrome to follow", Regex("""LazyColumn|LazyVerticalGrid|verticalScroll\(|HorizontalPager|VerticalPager""").containsMatchIn(pkg))
        }
        // No page owns a second connection or switches the chrome's state by hand: that is what detaches it from the shell.
        val own = Regex("""rememberBottomNavCollapse\(|NavChromeScroll\s*[({]|LocalNavChromeCollapse\s+provides|BottomNavCollapse\(\)""")
        val bad = files.filter { it.name != "DriveShell.kt" && own.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a Drive page builds its own chrome connection", emptyList<String>(), bad)
    }
}
