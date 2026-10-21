package com.diegonmarcos.clouddrive

import com.diegonmarcos.clouddrive.sync.StoreLegs
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The legacy CloudDrive clones are imported into an empty store by the app, once, verified, never by a manual cp. */
class LegacyImportTest {
    private lateinit var tmp: File
    private lateinit var legacy: File
    private lateinit var store: File
    private val isRepo: (File) -> Boolean = { StoreMigration.isClone(it) }

    @Before fun setUp() { tmp = Files.createTempDirectory("legacy-import").toFile(); legacy = File(tmp, "CloudDrive/git").apply { mkdirs() }; store = File(tmp, "store/git") }
    @After fun tearDown() { tmp.deleteRecursively() }

    private fun clone(parent: File, name: String, complete: Boolean = true) {
        val d = File(parent, name); File(d, ".git").mkdirs()
        if (complete) { File(d, ".git/HEAD").writeText("ref: refs/heads/main\n"); File(d, ".git/refs/heads").mkdirs(); File(d, ".git/refs/heads/main").writeText("abcdef1234567\n"); File(d, "README.md").writeText("x") }
    }

    @Test fun anEmptyStoreIsFilledFromTheLegacyClonesAndTheLegacyIsKept() {
        clone(legacy, "a"); clone(legacy, "b")
        val out = LegacyImport.run(LegacyImport.plan(legacy, store, isRepo), StoreLegs.FileCopier(), isRepo)
        assertEquals(listOf("a", "b"), out.map { it.name })
        assertTrue(out.all { it.ok })
        assertTrue(File(store, "a/README.md").isFile)
        assertTrue("the legacy copy is never deleted", File(legacy, "a/README.md").isFile)
    }

    @Test fun runningItAgainImportsNothing() {
        clone(legacy, "a")
        LegacyImport.run(LegacyImport.plan(legacy, store, isRepo), StoreLegs.FileCopier(), isRepo)
        assertTrue(LegacyImport.plan(legacy, store, isRepo).isEmpty())
    }

    @Test fun aHuskIsNotImportedAndAnExistingStoreCloneIsNotTouched() {
        clone(legacy, "husk", complete = false); clone(legacy, "have"); clone(store, "have")
        File(store, "have/mine.txt").writeText("keep")
        val plan = LegacyImport.plan(legacy, store, isRepo)
        assertTrue(plan.isEmpty())
        assertEquals("keep", File(store, "have/mine.txt").readText())
    }

    @Test fun aFailedCopyIsReportedNotSwallowedAndDoesNotStopTheRest() {
        clone(legacy, "a"); clone(legacy, "b")
        val flaky = object : StoreLegs.Copier {
            val real = StoreLegs.FileCopier()
            override fun copy(from: String, to: String, replace: Boolean) = if (from.endsWith("/a")) "disk full" else real.copy(from, to, replace)
            override fun remove(path: String) = real.remove(path)
        }
        val out = LegacyImport.run(LegacyImport.plan(legacy, store, isRepo), flaky, isRepo)
        assertFalse(out.first { it.name == "a" }.ok)
        assertTrue(out.first { it.name == "a" }.line().contains("disk full"))
        assertTrue(out.first { it.name == "b" }.ok)
    }
}
