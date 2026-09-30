package com.diegonmarcos.cloudc3

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #648 the declaration parser, run on the JVM against THIS repository's own build.json.
 *
 * The parse functions take JSON TEXT rather than BuildConfig precisely so this suite can
 * exercise the exact code the phone runs. A shell tester can compare two files; only this
 * can prove the parser turns the declaration into the models the screens receive — so a
 * declaration that parses to something subtly different (a dropped tab, a blank label)
 * fails here rather than on a device.
 */
class DeclarationsTest {

    /** The repository's real build.json, found by walking up to the app directory. */
    private fun buildJson(): JsonObject {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        val f = File(dir, "build.json")
        assertTrue("could not locate ac_cloud-c3/build.json from ${File("").absolutePath}", f.isFile)
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun ui(): JsonObject = buildJson()["ui"]!!.jsonObject

    private fun uiText(key: String): String = ui()[key]!!.toString()

    @Test
    fun `the five declared tabs parse in declared order with Home centre`() {
        val tabs = Declarations.parseTabs(uiText("tabs"))
        assertEquals("every declared tab must parse; a dropped one is an invisible missing tab",
            5, tabs.size)
        assertEquals(listOf("Topology", "Observ", "Home", "Apps", "Configs"), tabs.map { it.label })
        assertEquals("Home must be the centre item", "home", tabs[2].id)
        assertTrue("no tab may parse with a blank label or icon",
            tabs.all { it.label.isNotBlank() && it.icon.isNotBlank() })
    }

    @Test
    fun `the declared default tab is one of the declared tabs`() {
        val tabs = Declarations.parseTabs(uiText("tabs")).map { it.id }
        val default = ui()["default_tab"]!!.jsonPrimitive.content
        assertTrue("ui.default_tab '$default' is not among $tabs, so the app would open blank",
            default in tabs)
    }

    @Test
    fun `the three Apps tiles parse with the sibling packages they declare`() {
        val apps = Declarations.parseExternalApps(uiText("external_apps"))
        assertEquals("every declared Apps tile must parse", 3, apps.size)
        assertEquals(listOf("c3-watchdog", "c3-morpheus", "c3-watchtower"), apps.map { it.id })
        assertEquals(
            listOf(
                "com.diegonmarcos.watchdog",
                "com.diegonmarcos.morpheus",
                "com.diegonmarcos.watchtower",
            ),
            apps.map { it.packageName },
        )
    }

    @Test
    fun `the tile's printed name is DERIVED from the fleet name, not declared`() {
        // The owner asked for Watchdog / Morpheus / Watchtower; the fleet spells these
        // c3-watchdog / c3-morpheus / c3-watchtower. Both are satisfied by deriving the
        // printed name, which is why no `label` is stored: #351's one name, #224's revert.
        val apps = Declarations.parseExternalApps(uiText("external_apps"))
        assertEquals(listOf("Watchdog", "Morpheus", "Watchtower"), apps.map { it.display })
    }

    @Test
    fun `no Apps tile declares a label, because the name would then be stated twice`() {
        val raw = ui()["external_apps"]!!.jsonArray
        assertTrue("a label beside a fleet package is a second statement of the app's one name",
            raw.none { "label" in it.jsonObject })
    }

    @Test
    fun `an id with no family prefix still prints something`() {
        val one = Declarations.parseExternalApps(
            """[{"id":"morpheus","icon":"robot","package":"p"}]""",
        ).single()
        assertEquals("Morpheus", one.display)
    }

    @Test
    fun `an Apps tile with no package is dropped rather than rendered unopenable`() {
        val text = """[{"id":"a","label":"A","icon":"robot"}]"""
        assertTrue("a tile that cannot name the app it opens must not be rendered",
            Declarations.parseExternalApps(text).isEmpty())
    }

    @Test
    fun `each content tab's declared sub-pages parse`() {
        // DECLARED means IMPLEMENTED (#648): these are exactly the pages that have a
        // fragment today. The `topology`/`observability` pages ARE the SuperApp's two
        // stacks (feed cards, ntfy centre, container dashboards — C3StackFragment over
        // ui.sections[c3].stack_*), carried whole per the owner's escalation; dagu and the
        // WG mesh arrived with them. The SuperApp's sample-stub page ids stay undeclared,
        // which is what keeps "no placeholder in a shipped tab" assertable.
        assertEquals(listOf("topology", "public", "private"),
            Declarations.parsePages(uiText("topology")).map { it.id })
        assertEquals(listOf("observability", "health", "dagu", "mesh"),
            Declarations.parsePages(uiText("observ")).map { it.id })
        assertEquals(listOf("about"), Declarations.parsePages(uiText("configs")).map { it.id })
    }

    @Test
    fun `every declared icon name looks like a drawable name, not a vocabulary key`() {
        // The shell is Views and resolves icons with getIdentifier, so a name must be a real
        // resource name. "health" or "robot" (the old Compose keys) resolve to 0 and draw a
        // blank square, which is why the shell tester also checks the file exists.
        val names = Declarations.iconNames()
        assertTrue("no declared icon may be blank", names.none { it.isBlank() })
        assertTrue("every declared icon must be a drawable name (ic_*): $names",
            names.all { it.startsWith("ic_") })
    }

    @Test
    fun `parsePages reads the pages-object and the bare-array shape identically`() {
        val wrapped = """{"pages":[{"id":"x","label":"X","icon":"stack"}]}"""
        val bare = """[{"id":"x","label":"X","icon":"stack"}]"""
        assertEquals(Declarations.parsePages(bare), Declarations.parsePages(wrapped))
    }

    @Test
    fun `a malformed or empty declaration parses to empty rather than throwing`() {
        // A crash here would be a blank app; an empty list is a stated empty state.
        assertEquals(emptyList<Declarations.TabDecl>(), Declarations.parseTabs(""))
        assertEquals(emptyList<Declarations.TabDecl>(), Declarations.parseTabs("{not json"))
        assertEquals("", Declarations.decode("!!!not base64!!!"))
    }

    @Test
    fun `reordering the declaration reorders the parsed tabs and nothing else`() {
        // The property the bottom nav rests on: order is carried, never sorted.
        val a = """[{"id":"one","label":"One","icon":"home"},{"id":"two","label":"Two","icon":"stack"}]"""
        val b = """[{"id":"two","label":"Two","icon":"stack"},{"id":"one","label":"One","icon":"home"}]"""
        assertEquals(listOf("one", "two"), Declarations.parseTabs(a).map { it.id })
        assertEquals(listOf("two", "one"), Declarations.parseTabs(b).map { it.id })
        assertEquals(Declarations.parseTabs(a).toSet(), Declarations.parseTabs(b).toSet())
    }

    @Test
    fun `every icon name the declaration uses is a non-blank short name`() {
        val ui = ui()
        val names = mutableListOf<String>()
        ui["tabs"]!!.jsonArray.forEach { names += it.jsonObject["icon"]!!.jsonPrimitive.content }
        ui["external_apps"]!!.jsonArray.forEach { names += it.jsonObject["icon"]!!.jsonPrimitive.content }
        names += ui["icon_default"]!!.jsonPrimitive.content
        assertTrue("an icon name is a bare drawable name, never a path or an @reference",
            names.all { it.isNotBlank() && !it.contains('/') && !it.startsWith("@") })
    }
}
