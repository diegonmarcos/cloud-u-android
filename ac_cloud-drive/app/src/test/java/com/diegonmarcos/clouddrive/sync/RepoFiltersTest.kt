package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1256 the filter row, the persisted sort on every column and the Last commit column. */
class RepoFiltersTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val page by lazy { Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]) }

    private data class R(val name: String, val f: RepoFilters.Facts)
    private val rows = listOf(
        R("a", RepoFilters.Facts(true, RepoStatus.Kind.CLEAN, false, false, true)),
        R("b", RepoFilters.Facts(true, RepoStatus.Kind.DIRTY, true, true, false)),
        R("c", RepoFilters.Facts(false, RepoStatus.Kind.NOT_CLONED, null, false, null)),
    )
    private fun pick(sel: Map<String, String>) = RepoFilters.apply(rows, sel) { it.f }.map { it.name }

    @Test fun `every declared filter starts with all and every declared id has a meaning, both ways`() {
        assertTrue(page.filters.isNotEmpty())
        assertTrue(page.filters.all { it.options.first().id == RepoFilters.ALL })
        assertEquals(RepoFilters.HANDLED, page.filters.map { it.id }.toSet())
        assertEquals(listOf("all", "cloned", "not_cloned"), page.filters.first { it.id == "cloned" }.options.map { it.id })
        val kinds = RepoStatus.Kind.entries.map { it.name.lowercase() }
        assertTrue(page.filters.first { it.id == "status" }.options.drop(1).all { it.id in kinds })
    }

    @Test fun `filters combine`() {
        assertEquals(listOf("a", "b", "c"), pick(emptyMap()))
        assertEquals(listOf("a", "b"), pick(mapOf("cloned" to "cloned")))
        assertEquals(listOf("c"), pick(mapOf("cloned" to "not_cloned")))
        assertEquals(listOf("b"), pick(mapOf("cloned" to "cloned", "status" to "dirty")))
        assertEquals(listOf("b"), pick(mapOf("lfs" to "lfs")))
        assertEquals(listOf("a", "c"), pick(mapOf("visibility" to "public")))
        assertEquals(listOf("a"), pick(mapOf("hub" to "hub", "visibility" to "public")))
        assertEquals(listOf("b", "c"), pick(mapOf("hub" to "non_hub")))
        assertEquals("unlisted hides the registry rows", emptyList<String>(), pick(mapOf("unlisted" to "unlisted")))
        assertFalse(RepoFilters.showUnlisted(mapOf("unlisted" to "listed")))
        assertTrue(RepoFilters.showUnlisted(emptyMap()))
    }

    @Test fun `the selection and the sort persist as strings and come back`() {
        val sel = mapOf("cloned" to "cloned", "status" to "ahead", "lfs" to RepoFilters.ALL)
        assertEquals(mapOf("cloned" to "cloned", "status" to "ahead"), RepoFilters.decode(RepoFilters.encode(sel)))
        for (c in RepoColumn.entries) for (asc in listOf(true, false)) assertEquals(c to asc, RepoFilters.decodeSort(RepoFilters.encodeSort(c, asc)))
        assertEquals(RepoTable.DEFAULT_SORT to true, RepoFilters.decodeSort("NOPE:desc"))
        assertEquals(RepoTable.DEFAULT_SORT to true, RepoFilters.decodeSort(null))
    }

    private fun row(name: String, last: Long?) = RepoTableRow(name, false, null, null, null, null, null, null, lastCommitMs = last)

    @Test fun `Last commit is a sortable column, unknown sinks, asc and desc`() {
        assertEquals(RepoColumn.LAST_COMMIT, RepoColumn.entries.last())
        val l = listOf(row("x", 3000), row("y", null), row("z", 1000))
        assertEquals(listOf("z", "x", "y"), RepoTable.sort(l, RepoColumn.LAST_COMMIT, true) { it }.map { it.name })
        assertEquals(listOf("x", "z", "y"), RepoTable.sort(l, RepoColumn.LAST_COMMIT, false) { it }.map { it.name })
        assertEquals(RepoColumn.LAST_COMMIT to false, RepoTable.tap(RepoColumn.LAST_COMMIT, RepoColumn.LAST_COMMIT, true))
    }

    @Test fun `every column sorts both ways without losing a row`() {
        val l = listOf(row("b", 2), row("a", 1), row("c", null))
        for (c in RepoColumn.entries) for (asc in listOf(true, false)) assertEquals("$c $asc", setOf("a", "b", "c"), RepoTable.sort(l, c, asc) { it }.map { it.name }.toSet())
    }

    @Test fun `Last commit is the clone's HEAD when cloned, else the forge's pushed_at, printed relative`() {
        assertEquals(5L, RepoTable.lastCommit(true, 5, 9))
        assertEquals(9L, RepoTable.lastCommit(false, 5, 9))
        assertEquals(9L, RepoTable.lastCommit(true, null, 9))
        assertEquals(null, RepoTable.lastCommit(false, null, 0))
        val now = 10_000_000_000L
        assertEquals("—", RepoTable.age(null, now))
        assertEquals("now", RepoTable.age(now - 5_000, now))
        assertEquals("5m", RepoTable.age(now - 5 * 60_000, now))
        assertEquals("3h", RepoTable.age(now - 3 * 3_600_000, now))
        assertEquals("2d", RepoTable.age(now - 2 * 86_400_000L, now))
        val r = RepoTable.rowOf("n", false, false, null, RepoRemoteStats.Stats(1, 1, 1, false, "main", 1, pushedAtMs = 777))
        assertEquals(777L, r.lastCommitMs)
    }

    @Test fun `pushed_at is read from the repository answer and kept in the cache`() {
        val info = RepoRemoteStats.parseRepo("""{"size":3,"private":false,"default_branch":"main","pushed_at":"2026-10-01T00:00:00Z"}""")!!
        assertEquals(java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), info.pushedAtMs)
        val m = mapOf("o/r" to RepoRemoteStats.Stats(1, 2, 3, false, "main", 4, 5))
        assertEquals(m, RepoRemoteStats.Cache.decode(RepoRemoteStats.Cache.encode(m)))
        assertEquals(0L, RepoRemoteStats.isoMs("garbage"))
    }

    @Test fun `Activity Logs is declared and bounded`() {
        assertTrue(page.activityMax in 1..1000)
    }
}
