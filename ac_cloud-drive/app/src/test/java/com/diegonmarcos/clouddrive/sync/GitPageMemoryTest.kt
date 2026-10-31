package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #1356 The Git page keeps its exact state across a bottom-nav switch and a process death. The page reads [GitPageMemory] from
 * the activity-scoped [GitPageMemoryStore] when it is drawn and writes every change back; a switch disposes the page, so the
 * store is the only thing that carries the open repo across it. test-drive-git-page-memory.sh proves the page is wired to it.
 */
class GitPageMemoryTest {
    private val key = GitPageMemoryStore.keyOf(GitView.ME, GitPart.TABLE)

    /** The page as it was left: repo-b's sheet open on its Branches sub-menu, scrolled, searched, Logs on App. */
    private val left = GitPageMemory(
        expanded = "cloud-u-linux", panelRepo = "repo-b", panel = "branches", settingsFor = "", statusFor = "",
        showActivity = false, logsTab = "app", query = "cloud", showFilters = true, scrollIndex = 7, scrollOffset = 42,
    )

    @Test fun `open a repo, navigate away and back, the same repo is open`() {
        val store = GitPageMemoryStore()
        store.put(key, left)                     // the page writes its state while drawn
        // navigate away: the page composable is disposed, only the store (activity scope) remains
        val back = store.get(key)                // navigate back: the new page reads it
        assertEquals("cloud-u-linux", back.expanded)
        assertEquals("branches", back.panelFor("repo-b"))
        assertEquals(7, back.scrollIndex); assertEquals(42, back.scrollOffset)
        assertEquals("cloud", back.query); assertEquals(true, back.showFilters); assertEquals("app", back.logsTab)
        assertEquals(left, back)
    }

    @Test fun `a process death restores the same page from the saved text`() {
        val store = GitPageMemoryStore()
        store.put(key, left)
        val restored = GitPageMemoryStore.decode(store.encode())
        assertEquals(left, restored.get(key))
    }

    @Test fun `each drawn page keeps its own state`() {
        val store = GitPageMemoryStore()
        store.put(key, left)
        assertEquals(GitPageMemory(), store.get(GitPageMemoryStore.keyOf(GitView.CLOUD, GitPart.TABLE)))
        assertEquals(GitPageMemory(), store.get(GitPageMemoryStore.keyOf(GitView.ME, GitPart.SETUP)))
    }

    @Test fun `a sub-menu reopens only on the repo it was open for`() {
        assertEquals("", left.panelFor("repo-a"))
        assertEquals("branches", left.panelFor("repo-b"))
    }

    /** Negative controls: the assertions above are not vacuous. A fresh store, a page that never wrote, or unreadable saved text open fresh. */
    @Test fun `without the store the page opens fresh`() {
        assertNotEquals(left, GitPageMemoryStore().get(key))
        assertEquals("", GitPageMemoryStore().get(key).expanded)
        assertEquals(GitPageMemory(), GitPageMemoryStore.decode("{not json").get(key))
        assertEquals(GitPageMemory(), GitPageMemoryStore.decode(null).get(key))
    }
}
