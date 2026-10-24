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
        "ac_cloud-mail", "ac_cloud-me", "ac_cloud-drive", "ac_cloud-nav", "ac_cloud-account", "ac_cloud-store", "ac_cloud-c3", "ac_cloud-tasks", "ac_cloud-contacts", "ac_cloud-news", "ac_cloud-writer",
    )

    private fun sources(app: String): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val root = File(dir, "$app/app/src/main")
            if (root.isDirectory) return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
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
}
