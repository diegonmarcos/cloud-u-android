package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserGridRow
import com.diegonmarcos.superapp.browser.BrowserGridRows
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabGroups
import com.diegonmarcos.superapp.browser.BrowserTabOps
import com.diegonmarcos.superapp.browser.BrowserTabOrder
import com.diegonmarcos.superapp.browser.BrowserTabStore
import com.diegonmarcos.superapp.browser.PrivateSession
import com.diegonmarcos.superapp.browser.SwipeClose
import com.diegonmarcos.superapp.browser.SwipeGesture
import com.diegonmarcos.superapp.browser.SwipeGesture.Axis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tab cards: no close button, pin ordering and persistence, swipe-close with undo, and the gestures not fighting. */
class BrowserTabCardTest {

    private fun tab(id: String, ts: Long = 1L, pinned: Boolean = false, priv: Boolean = false, group: String = "") =
        BrowserTab("https://$id.example/", id, ts, id = id, pinned = pinned, isPrivate = priv, group = group, previewPath = "/p/$id.png")

    private fun gridSource(): String {
        val rel = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserTabGrid.kt"
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, rel).isFile) d = d.parentFile
        return File(d, rel).readText()
    }

    @Test
    fun `the card has no close button, only the pin and the options menu`() {
        val src = gridSource()
        assertFalse("no close view id", src.contains("ID_CLOSE"))
        assertFalse("no x glyph on a card", src.contains("\" ✕ \""))
        assertTrue(src.contains("id = ID_PIN") && src.contains("id = ID_MENU"))
    }

    @Test
    fun `pinned tabs sort first and stay first in the grid rows`() {
        val tabs = listOf(tab("a", 3), tab("b", 5), tab("p", 1, pinned = true), tab("c", 4))
        assertEquals(listOf("p", "b", "c", "a"), BrowserTabOrder.sort(tabs).map { it.id })
        val rows = BrowserGridRows.build(tabs, emptySet())
        assertEquals("p", (rows.first() as BrowserGridRow.TabCard).tab.id)
    }

    @Test
    fun `the pin persists with the tab`() {
        val saved = BrowserTabStore.serialize(listOf(tab("p", pinned = true), tab("n")))
        val back = BrowserTabStore.parse(saved)
        assertTrue(back.first { it.id == "p" }.pinned)
        assertFalse(back.first { it.id == "n" }.pinned)
    }

    @Test
    fun `close all spares pinned tabs and respects the list being shown`() {
        val tabs = listOf(tab("a"), tab("p", pinned = true), tab("i1", priv = true), tab("i2", priv = true, pinned = true))
        val (keptN, closedN) = BrowserTabOps.closeAll(tabs) { !it.isPrivate }
        assertEquals(listOf("a"), closedN.map { it.id })
        assertEquals(setOf("p", "i1", "i2"), keptN.map { it.id }.toSet())
        val (keptP, closedP) = BrowserTabOps.closeAll(tabs) { it.isPrivate }
        assertEquals(listOf("i1"), closedP.map { it.id })
        assertTrue(keptP.any { it.id == "i2" })
    }

    @Test
    fun `swipe-close then undo restores the tab with its state and place`() {
        val a = tab("a", 3, group = "work"); val b = tab("b", 5); val c = tab("c", 4)
        val all = listOf(a, b, c)
        val sc = SwipeClose(PrivateSession(profileSupported = true))
        val remaining = all.filterNot { it.id == "c" }       // the store dropped it
        sc.begin(c)
        assertEquals(c, sc.pending)
        val back = sc.undo(remaining)
        assertEquals(c, back.first { it.id == "c" })          // same record: group, preview, pin, id
        assertEquals(BrowserTabOrder.sort(all).map { it.id }, BrowserTabOrder.sort(back).map { it.id })
        assertNull(sc.pending)
        assertNull("nothing left to commit after an undo", sc.commit(back))
        // Restoring twice never duplicates.
        assertEquals(back, BrowserTabOps.restore(back, c))
    }

    @Test
    fun `a pinned tab needs a confirm and comes back pinned after undo`() {
        val p = tab("p", pinned = true)
        assertTrue(SwipeGesture.needsConfirm(p))
        assertFalse(SwipeGesture.needsConfirm(tab("n")))
        // Confirmed: the host unpins in the store, but the held snapshot is the pinned original.
        val sc = SwipeClose(PrivateSession())
        sc.begin(p)
        assertTrue(sc.undo(emptyList()).single().pinned)
    }

    @Test
    fun `closing the last incognito tab by swipe tears the profile down only when the window ends`() {
        val s = PrivateSession(profileSupported = true)
        val sc = SwipeClose(s)
        val p = tab("p", priv = true); val n = tab("n")
        s.visit(p, "https://p.example")
        sc.begin(p)
        // Still undoable: nothing was torn down yet.
        val commit = sc.commit(listOf(n))!!
        assertEquals("p", commit.tab.id)
        assertTrue("last private tab: profile is deleted", commit.plan!!.deleteProfile)

        // Another private tab remains: no teardown.
        val sc2 = SwipeClose(PrivateSession(profileSupported = true))
        sc2.begin(p)
        assertNull(sc2.commit(listOf(n, tab("p2", priv = true)))!!.plan)

        // Without multi-profile the old clearing rule applies.
        val sc3 = SwipeClose(PrivateSession(profileSupported = false))
        sc3.begin(p)
        val plan = sc3.commit(listOf(n))!!.plan!!
        assertFalse(plan.deleteProfile)
        assertFalse(plan.clearSessionCookies)   // a normal tab is open
    }

    @Test
    fun `a horizontal swipe locks only when clearly horizontal and never during a drag`() {
        val slop = 8f
        assertEquals(Axis.HORIZONTAL, SwipeGesture.lock(60f, 10f, slop, dragging = false))
        assertEquals(Axis.VERTICAL, SwipeGesture.lock(10f, 60f, slop, dragging = false))     // the grid scrolls
        assertEquals(Axis.NONE, SwipeGesture.lock(5f, 5f, slop, dragging = false))            // inside the slop
        assertEquals(Axis.NONE, SwipeGesture.lock(40f, 35f, slop, dragging = false))          // diagonal: neither
        // A long-press drag (reorder / drop-on-tab / drop-on-group) wins: even a big sideways move is no close.
        assertEquals(Axis.NONE, SwipeGesture.lock(300f, 0f, slop, dragging = true))
    }

    @Test
    fun `drag-to-group still resolves a hover when the dragged card has moved sideways`() {
        val dragged = BrowserTabGroups.Slot("a", null, 0, 0, 100, 100)
        val target = BrowserTabGroups.Slot("b", null, 110, 0, 210, 100)
        val header = BrowserTabGroups.Slot(null, "work", 0, 120, 210, 160)
        // Centre of the dragged card over the other tab: that tab is the drop target (a regroup, not a close).
        assertEquals(target, BrowserTabGroups.hit(listOf(dragged, target, header), 160, 50, "a"))
        // Over a group header: the header.
        assertEquals(header, BrowserTabGroups.hit(listOf(dragged, target, header), 100, 140, "a"))
        // Headers and non-cards are never swipeable.
        assertFalse(SwipeGesture.swipeable(BrowserGridRow.GroupHeader("work", false, 2)))
        assertTrue(SwipeGesture.swipeable(BrowserGridRow.TabCard(tab("a"))))
    }
}
