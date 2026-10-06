package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserBookmarkOps
import com.diegonmarcos.superapp.browser.BrowserFavourites
import com.diegonmarcos.superapp.browser.FavSeed
import com.diegonmarcos.superapp.browser.FavSeedItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #893 Fav = the bookmarks: the seed's merge rules and the view toggle. */
class BrowserFavouritesTest {

    private val seed = FavSeed(1, listOf(
        FavSeedItem("Gitea", "https://git.diegonmarcos.com", "Public"),
        FavSeedItem("Kg", "http://kg-store:8001", "Private (mesh)"),
    ))

    @Test
    fun `first run adds every seed entry into its group folder`() {
        val (list, seen) = BrowserFavourites.merge(emptyList(), seed, emptySet(), 5L)
        assertEquals(setOf("Public", "Private (mesh)"), list.map { it.folder }.toSet())
        assertEquals(setOf("https://git.diegonmarcos.com", "http://kg-store:8001"), seen)
    }

    @Test
    fun `a second run changes nothing, a user's edit is kept, a deleted entry stays deleted`() {
        var (list, seen) = BrowserFavourites.merge(emptyList(), seed, emptySet(), 5L)
        list = BrowserBookmarkOps.add(list, BrowserBookmark("https://git.diegonmarcos.com", "My git", "mine", 9L))   // edited
        list = BrowserBookmarkOps.remove(list, "http://kg-store:8001")                                                // deleted
        val again = BrowserFavourites.merge(list, seed, seen, 6L)
        assertEquals(list, again.first)
        assertEquals("My git", again.first.first { it.url == "https://git.diegonmarcos.com" }.title)
        assertTrue(again.first.none { it.url == "http://kg-store:8001" })
    }

    @Test
    fun `a newer seed adds only its new entries`() {
        val (list, seen) = BrowserFavourites.merge(emptyList(), seed, emptySet(), 5L)
        val v2 = FavSeed(2, seed.items + FavSeedItem("Dagu", "https://workflows.diegonmarcos.com", "Public"))
        val (next, _) = BrowserFavourites.merge(list, v2, seen, 7L)
        assertEquals(3, next.size)
        assertEquals("https://workflows.diegonmarcos.com", next.first { it.title == "Dagu" }.url)
    }

    @Test
    fun `a bookmark the user already has is not duplicated by the seed`() {
        val mine = listOf(BrowserBookmark("https://git.diegonmarcos.com", "Mine", "", 1L))
        val (list, _) = BrowserFavourites.merge(mine, seed, emptySet(), 5L)
        assertEquals(1, list.count { it.url == "https://git.diegonmarcos.com" })
        assertEquals("Mine", list.first { it.url == "https://git.diegonmarcos.com" }.title)
    }

    @Test
    fun `the view is list or grid, defaults to list, and toggles`() {
        assertEquals("list", BrowserFavourites.normView(null))
        assertEquals("list", BrowserFavourites.normView("junk"))
        assertEquals("grid", BrowserFavourites.normView("grid"))
        assertEquals("grid", BrowserFavourites.toggled("list"))
        assertEquals("list", BrowserFavourites.toggled("grid"))
    }

    @Test
    fun `sections put the top level first and the folders sorted, and tiles get a letter`() {
        val l = listOf(BrowserBookmark("u1", "b", "Z"), BrowserBookmark("u2", "a", ""), BrowserBookmark("u3", "c", "A"))
        assertEquals(listOf("", "A", "Z"), BrowserFavourites.sections(l).map { it.first })
        assertEquals("G", BrowserFavourites.letter("gitea", "https://x"))
        assertEquals("X", BrowserFavourites.letter("", "https://x.example"))
    }

    @Test
    fun `the seed parses from the baked json and drops blanks and duplicates`() {
        val o = JSONObject("""{"version":3,"items":[{"title":"A","url":"https://a"},{"url":""},{"title":"A2","url":"https://a"}]}""")
        val s = FavSeed.parse(o)
        assertEquals(3, s.version)
        assertEquals(1, s.items.size)
        assertEquals(FavSeed.EMPTY, FavSeed.parse(null))
    }
}
