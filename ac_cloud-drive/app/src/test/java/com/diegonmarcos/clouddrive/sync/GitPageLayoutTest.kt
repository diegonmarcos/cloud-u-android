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

    @Test fun `the column header is sticky and is drawn below the sign-in cards`() {
        assertTrue("the header must be a stickyHeader", src.contains("stickyHeader(key = \"table-header\")"))
        assertTrue("the table block is placed after the way cards", src.indexOf("GitWayCard(") < src.lastIndexOf("tableBlock()"))
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
