package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserStripRules
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #886 parts 7 and 9: grouping by drop, naming/colour/edit, and what the tab strip shows. */
class BrowserGroupsTest {

    private fun tab(id: String, url: String, group: String = "", ts: Long = 1L) = BrowserTab(url, id, ts, id = id, group = group)

    private val gh1 = tab("gh1", "https://github.com/a")
    private val gh2 = tab("gh2", "https://www.github.com/b")
    private val wiki = tab("wiki", "https://en.wikipedia.org/x")
    private val news = tab("news", "https://news.example.org/")

    @Test
    fun `dropping a tab on another loose tab creates a group holding both`() {
        val c = BrowserTabGroups.dropOnTab(listOf(gh1, wiki, news), emptyMap(), "gh1", "wiki")!!
        assertTrue(c.created)
        assertEquals(setOf("gh1", "wiki"), c.tabs.filter { it.group == c.group }.map { it.id }.toSet())
        assertEquals("", c.tabs.first { it.id == "news" }.group)
        assertTrue("the new group has a colour", c.colors.containsKey(c.group))
    }

    @Test
    fun `two pages of one site are named after the site, two sites after both`() {
        assertEquals("Github", BrowserTabGroups.dropOnTab(listOf(gh1, gh2), emptyMap(), "gh1", "gh2")!!.group)
        assertEquals("Wikipedia & Github", BrowserTabGroups.dropOnTab(listOf(gh1, wiki), emptyMap(), "gh1", "wiki")!!.group)
    }

    @Test
    fun `a new group never takes a name or a colour already in use`() {
        val first = BrowserTabGroups.dropOnTab(listOf(gh1, gh2, wiki, news), emptyMap(), "gh1", "gh2")!!
        val again = BrowserTabGroups.dropOnTab(first.tabs.map { if (it.id == "wiki") it.copy(url = "https://github.com/c") else if (it.id == "news") it.copy(url = "https://github.com/d") else it },
            first.colors, "wiki", "news")!!
        assertNotEquals(first.group, again.group)
        assertEquals("Github 2", again.group)
        assertNotEquals(first.colors[first.group], again.colors[again.group])
    }

    @Test
    fun `dropping on a tab of a group, or on the group header, joins that group`() {
        val g = BrowserTabGroups.dropOnTab(listOf(gh1, gh2, wiki), emptyMap(), "gh1", "gh2")!!
        val viaTab = BrowserTabGroups.dropOnTab(g.tabs, g.colors, "wiki", "gh2")!!
        assertEquals(g.group, viaTab.tabs.first { it.id == "wiki" }.group)
        assertTrue(!viaTab.created)
        val viaHeader = BrowserTabGroups.dropOnGroup(g.tabs, g.colors, "wiki", g.group)!!
        assertEquals(g.group, viaHeader.tabs.first { it.id == "wiki" }.group)
        assertEquals("colour is kept", g.colors[g.group], viaHeader.colors[g.group])
    }

    @Test
    fun `a drop that is not a regroup is refused`() {
        val g = BrowserTabGroups.dropOnTab(listOf(gh1, gh2, wiki), emptyMap(), "gh1", "gh2")!!
        assertNull("onto itself", BrowserTabGroups.dropOnTab(g.tabs, g.colors, "gh1", "gh1"))
        assertNull("onto a tab of its own group is a reorder", BrowserTabGroups.dropOnTab(g.tabs, g.colors, "gh1", "gh2"))
        assertNull("onto its own header", BrowserTabGroups.dropOnGroup(g.tabs, g.colors, "gh1", g.group))
        assertNull("unknown tab", BrowserTabGroups.dropOnTab(g.tabs, g.colors, "zzz", "gh2"))
    }

    @Test
    fun `a group that loses its last tab disappears with its colour`() {
        val g = BrowserTabGroups.dropOnTab(listOf(gh1, wiki, news), emptyMap(), "gh1", "wiki")!!
        val g2 = BrowserTabGroups.dropOnTab(g.tabs, g.colors, "news", "wiki")!!   // news joins g
        val left = BrowserTabGroups.ungroup(g2.tabs, g2.colors, g2.group)
        assertTrue(left.tabs.all { it.group.isBlank() })
        assertTrue(left.colors.isEmpty())
    }

