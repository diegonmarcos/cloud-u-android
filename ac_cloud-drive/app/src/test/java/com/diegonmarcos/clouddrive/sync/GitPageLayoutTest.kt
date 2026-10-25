package com.diegonmarcos.clouddrive.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Git page's layout contract, read off the source: one table, a sticky header below the sign-in cards, the bulk bar first, a closable menu. */
class GitPageLayoutTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val src = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt").readText()

    @Test fun `the Git page keeps only the table and the sign-in cards live in Configs`() {
        assertTrue("the cards are drawn only in the SETUP part", src.contains("if (setup) page.ways.forEach"))
        assertTrue("the host cards are drawn only in the SETUP part", src.contains("if (setup) Declarations.gitHosts.forEach"))
        assertTrue("the table is drawn only outside the SETUP part", src.contains("if (!setup) tableBlock()"))
        assertTrue("the hero and the pull actions are SETUP-only", Regex("if \\(setup\\) item \\{\\s+Row\\(Modifier\\.fillMaxWidth\\(\\)\\.testTag\\(DriveTags\\.SYNC_HERO\\)").containsMatchIn(src) && src.contains("if (setup) item {\n            Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_PULL_ALL)"))
        val cfg = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/configs/ConfigsScreen.kt").readText()
        assertTrue(cfg.contains("\"git\" -> GitReposScreen(") && cfg.contains("part = GitPart.SETUP"))
        val sync = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/SyncScreen.kt").readText()
        assertFalse("the Git page itself never asks for the SETUP part", sync.contains("GitPart.SETUP"))
    }

    @Test fun `the page scrolls in the shared frame, one bounded list, never a nested scroller`() {
        assertTrue(src.contains("GitPageFrame("))
        assertFalse("the screen must not open its own LazyColumn", src.contains("LazyColumn("))
        val frame = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/GitPageFrame.kt").readText()
        assertTrue(frame.contains("windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))"))
        assertEquals("exactly one list in the frame", 1, Regex("LazyColumn\\(").findAll(frame).count())
        assertFalse("no hardcoded dp in the frame", Regex("\\d\\.dp").containsMatchIn(frame))
    }

    @Test fun `the column header is sticky and is the single header`() {
        assertTrue("the header must be a stickyHeader", src.contains("stickyHeader(key = \"table-header\")"))
        assertEquals("exactly one header emission", 1, Regex("RepoTableHeader\\(sortColumn").findAll(src).count())
    }

    @Test fun `there is one table, no public and private groups`() {
        assertFalse(src.contains("personalGroups"))
        assertFalse(src.contains("GIT_GROUP_PRIVATE"))
        assertEquals("one rows block", 1, Regex("items\\(sortedEntries").findAll(src).count())
    }

    @Test fun `the bulk bar comes before the rows and covers the four commands`() {
        val block = src.substring(src.indexOf("fun LazyListScope.tableBlock()"))
        assertTrue(block.indexOf("RepoBulkBar(") in 0 until block.indexOf("items(sortedEntries"))
        assertTrue(block.indexOf("RepoSummaryLine(") in 0 until block.indexOf("items(sortedEntries"))
        for (b in listOf("CHECK_ALL", "SYNC_ALL", "CLONE_ALL", "STASH_PUSH_ALL")) assertTrue(b, block.contains("Bulk.$b"))
    }

    @Test fun `a tap toggles the menu through the pure rule and the menu has a close control`() {
        assertTrue(src.contains("expanded = RepoTable.toggle(expanded, e.key)"))
        assertTrue(src.contains("onClose = { expanded = \"\" }"))
    }
}
