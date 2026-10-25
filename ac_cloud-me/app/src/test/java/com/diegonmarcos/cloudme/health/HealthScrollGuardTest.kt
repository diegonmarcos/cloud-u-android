package com.diegonmarcos.cloudme.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structure guard (plain JUnit, reads the sources): a Health page is ONE vertical scroller, owned by HealthPage, so the shell's chrome
 * connection follows it; no page or component nests another vertical scroller (a LazyColumn, a second verticalScroll) inside it, and the
 * pages draw their icons from libs:ui-icons by name, never from a material icon of their own.
 */
class HealthScrollGuardTest {
    private fun dir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "app/src/main/java/com/diegonmarcos/cloudme/health")
            if (f.isDirectory) return d
            val g = File(d, "src/main/java/com/diegonmarcos/cloudme/health")
            if (g.isDirectory) return d.parentFile
            d = d.parentFile
        }
        throw AssertionError("ac_cloud-me sources not found")
    }

    private val root get() = dir()
    private val files get() = File(root, "app/src/main/java/com/diegonmarcos/cloudme/health").listFiles { f -> f.extension == "kt" }!!.toList()

    @Test fun `exactly one vertical scroller in the Health pages and it is HealthPage`() {
        val owners = files.filter { Regex("""verticalScroll\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("HealthPage.kt"), owners)
        val lazy = files.filter { Regex("""LazyColumn|LazyVerticalGrid|VerticalPager""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a lazy list nests a scroller inside the page", emptyList<String>(), lazy)
    }

    @Test fun `the page is routed from the section host and the chrome connection is the shell's`() {
        val section = File(root, "app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt").readText()
        assertTrue("SectionScreen does not host the healthpage block", "\"healthpage\" ->" in section && "HealthPage(" in section)
        val own = files.filter { Regex("""rememberBottomNavCollapse\(|NavChromeScroll\s*[({]|LocalNavChromeCollapse\s+provides""").containsMatchIn(it.readText()) }
        assertEquals("a Health page builds its own chrome connection", emptyList<String>(), own.map { it.name })
    }

    @Test fun `Health draws its icons by catalog name and holds no PDF renderer`() {
        val text = files.joinToString("\n") { it.readText() }
        assertTrue("Health uses a material icon directly", !Regex("""Icons\.(Outlined|Filled|Rounded)\.""").containsMatchIn(text))
        assertTrue("UiIcons is not used for the cluster icons", "UiIcons.vector(" in text)
        for (renderer in listOf("PdfRenderer", "pdfium", "pdf.js", "mupdf")) assertTrue("Health ships $renderer", renderer !in text)
    }
}
