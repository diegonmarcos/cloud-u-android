package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #886 part 8: "after closing the app and coming back, a tab shows an OLDER page".
 *
 * ROOT CAUSE, reproduced here as behaviour: the tab list stored the url a tab was OPENED with and
 * nothing rewrote it when the tab navigated, so a restart (here: serialize → parse, which is exactly
 * what SharedPreferences does between runs) brought back the first page. [BrowserTabStore.commit] is
 * the missing write; these tests prove the last committed url is what survives, and that the
 * navigations that must NOT be remembered are refused.
 */
class BrowserTabStateTest {

    private fun restart(tabs: List<BrowserTab>) = BrowserTabStore.parse(BrowserTabStore.serialize(tabs))

    private val a = BrowserTab("https://a.example/start", "A", 1L, id = "ida")
    private val b = BrowserTab("https://b.example/", "B", 2L, id = "idb", pinned = true, group = "work", order = 3)

    @Test
    fun `a tab that navigated comes back on the page it LAST committed, not the one it opened with`() {
        var tabs = listOf(a, b)
        // the user follows a link inside tab A, then another
        tabs = BrowserTabStore.commit(tabs, "ida", "https://a.example/article/1", "Article 1")
        tabs = BrowserTabStore.commit(tabs, "ida", "https://a.example/article/2", "Article 2")
        val back = restart(tabs).first { it.id == "ida" }
        assertEquals("https://a.example/article/2", back.url)
        assertEquals("Article 2", back.title)
        assertNotEquals("the opened-with url must not be what returns", "https://a.example/start", back.url)
    }

    @Test
    fun `without the commit the old page returns - the bug, stated as a test of the data`() {
        // What the store did before: nothing wrote the url after opening. The restarted tab IS the opened one.
        val back = restart(listOf(a)).single()
        assertEquals("https://a.example/start", back.url)
    }

    @Test
    fun `the active tab is found by id after its url changed`() {
        val tabs = BrowserTabStore.commit(listOf(a, b), "ida", "https://a.example/moved")
        val active = BrowserTabStore.active(restart(tabs), "ida", "https://a.example/start")
        assertEquals("https://a.example/moved", active!!.url)
    }

    @Test
    fun `a store from before ids still finds its active tab by url and stamps a stable id`() {
        val legacy = """[{"url":"https://old.example/","title":"Old","ts":7}]"""
        val first = BrowserTabStore.parse(legacy).single()
        val second = BrowserTabStore.parse(legacy).single()
        assertTrue(first.id.isNotBlank())
        assertEquals("two reads agree on the legacy id", first.id, second.id)
        assertEquals(first.id, BrowserTabStore.active(listOf(first), null, "https://old.example/")!!.id)
        // and once saved it keeps that id even though the url then changes
        val moved = BrowserTabStore.commit(listOf(first), first.id, "https://old.example/next")
        assertEquals(first.id, restart(moved).single().id)
    }

    @Test
    fun `only a real committed page is remembered`() {
        for (bad in listOf(null, "", "   ", "about:blank", "data:text/html,hi", "javascript:alert(1)", "blob:https://x/1", "chrome-error://chromewebdata/")) {
            assertFalse("'$bad' must not be committed", BrowserTabStore.shouldCommit(bad))
            val tabs = listOf(a)
            assertSame("a refused commit changes nothing", tabs, BrowserTabStore.commit(tabs, "ida", bad))
        }
        assertTrue(BrowserTabStore.shouldCommit("https://x.example/p"))
        assertTrue(BrowserTabStore.shouldCommit("http://localhost:8000/"))
        assertTrue(BrowserTabStore.shouldCommit("file:///data/user/0/app/files/offline/s/index.mhtml"))
    }

    @Test
    fun `a commit moves the url and nothing else`() {
        val moved = BrowserTabStore.commit(listOf(a, b), "idb", "https://b.example/page")
        val nb = restart(moved).first { it.id == "idb" }
        assertEquals("https://b.example/page", nb.url)
        assertTrue(nb.pinned); assertEquals("work", nb.group); assertEquals(3, nb.order)
        assertEquals("the other tab is untouched", a, restart(moved).first { it.id == "ida" }.copy(ts = a.ts))
    }

    @Test
    fun `two tabs on the same page stay two tabs`() {
        val t1 = BrowserTab("https://same.example/", "1", 1L, id = "one")
        val t2 = BrowserTab("https://same.example/", "2", 2L, id = "two")
        val tabs = BrowserTabStore.commit(listOf(t1, t2), "one", "https://same.example/x")
        val back = restart(tabs)
        assertEquals(2, back.size)
        assertEquals("https://same.example/x", back.first { it.id == "one" }.url)
        assertEquals("https://same.example/", back.first { it.id == "two" }.url)
    }

    @Test
    fun `an unknown tab id commits nothing and active falls through to null`() {
        val tabs = listOf(a)
        assertEquals(tabs, BrowserTabStore.commit(tabs, "nope", "https://z.example/"))
        assertNull(BrowserTabStore.active(tabs, "nope", "https://elsewhere/"))
    }
}
