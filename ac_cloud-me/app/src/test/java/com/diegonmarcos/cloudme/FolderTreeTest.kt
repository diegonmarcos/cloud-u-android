package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1350 RoadMap: the shipped vault folder tree and the rows the screen draws from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderTreeTest {
    private val sections = FolderTree.parse(BuroLlcTestData.text("data/repos/roadmap-tree.json"))
    private fun all(f: Folder): List<Folder> = listOf(f) + f.children.flatMap(::all)
    private val folders = sections.flatMap { s -> s.folders.flatMap(::all) }

    @Test fun `RoadMap is a bar section whose one page draws the shipped tree`() {
        val s = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui").getJSONArray("sections")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.getString("id") == "roadmap" }
        assertEquals("RoadMap", s.getString("label"))
        val page = JSONArray(BuroLlcTestData.text("data/ui/roadmap/folders.json")).getJSONObject(0)
        assertEquals("folder_tree", page.getString("id")); assertEquals("roadmap-tree.json", page.getString("asset"))
    }

    @Test fun `separators are section titles, never folders, and own their numbered folders`() {
        assertEquals(listOf("Section 0", "Section 1", "Section 2"), sections.map { it.title })
        assertTrue(folders.none { Regex("""^\d+_+$""").matches(it.name) })
        for ((i, s) in sections.withIndex()) assertTrue(s.folders.isNotEmpty() && s.folders.all { it.name.startsWith("$i.") })
        assertEquals(listOf("0.0_Inbox", "0.1_Backlog", "0.2_Resources"), sections[0].folders.map { it.name })
        assertEquals("1.9 sorts before 1.10", listOf("1.9_HT_CAD-Simulators", "1.10___42"), sections[1].folders.map { it.name }.filter { it.startsWith("1.9") || it.startsWith("1.10") })
    }

    @Test fun `tooling folders and dot folders are hidden at every depth`() {
        val hidden = setOf("0_docs", "0_hub", "1_workflows", "_dotfiles")
        assertTrue(folders.none { it.name in hidden || it.path.split('/').any { p -> p.startsWith(".") } })
        assertTrue(folders.size > 50)
    }

    @Test fun `rows start collapsed, open one folder, and Expand all opens every container`() {
        val closed = FolderTree.rows(sections, emptySet())
        assertEquals(sections.size + sections.sumOf { it.folders.size }, closed.size)
        val inbox = sections[0].folders.first()
        assertTrue(inbox.children.isNotEmpty())
        val opened = FolderTree.rows(sections, setOf(inbox.path))
        assertEquals(closed.size + inbox.children.size, opened.size)
        assertTrue(opened.any { it is FolderRow.Item && it.depth == 1 && it.folder == inbox.children.first() })
        val every = FolderTree.rows(sections, FolderTree.containers(sections))
        assertEquals(sections.size + folders.size, every.size)
        assertFalse(FolderTree.parse("""{"_schema":"other"}""").isNotEmpty())
        assertTrue(FolderTree.parse("not json").isEmpty())
    }
}
