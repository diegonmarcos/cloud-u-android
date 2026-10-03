package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserBookmarkOps
import com.diegonmarcos.superapp.browser.BrowserDownload
import com.diegonmarcos.superapp.browser.BrowserDownloadIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** #802 I4 bookmark folder rules and the download index, as behaviour. */
class BrowserLibraryTest {

    private val q = BrowserBookmark("https://qwant.com", "Q", "search")
    private val d = BrowserBookmark("https://duckduckgo.com", "D", "search/engines")
    private val n = BrowserBookmark("https://news.ycombinator.com", "HN", "")

    @Test
    fun `adding a URL twice keeps one bookmark, with the newer folder and title`() {
        val once = BrowserBookmarkOps.add(emptyList(), q)
        val twice = BrowserBookmarkOps.add(once, q.copy(title = "Qwant", folder = " /web// "))
        assertEquals(1, twice.size)
        assertEquals("Qwant", twice[0].title)
        assertEquals("web", twice[0].folder)   // normalised
    }

    @Test
    fun `folders lists parents too`() {
        assertEquals(listOf("search", "search/engines"), BrowserBookmarkOps.folders(listOf(q, d, n)))
    }

    @Test
    fun `renaming a folder moves its whole subtree, and nothing else`() {
        val moved = BrowserBookmarkOps.moveFolder(listOf(q, d, n), "search", "find")
        assertEquals(listOf("find", "find/engines", ""), moved.map { it.folder })
        // A sibling whose name merely STARTS with the folder's is not inside it.
        val s2 = BrowserBookmark("https://x.org", "X", "searching")
        assertEquals("searching", BrowserBookmarkOps.moveFolder(listOf(s2), "search", "find")[0].folder)
    }

    @Test
    fun `deleting a folder deletes what is under it, and nothing else`() {
        val left = BrowserBookmarkOps.deleteFolder(listOf(q, d, n), "search")
        assertEquals(listOf(n), left)
        assertEquals(3, BrowserBookmarkOps.deleteFolder(listOf(q, d, n), " ").size)   // "" is never deletable
    }

    @Test
    fun `bookmarks and the download index survive a JSON round trip`() {
        val list = listOf(q, d, n)
        assertEquals(list, BrowserBookmarkOps.fromJson(BrowserBookmarkOps.toJson(list).toString()))
        val dl = listOf(BrowserDownload(7, "https://a/b.pdf", "docs/b.pdf", "application/pdf", 42))
        assertEquals(dl, BrowserDownloadIndex.fromJson(BrowserDownloadIndex.toJson(dl).toString()))
        assertEquals(emptyList<BrowserDownload>(), BrowserDownloadIndex.fromJson("not json"))
    }

    @Test
    fun `the index keeps one entry per id, newest first, capped`() {
        var idx = emptyList<BrowserDownload>()
        repeat(BrowserDownloadIndex.CAP + 5) { idx = BrowserDownloadIndex.add(idx, BrowserDownload(it.toLong(), "u$it", "f", "", 0)) }
        assertEquals(BrowserDownloadIndex.CAP, idx.size)
        assertEquals((BrowserDownloadIndex.CAP + 4).toLong(), idx[0].id)
        assertEquals("successful", BrowserDownloadIndex.status(8))
    }
}
