package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The explorer's inline search: the four declared scopes, the declared default chip, the in-place
 * filter and the bounded recursive walk, run as real code over a temp folder tree. Mutation proof:
 * test/test-drive-explorer-search.sh flips the default and the scope flags and expects this suite's
 * static twin to go red.
 */
class ExplorerSearchTest {
    private val root = File(System.getProperty("user.dir") ?: ".").let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val files = Declarations.parseFiles(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["files"].toString())
    private val decl = files.search
    private val mimes = FileOps.MimeResolver { null }
    private fun sc(id: String) = decl.scope(id)!!

    @Test fun fourScopesInDeclaredOrderAndDefaultIsFoldersHere() {
        assertEquals(listOf("folders_here", "folders_root", "all_here", "all_root"), decl.scopes.map { it.id })
        assertEquals(listOf("Only Folders Here", "Only Folders Root", "All From Here", "All From Root"), decl.scopes.map { it.label })
        assertEquals("folders_here", decl.defaultScope)
        assertEquals("folders_here", decl.initialScope?.id)
        assertEquals(40, decl.heightDp)
    }

    @Test fun defaultComesFromTheDeclarationNotTheOrder() {
        val text = """{"search":{"default_scope":"all_root","scopes":[{"id":"folders_here","recursive":false,"from":"here","folders_only":true},{"id":"all_root","recursive":true,"from":"root","folders_only":false}]}}"""
        assertEquals("all_root", Declarations.parseFiles(text).search.initialScope?.id)
        val unknown = text.replace("\"default_scope\":\"all_root\"", "\"default_scope\":\"nope\"")
        assertEquals("folders_here", Declarations.parseFiles(unknown).search.initialScope?.id)
    }

    @Test fun scopeFlagsMatchTheirNames() {
        with(sc("folders_here")) { assertEquals(false, recursive); assertEquals(false, fromRoot); assertEquals(true, foldersOnly) }
        with(sc("folders_root")) { assertEquals(true, recursive); assertEquals(true, fromRoot); assertEquals(true, foldersOnly) }
        with(sc("all_here")) { assertEquals(true, recursive); assertEquals(false, fromRoot); assertEquals(false, foldersOnly) }
        with(sc("all_root")) { assertEquals(true, recursive); assertEquals(true, fromRoot); assertEquals(false, foldersOnly) }
    }

    private fun tree(): Pair<File, File> {
        val top = Files.createTempDirectory("xs").toFile()
        val here = File(top, "work/photos").apply { mkdirs() }
        File(here, "Trip").mkdirs(); File(here, "trip.jpg").writeText("x"); File(here, "Other").mkdirs()
        File(here, "Trip/trip-day2").mkdirs(); File(here, "Trip/trip-notes.txt").writeText("x")
        File(top, "music/trip-songs").mkdirs(); File(top, "music/trip.mp3").writeText("x")
        return top to here
    }

    private fun run(start: File, q: String, id: String, bounds: ExplorerSearch.Bounds = ExplorerSearch.Bounds(300, 50_000, 24)): Pair<List<String>, ExplorerSearch.Stop> {
        val hits = mutableListOf<String>()
        val stop = ExplorerSearch.walk(start, q, sc(id), bounds, mimes, { false }) { hits += it.name }
        return hits.sorted() to stop
    }

    @Test fun onlyFoldersHereFiltersTheListingInPlace() {
        val (_, here) = tree()
        val entries = here.listFiles()!!.map { FileOps.describe(it, mimes, countChildren = false) }
        assertEquals(listOf("Trip"), ExplorerSearch.filterHere(entries, "TRIP", sc("folders_here")).map { it.name })
        assertEquals(listOf("Trip", "trip.jpg"), ExplorerSearch.filterHere(entries, "trip", sc("all_here")).map { it.name }.sorted())
        assertEquals(entries.size, ExplorerSearch.filterHere(entries, "  ", sc("folders_here")).size)
    }

    @Test fun recursiveScopesWalkFromHereOrFromRoot() {
        val (top, here) = tree()
        val roots = listOf(top.path)
        val start = { id: String -> File(ExplorerSearch.startOf(sc(id), here.path, roots)) }
        assertEquals(listOf("Trip", "trip-day2"), run(here, "trip", "folders_root").first)
        assertEquals(listOf("Trip", "trip-day2", "trip-songs"), run(start("folders_root"), "trip", "folders_root").first)
        assertEquals(listOf("Trip", "trip-day2", "trip-notes.txt", "trip.jpg"), run(start("all_here"), "trip", "all_here").first)
        assertEquals(listOf("Trip", "trip-day2", "trip-notes.txt", "trip-songs", "trip.jpg", "trip.mp3"), run(start("all_root"), "trip", "all_root").first)
        assertEquals(here.path, ExplorerSearch.startOf(sc("all_here"), here.path, roots))
        assertEquals(top.path, ExplorerSearch.startOf(sc("all_root"), here.path, roots))
        assertEquals("/", ExplorerSearch.storageRoot("/elsewhere/x", roots))
    }

    @Test fun walkIsBoundedAndCancellable() {
        val (top, _) = tree()
        assertEquals(ExplorerSearch.Stop.RESULTS, run(top, "trip", "all_root", ExplorerSearch.Bounds(2, 50_000, 24)).second)
        assertEquals(2, run(top, "trip", "all_root", ExplorerSearch.Bounds(2, 50_000, 24)).first.size)
        assertEquals(ExplorerSearch.Stop.SCANNED, run(top, "trip", "all_root", ExplorerSearch.Bounds(300, 3, 24)).second)
        assertTrue(run(top, "trip", "all_root", ExplorerSearch.Bounds(300, 50_000, 1)).first.isEmpty())
        var calls = 0
        val stop = ExplorerSearch.walk(top, "trip", sc("all_root"), ExplorerSearch.Bounds(300, 50_000, 24), mimes, { ++calls > 2 }) { }
        assertEquals(ExplorerSearch.Stop.CANCELLED, stop)
        assertEquals(ExplorerSearch.Stop.NONE, run(top, "", "all_root").second)
    }
}
