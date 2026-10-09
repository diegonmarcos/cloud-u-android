package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabsBar
import com.diegonmarcos.superapp.browser.BrowserTabsBar.Filter
import com.diegonmarcos.superapp.browser.BrowserTabsBar.Id
import com.diegonmarcos.superapp.browser.BrowserTabsBar.Item
import com.diegonmarcos.superapp.browser.PrivateSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Tabs view's top row: order, bare "│" dividers, the Normal/Incognito toggle, and incognito leaving nothing. */
class BrowserIncognitoTabsTest {

    private fun tab(id: String, priv: Boolean = false) = BrowserTab("https://$id.example/", id, 1L, id = id, isPrivate = priv)

    @Test
    fun `buttons are in the owner's order with bare dividers between the groups`() {
        val items = BrowserTabsBar.items(Filter.NORMAL)
        assertEquals(
            listOf("New Tab", "New Incognito", "│", "Normal Tabs", "Incognito Tabs", "│", "History"),
            items.map { if (it is Item.Action) it.label else BrowserTabsBar.SEP },
        )
        assertEquals("New Tab New Incognito │ Normal Tabs Incognito Tabs │ History", BrowserTabsBar.text(Filter.NORMAL))
        // A divider is not a button: no id, so nothing to tap and no cell to size.
        assertEquals(2, items.count { it === Item.Sep })
        assertEquals(listOf(Id.NEW_TAB, Id.NEW_INCOGNITO, Id.NORMAL, Id.INCOGNITO, Id.HISTORY),
            items.filterIsInstance<Item.Action>().map { it.id })
    }

    @Test
    fun `only the active toggle is highlighted`() {
        fun active(f: Filter) = BrowserTabsBar.items(f).filterIsInstance<Item.Action>().filter { it.active }.map { it.id }
        assertEquals(listOf(Id.NORMAL), active(Filter.NORMAL))
        assertEquals(listOf(Id.INCOGNITO), active(Filter.INCOGNITO))
    }

    @Test
    fun `the toggle filters the grid to normal or incognito tabs`() {
        val tabs = listOf(tab("a"), tab("p1", true), tab("b"), tab("p2", true))
        assertEquals(listOf("a", "b"), BrowserTabsBar.filter(tabs, Filter.NORMAL).map { it.id })
        assertEquals(listOf("p1", "p2"), BrowserTabsBar.filter(tabs, Filter.INCOGNITO).map { it.id })
        assertEquals(2, BrowserTabsBar.count(tabs, Filter.INCOGNITO))
        assertTrue(BrowserTabsBar.filter(listOf(tab("a")), Filter.INCOGNITO).isEmpty())
    }

    @Test
    fun `an incognito tab leaves no history and no cookies after it closes`() {
        val s = PrivateSession()
        val p = tab("p", true)
        val n = tab("n")
        val recorded = ArrayList<String>()
        // History is only written when visit() says so.
        if (s.visit(p, "https://p.example")) recorded.add("p")
        if (s.visit(p, "https://tracker.example")) recorded.add("t")
        assertTrue("incognito pages never reach history", recorded.isEmpty())
        assertTrue(s.visit(n, "https://n.example"))

        // A normal tab is still open: site storage of the visited origins goes, session cookies stay.
        val withNormal = s.close(p, listOf(n))!!
        assertEquals(setOf("https://p.example", "https://tracker.example"), withNormal.clearOrigins)
        assertFalse(withNormal.clearSessionCookies)

        // Nothing open afterwards: the session cookies go too.
        s.visit(p, "https://p.example")
        val alone = s.close(p, emptyList())!!
        assertEquals(setOf("https://p.example"), alone.clearOrigins)
        assertTrue(alone.clearSessionCookies)
        // Origins are forgotten once cleared.
        s.visit(p, null)
        assertTrue(s.close(p, emptyList())!!.clearOrigins.isEmpty())
    }

    @Test
    fun `nothing is cleared while another incognito tab is open, or for a normal tab`() {
        val s = PrivateSession()
        s.visit(tab("p1", true), "https://p.example")
        assertNull(s.close(tab("p1", true), listOf(tab("p2", true))))
        assertNull(s.close(tab("n"), emptyList()))
        assertNotNull(s.close(tab("p2", true), emptyList()))
    }
}