    @Test
    fun `a group is renamed and recoloured, and a taken or blank name is refused`() {
        val g = BrowserTabGroups.dropOnTab(listOf(gh1, wiki, news, gh2), emptyMap(), "gh1", "wiki")!!
        val other = BrowserTabGroups.dropOnTab(g.tabs, g.colors, "news", "gh2")!!
        val r = BrowserTabGroups.rename(other.tabs, other.colors, g.group, "Reading", color = BrowserTabGroups.PALETTE[5])!!
        assertEquals("Reading", r.group)
        assertEquals(BrowserTabGroups.PALETTE[5], r.colors["Reading"])
        assertTrue(r.tabs.filter { it.group == "Reading" }.size == 2)
        assertTrue(r.colors.keys.none { it == g.group })
        assertNull(BrowserTabGroups.rename(other.tabs, other.colors, g.group, "   "))
        assertNull(BrowserTabGroups.rename(other.tabs, other.colors, g.group, other.group))
    }

    @Test
    fun `the strip's plus starts a group from a loose tab and joins the group of a grouped one`() {
        val fresh = tab("fresh", "https://new.example/")
        val loose = BrowserTabGroups.startOrJoin(listOf(gh1, fresh), emptyMap(), "gh1", "fresh")!!
        assertTrue(loose.created)
        assertEquals(loose.group, loose.tabs.first { it.id == "gh1" }.group)
        assertEquals(loose.group, loose.tabs.first { it.id == "fresh" }.group)
        val fresh2 = tab("fresh2", "https://new2.example/")
        val joined = BrowserTabGroups.startOrJoin(loose.tabs + fresh2, loose.colors, "gh1", "fresh2")!!
        assertEquals(loose.group, joined.group)
        assertTrue(!joined.created)
        assertEquals(3, joined.tabs.count { it.group == loose.group })
    }

    @Test
    fun `hit testing picks the card under the finger and never the dragged one`() {
        val slots = listOf(
            BrowserTabGroups.Slot("a", null, 0, 0, 100, 100),
            BrowserTabGroups.Slot("b", null, 100, 0, 200, 100),
            BrowserTabGroups.Slot(null, "Work", 0, 100, 200, 140),
        )
        assertEquals("b", BrowserTabGroups.hit(slots, 150, 50, "a")!!.tabKey)
        assertNull("over itself", BrowserTabGroups.hit(slots, 50, 50, "a"))
        assertEquals("Work", BrowserTabGroups.hit(slots, 50, 120, "a")!!.group)
        assertNull("outside everything", BrowserTabGroups.hit(slots, 500, 500, "a"))
        assertNull("right edge is exclusive", BrowserTabGroups.hit(slots, 200, 50, "a"))
    }

    // ── the strip ──

    @Test
    fun `the strip shows the current tab's group, or just the tab when it has none`() {
        val g = BrowserTabGroups.dropOnTab(listOf(gh1, gh2, wiki), emptyMap(), "gh1", "gh2")!!
        assertEquals(setOf("gh1", "gh2"), BrowserStripRules.visible(g.tabs, g.tabs.first { it.id == "gh1" }).map { it.id }.toSet())
        assertEquals(listOf("wiki"), BrowserStripRules.visible(g.tabs, g.tabs.first { it.id == "wiki" }).map { it.id })
        assertTrue(BrowserStripRules.visible(g.tabs, null).isEmpty())
    }

    @Test
    fun `a tab without a favicon shows its site's first letter`() {
        assertEquals("G", BrowserStripRules.letter(gh1))
        assertEquals("W", BrowserStripRules.letter(wiki))
        assertEquals("N", BrowserStripRules.letter(BrowserTab("", "news", 1L)))
        assertNotNull(BrowserStripRules.letter(BrowserTab("", "", 1L)))
    }
}
