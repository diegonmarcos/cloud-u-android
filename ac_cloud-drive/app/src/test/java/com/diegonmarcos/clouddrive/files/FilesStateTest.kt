package com.diegonmarcos.clouddrive.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #579 the Files reducers, proved on the JVM: two panes, tabs with a cap, the back walk
 * (selection → history → tab → nothing), archive locations as folders, selection and
 * tree expansion, and the saveable round trip. No Android, no IO.
 */
class FilesStateTest {

    private val store = Location.Local("/mnt/shared/CloudDrive")
    private val external = Location.Local("/mnt/shared")
    private fun fresh() = FilesUiState.initial(store, external, "name", false, true)

    @Test fun localLocationNameKeyAndParent() {
        val l = Location.Local("/a/b/c")
        assertEquals("c", l.name)
        assertEquals("L:/a/b/c", l.key)
        assertEquals(Location.Local("/a/b"), l.parent())
        assertEquals(Location.Local("/a/b/c/d"), l.child("d"))
        assertNull(Location.Local("/").parent())
        assertNull(Location.Local("/a").parent())
    }

    @Test fun archiveLocationIsAFolderWithCrumbsThroughTheZip() {
        val root = Location.Archive("/a/b/x.zip")
        assertTrue(root.isRoot)
        assertEquals("x.zip", root.name)
        val deep = root.child("docs").child("2026")
        assertEquals("docs/2026", deep.inner)
        assertEquals("2026", deep.name)
        assertEquals("docs/2026/", deep.prefix)
        assertEquals(Location.Archive("/a/b/x.zip", "docs"), deep.parent())
        assertEquals(Location.Archive("/a/b/x.zip", ""), Location.Archive("/a/b/x.zip", "docs").parent())
        // Up from the archive root lands in the folder that holds the zip.
        assertEquals(Location.Local("/a/b"), root.parent())
        val crumbs = deep.crumbs()
        // Crumbs start at the first path segment, the same top the Local chain stops at (Local("/a").parent() is null).
        assertEquals(listOf(Location.Local("/a"), Location.Local("/a/b"), Location.Archive("/a/b/x.zip", ""), Location.Archive("/a/b/x.zip", "docs"), deep), crumbs)
    }

    @Test fun openPushesHistoryAndBackWalksIt() {
        var s = fresh()
        s = FilesReducer.open(s, PaneId.A, store.child("cloud-infra"))
        s = FilesReducer.open(s, PaneId.A, store.child("cloud-infra").child("src"))
        assertEquals(store.child("cloud-infra").child("src"), s.a.location)
        assertEquals(2, s.a.current.history.size)
        s = FilesReducer.back(s, PaneId.A)!!
        assertEquals(store.child("cloud-infra"), s.a.location)
        s = FilesReducer.back(s, PaneId.A)!!
        assertEquals(store, s.a.location)
        assertNull("nothing left to undo → the shell leaves the app", FilesReducer.back(s, PaneId.A))
        // Pane B was never touched.
        assertEquals(external, s.b.location)
    }

    @Test fun backClearsASelectionBeforeItNavigates() {
        var s = FilesReducer.open(fresh(), PaneId.A, store.child("x"))
        s = FilesReducer.toggleSelect(s, PaneId.A, "L:/f")
        assertTrue(s.a.selecting)
        s = FilesReducer.back(s, PaneId.A)!!
        assertFalse(s.a.selecting)
        assertEquals(store.child("x"), s.a.location)
    }

    @Test fun upStopsAtTheFilesystemRoot() {
        val s = fresh()
        assertNotNull(FilesReducer.up(s, PaneId.A))
        val atRoot = FilesReducer.open(s, PaneId.A, Location.Local("/"))
        assertNull(FilesReducer.up(atRoot, PaneId.A))
    }

    @Test fun tabsAreCappedAndBackClosesANonLastTab() {
        var s = fresh()
        s = FilesReducer.openTab(s, PaneId.A, external, max = 2)
        assertEquals(2, s.a.tabs.size)
        assertEquals(1, s.a.activeTab)
        // At the cap the current tab is replaced, not a third one opened.
        s = FilesReducer.openTab(s, PaneId.A, store.child("y"), max = 2)
        assertEquals(2, s.a.tabs.size)
        assertEquals(store.child("y"), s.a.location)
        // Back: history first (the replaced tab remembered external), then the tab closes.
        s = FilesReducer.back(s, PaneId.A)!!
        assertEquals(external, s.a.location)
        s = FilesReducer.back(s, PaneId.A)!!
        assertEquals(1, s.a.tabs.size)
        assertEquals(store, s.a.location)
        assertEquals(s, FilesReducer.closeTab(s, PaneId.A, 0))
    }

