package com.diegonmarcos.cloudlib.rclone.unison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #1084 what the Sync tab remembers: a conflict found by a scheduled run is still there after a restart, and a kill mid-write keeps the old file. */
class UnisonStateStoreTest {
    private val dir = Files.createTempDirectory("unison-state").toFile()
    private val file = File(dir, "unison/state.json")
    private fun state(id: String = "docs", conflicts: List<String> = listOf("a.txt")) = UnisonStateStore.PairState(
        id, 1_790_000_000, "scheduled", "0 transferred · ${conflicts.size} conflict(s) · 0 failed", 1,
        conflicts.map { UnisonOutput.Conflict(it, "contents changed on both sides") }, listOf(UnisonOutput.Failure("x.bin", "denied")), listOf("line 1", "line 2"),
    )

    @Test fun aStateSurvivesAReopen() {
        UnisonStateStore(file).put(state())
        val back = UnisonStateStore(file).get("docs")!!
        assertEquals(state(), back)
    }

    @Test fun resolvingAConflictRemovesOnlyThatPath() {
        val s = UnisonStateStore(file)
        s.put(state(conflicts = listOf("a.txt", "b.txt")))
        s.resolved("docs", "a.txt")
        assertEquals(listOf("b.txt"), s.get("docs")!!.conflicts.map { it.path })
        s.resolved("nope", "x") // an unknown pair is not an error
    }

    @Test fun theLogIsBoundedToItsLastLines() {
        val s = UnisonStateStore(file)
        s.put(state().copy(log = (1..400).map { "l$it" }))
        val log = s.get("docs")!!.log
        assertEquals(UnisonStateStore.MAX_LOG, log.size); assertEquals("l400", log.last())
    }

    @Test fun pairsNoLongerDeclaredLoseTheirState() {
        val s = UnisonStateStore(file)
        s.put(state("docs")); s.put(state("old"))
        s.retain(setOf("docs"))
        assertTrue(s.get("old") == null && s.get("docs") != null)
    }

    @Test fun aCorruptFileReadsAsEmptyNotACrash() {
        file.parentFile.mkdirs(); file.writeText("{not json")
        assertTrue(UnisonStateStore(file).load().isEmpty())
        assertNull(UnisonStateStore(file).get("docs"))
    }

    @Test fun aLeftoverTempFileFromAKilledWriteDoesNotHideTheLastGoodFile() {
        val s = UnisonStateStore(file)
        s.put(state())
        File(file.parentFile, "state.json.tmp").writeText("{half a wri")
        assertEquals(1, s.get("docs")!!.conflicts.size)
    }
}
