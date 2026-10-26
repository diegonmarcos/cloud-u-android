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

    private val dir = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync")
    private val fsync = File(dir, "FsyncPage.kt").readText()
    private val mounts = File(dir, "MountsPage.kt").readText()
    private val tableUi = File(dir, "RepoTableUi.kt").readText()

    @Test fun `#1195 the actions bar sits above the column header and the header is the sticky item right below it`() {
        val block = src.substring(src.indexOf("fun LazyListScope.tableBlock()"))
        val bar = block.indexOf("RepoBulkBar(")
        val header = block.indexOf("stickyHeader(key = \"table-header\")")
        val rows = block.indexOf("items(sortedEntries")
        assertTrue("bulk bar, then sticky header, then rows", bar in 0 until header && header < rows)
        assertTrue("the bar is its own item, not inside the sticky header", block.substring(0, header).contains("item(key = \"table-bulk\")"))
        assertTrue("the bar carries the Settings gear", tableUi.contains("PageActionsBar(") && tableUi.contains("AutoChecks.Page.GIT, prefs"))
    }

    @Test fun `#1195 git fsync and mounts share one actions bar with the settings sheet and one tap-to-open row`() {
        assertTrue(tableUi.contains("PageActionsBar("))
        assertEquals("two Fsync bars (backup, folders)", 2, Regex("PageActionsBar\\(").findAll(fsync).count())
        assertEquals("two Mounts bars (rclone, fuse)", 2, Regex("PageActionsBar\\(").findAll(mounts).count())
        assertTrue(File(dir, "TablePage.kt").readText().contains("AutoSettingsSheet(page, prefs)"))
        assertEquals(1, Regex("fun AutoSettingsSheet\\(").findAll(File(dir, "TablePage.kt").readText()).count())
        assertTrue(fsync.contains("TapCard(") && mounts.contains("TapCard(") && File(dir, "UnisonScreens.kt").readText().contains("onClick = onToggle"))
        for (f in listOf(fsync, mounts)) assertTrue("tap again closes the panel", f.contains("TapPanel.toggle(open"))
        assertEquals("one rule for the toggle", "if (open == tapped) \"\" else tapped", File(dir, "TablePage.kt").readText().lines().first { it.contains("fun toggle") }.substringAfter("=").trim())
    }

    @Test fun `#1195 a repository that is not cloned opens its panel on a tap`() {
        assertFalse("the row is clickable whether or not it is cloned", src.contains(".clickable(enabled = repo != null"))
        assertTrue(src.contains("expanded == e.key && cloned == null"))
        assertTrue(src.contains("GitRepoNotClonedBox("))
        for (needle in listOf("R.string.repo_act_open_web", "R.string.sync_clone_into_store", "R.string.repo_act_copy_url", "RepoActions.PRS", "RepoActions.needsClone(it)", "R.string.repo_not_cloned_why")) assertTrue(needle, src.contains(needle))
    }

    @Test fun `#1195 the pages open without disk, process or network work in their composables`() {
        // StrictMode-style, read off the source: the composables only read the caches; every walk, probe and request is in a coordinator behind withContext(IO).
        val banned = listOf("walkTopDown", "walkFileTree", "listFiles(", "HttpURLConnection", ".readText(", "ProcessBuilder", "Runtime.getRuntime")
        for ((name, text) in mapOf("GitReposScreen" to src, "FsyncPage" to fsync, "MountsPage" to mounts, "RepoTableUi" to tableUi, "TablePage" to File(dir, "TablePage.kt").readText())) {
            banned.forEach { assertFalse("$name must not call $it on the main thread", text.contains(it)) }
        }
        assertFalse("the seed report is read off the main thread", src.contains("remember { StoreSeedWorker.lastReport"))
        val coord = File(dir, "GitSyncCoordinator.kt").readText()
        assertTrue("sizes are walked on IO", coord.contains("withContext(Dispatchers.IO) {\n                    val dirs = registry.load()"))
        assertTrue("opening the Git page loads the registry and runs only the plan", coord.contains("runPlan(AutoChecks.plan(autoSettings(), AutoChecks.Trigger.OPEN))"))
        val pageOpen = coord.substring(coord.indexOf("fun onPageOpened()"), coord.indexOf("fun onTick()"))
        assertFalse("no unconditional status read on open", pageOpen.contains("refresh("))
        for (f in listOf(fsync, mounts)) assertTrue("the deep probes sit behind the settings", f.contains("AutoRefreshEffects("))
        assertTrue(File(dir, "RepoDiskSize.kt").readText().contains("check(!StartupGuard.onMainThread())"))
    }

    @Test fun `#1195 the size column is in the table header and cells`() {
        assertTrue(tableUi.contains("RepoColumn.SIZE_DISK -> R.string.repo_col_size_disk"))
        assertTrue(tableUi.contains("RepoColumn.SIZE_DISK to RepoTable.size(r.sizeDiskBytes)"))
        assertTrue(src.contains("diskSizes[File(it.path).absolutePath]?.bytes"))
    }
}