    @Test fun closingATabBeforeTheActiveOneKeepsTheActiveLocation() {
        var s = fresh()
        s = FilesReducer.openTab(s, PaneId.A, external, 6)
        s = FilesReducer.openTab(s, PaneId.A, store.child("z"), 6)
        s = FilesReducer.closeTab(s, PaneId.A, 0)
        assertEquals(store.child("z"), s.a.location)
        assertEquals(2, s.a.tabs.size)
    }

    @Test fun paneActivationAndDualToggle() {
        var s = fresh()
        assertEquals(PaneId.A, s.active)
        s = FilesReducer.activate(s, PaneId.B)
        assertEquals(PaneId.B, s.active)
        assertEquals(PaneId.A, s.otherId)
        assertEquals(s.b, s.activePane)
        s = FilesReducer.toggleDual(s)
        assertFalse(s.dualPane)
        assertEquals("single mode shows pane A", PaneId.A, s.active)
        s = FilesReducer.setDual(s, true)
        assertTrue(s.dualPane)
        assertEquals(s, FilesReducer.setDual(s, true))
    }

    @Test fun selectionVerbs() {
        var s = fresh()
        val keys = listOf("L:/a", "L:/b", "L:/c")
        s = FilesReducer.toggleSelect(s, PaneId.A, "L:/a")
        s = FilesReducer.toggleSelect(s, PaneId.A, "L:/a")
        assertFalse(s.a.selecting)
        s = FilesReducer.select(s, PaneId.A, listOf("L:/a", "L:/b"))
        assertEquals(setOf("L:/a", "L:/b"), s.a.selection)
        s = FilesReducer.invert(s, PaneId.A, keys)
        assertEquals(setOf("L:/c"), s.a.selection)
        s = FilesReducer.selectAll(s, PaneId.A, keys)
        assertEquals(keys.toSet(), s.a.selection)
        s = FilesReducer.clearSelection(s, PaneId.A)
        assertFalse(s.a.selecting)
        // Navigating clears a selection: the keys belong to the folder that was left.
        s = FilesReducer.select(s, PaneId.A, keys)
        s = FilesReducer.open(s, PaneId.A, external)
        assertFalse(s.a.selecting)
    }

    @Test fun sortFilterHiddenViewAndTree() {
        var s = fresh()
        s = FilesReducer.setSort(s, PaneId.B, "size", true)
        assertEquals("size", s.b.sort); assertTrue(s.b.descending); assertEquals("name", s.a.sort)
        s = FilesReducer.setFilter(s, PaneId.A, "images")
        assertEquals("images", s.a.filterId)
        s = FilesReducer.toggleHidden(s, PaneId.A)
        assertTrue(s.a.showHidden)
        s = FilesReducer.setViewMode(s, PaneId.A, ViewMode.TREE)
        assertEquals(ViewMode.TREE, s.a.viewMode)
        s = FilesReducer.toggleExpanded(s, PaneId.A, "L:/x")
        assertTrue("L:/x" in s.a.expanded)
        s = FilesReducer.toggleExpanded(s, PaneId.A, "L:/x")
        assertFalse("L:/x" in s.a.expanded)
    }

    @Test fun saveableRoundTripKeepsEverything() {
        var s = fresh()
        s = FilesReducer.openTab(s, PaneId.A, Location.Archive("/mnt/shared/a.zip", "docs"), 6)
        s = FilesReducer.setViewMode(s, PaneId.B, ViewMode.TREE)
        s = FilesReducer.toggleExpanded(s, PaneId.B, "L:/mnt/shared/Download")
        s = FilesReducer.select(s, PaneId.A, listOf("A:/mnt/shared/a.zip!/docs/x.txt"))
        s = FilesReducer.activate(s, PaneId.B)
        val text = s.encode()
        val back = FilesUiState.decode(text)
        assertEquals(s, back)
        assertNull(FilesUiState.decode("not json"))
    }

    @Test fun historyIsCapped() {
        var s = fresh()
        repeat(FilesReducer.HISTORY_CAP + 10) { i -> s = FilesReducer.open(s, PaneId.A, store.child("d$i")) }
        assertEquals(FilesReducer.HISTORY_CAP, s.a.current.history.size)
    }
}
