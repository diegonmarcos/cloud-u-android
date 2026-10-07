package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserContextMenu as M
import com.diegonmarcos.superapp.browser.BrowserContextMenu.Action
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long-press on page content: hit type -> actions, and where an opened tab lands. */
class BrowserContextMenuTest {

    private fun tab(id: String, url: String, group: String = "") = BrowserTab(url, id, 1L, id = id, group = group)

    @Test
    fun `a link offers open, copy, share, download and Fav`() {
        val t = M.target(M.SRC_ANCHOR, "https://a.example/x", null, "Hello")!!
        assertEquals(
            listOf(Action.OPEN_TAB, Action.OPEN_IN_GROUP, Action.OPEN_BACKGROUND, Action.OPEN_PRIVATE, Action.COPY_LINK,
                Action.COPY_LINK_TEXT, Action.SHARE_LINK, Action.DOWNLOAD_LINK, Action.ADD_FAV),
            M.actions(t, hasPrivate = true))
    }

    @Test
    fun `no private tab means no private row, no link text means no copy-text row`() {
        val a = M.actions(M.target(M.SRC_ANCHOR, "https://a.example/x")!!, hasPrivate = false)
        assertFalse(Action.OPEN_PRIVATE in a)
        assertFalse(Action.COPY_LINK_TEXT in a)
    }

    @Test
    fun `an image offers its own five rows and none of the link rows`() {
        val t = M.target(M.IMAGE, "https://a.example/p.png")!!
        assertEquals(listOf(Action.IMAGE_OPEN, Action.IMAGE_DOWNLOAD, Action.IMAGE_COPY, Action.IMAGE_SHARE, Action.IMAGE_SEARCH),
            M.actions(t, true))
    }

    @Test
    fun `an image link resolves its href and offers both sets`() {
        val t = M.target(M.SRC_IMAGE_ANCHOR, "https://a.example/p.png", "https://a.example/page", "Pic")!!
        assertEquals("https://a.example/page", t.linkUrl)
        assertEquals("https://a.example/p.png", t.imageUrl)
        val a = M.actions(t, true)
        assertTrue(Action.OPEN_IN_GROUP in a && Action.IMAGE_DOWNLOAD in a && Action.ADD_FAV in a)
    }

    @Test
    fun `text, fields, phone, email, geo and unknown keep the system actions`() {
        for (type in listOf(M.UNKNOWN, M.EDIT_TEXT, M.PHONE, M.EMAIL, M.GEO)) assertNull("type $type", M.target(type, "x"))
        assertNull(M.target(M.SRC_ANCHOR, ""))
        assertNull(M.target(M.IMAGE, null))
    }

    @Test
    fun `a non-web link cannot be opened or downloaded, only copied and shared`() {
        val a = M.actions(M.target(M.SRC_ANCHOR, "javascript:void(0)")!!, true)
        assertEquals(listOf(Action.COPY_LINK, Action.SHARE_LINK), a)
        val d = M.actions(M.target(M.IMAGE, "data:image/png;base64,AAAA")!!, true)
        assertEquals(listOf(Action.IMAGE_COPY, Action.IMAGE_SHARE), d)
    }

    @Test
    fun `placement - in group joins, background never takes focus, private forces private`() {
        assertEquals(M.Placement(activate = true, group = true, isPrivate = false), M.placement(Action.OPEN_IN_GROUP, false))
        assertFalse(M.placement(Action.OPEN_BACKGROUND, false)!!.activate)
        assertTrue(M.placement(Action.OPEN_TAB, false)!!.activate)
        assertTrue(M.placement(Action.OPEN_PRIVATE, false)!!.isPrivate)
        assertTrue("a link opened from a private tab stays private", M.placement(Action.OPEN_TAB, true)!!.isPrivate)
        assertNull(M.placement(Action.COPY_LINK, false))
    }

    @Test
    fun `in this group - the new tab joins the current tab's group`() {
        val g = BrowserTabGroups.dropOnTab(listOf(tab("a", "https://github.com/a"), tab("b", "https://github.com/b")), emptyMap(), "a", "b")!!
        val tabs = g.tabs + tab("n", "https://github.com/c")
        val c = BrowserTabGroups.startOrJoin(tabs, g.colors, "a", "n")!!
        assertEquals(g.group, c.tabs.first { it.id == "n" }.group)
        assertFalse(c.created)
    }

    @Test
    fun `in this group from a loose tab starts a group holding both`() {
        val tabs = listOf(tab("a", "https://github.com/a"), tab("n", "https://github.com/c"), tab("z", "https://z.example/"))
        val c = BrowserTabGroups.startOrJoin(tabs, emptyMap(), "a", "n")!!
        assertTrue(c.created)
        assertEquals(setOf("a", "n"), c.tabs.filter { it.group == c.group }.map { it.id }.toSet())
        assertEquals("", c.tabs.first { it.id == "z" }.group)
    }

    @Test
    fun `image search goes to Lens, or Bing for Bing, with the address encoded`() {
        assertTrue(M.imageSearchUrl("google", "https://a.example/p.png?x=1").startsWith("https://lens.google.com/uploadbyurl?url=https%3A%2F%2Fa.example%2Fp.png%3Fx%3D1"))
        assertTrue(M.imageSearchUrl("bing", "https://a.example/p.png").startsWith("https://www.bing.com/images/search"))
        assertNotNull(M.imageSearchUrl("qwant", "https://a.example/p.png"))
    }
}
