package com.diegonmarcos.clouddrive

import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.files.FilesController
import com.diegonmarcos.clouddrive.files.Location
import com.diegonmarcos.clouddrive.files.PaneState
import com.diegonmarcos.clouddrive.files.PaneTab
import com.diegonmarcos.clouddrive.ui.IconCatalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #579 the declarations, parsed by the EXACT parser the phone runs, against THIS
 * repository's build.json and data files (the module dir is the working directory of
 * a Gradle unit test, so ../build.json is the app's own). A declared icon name the
 * catalog does not know, a filter with no rule, a tab without an id, a data file that
 * lost its shape — all fail here before they fail on a device.
 */
class DeclarationsTest {

    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val buildJson: JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject
    private val ui: JsonObject = buildJson["ui"]!!.jsonObject
    private fun section(key: String): String = ui[key].toString()

    @Test fun tabsDeclareTheFiveKnownShellTabs() {
        val tabs = Declarations.parseTabs(section("tabs"))
        // #603 Sync and Backups are no longer tabs: they are Configs sub-pages.
        assertEquals(listOf("files", "volumes", "home", "apps", "configs"), tabs.map { it.id })
        assertTrue(tabs.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        assertTrue(tabs.map { it.id }.contains(ui["default_tab"].toString().trim('"')))
    }

    @Test fun everyDeclaredIconIsInTheCatalog() {
        val tabs = Declarations.parseTabs(section("tabs"))
        val configs = Declarations.parseConfigs(section("configs"))
        val files = Declarations.parseFiles(section("files"))
        val names = Declarations.iconNames(tabs, configs, files)
        assertTrue(names.size >= 10)
        val unknown = names.filterNot { IconCatalog.knows(it) }
        assertEquals("icons declared but unknown to IconCatalog: $unknown", emptyList<String>(), unknown)
        assertTrue(IconCatalog.knows(ui["icons"]!!.jsonObject["_default"].toString().trim('"')))
        assertFalse(IconCatalog.knows("no-such-glyph"))
    }

    @Test fun configsPagesAndPeriods() {
        val configs = Declarations.parseConfigs(section("configs"))
        // #603 the six sub-pages, in declared order: Git/Rclone/Mounts came from the old Sync
        // tab, Backups from its own tab, General is #579's Configs page and Others is new.
        assertEquals(listOf("git", "rclone", "mounts", "backups", "general", "others"), configs.pages.map { it.id })
        assertTrue(configs.pages.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        assertTrue(configs.gitPeriodsMinutes.isNotEmpty())
        assertTrue("every period is at or above WorkManager's floor", configs.gitPeriodsMinutes.all { it >= 15 })
        assertEquals(configs.gitPeriodsMinutes.sorted(), configs.gitPeriodsMinutes)
    }

    @Test fun filesDeclaresTwoSectionsAndEveryPlaceNamesOne() {
        val files = Declarations.parseFiles(section("files"))
        assertEquals(2, files.sections.size)
        assertTrue("Emulated is a declared section", files.sections.any { it.id == "emulated" })
        assertTrue(files.sections.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        val ids = files.sections.map { it.id }.toSet()
        assertTrue("every place names a declared section", files.places.all { it.section in ids })
        // The #575 shared store is a section of its own, not one place among the emulated ones.
        val store = files.places.single { it.kind == "shared_root" }
        assertTrue(store.section != "emulated" && store.section in ids)
        assertEquals(1, files.places.count { it.section == store.section })
    }

    @Test fun filesPlacesFiltersAndSort() {
        val files = Declarations.parseFiles(section("files"))
        assertEquals("the shared store is the first, hero place", "shared_root", files.places.first().kind)
        assertTrue(files.places.first().hero)
        assertEquals(1, files.places.count { it.kind == "shared_root" })
        assertTrue(files.places.all { it.kind in setOf("shared_root", "external_root", "public_dir", "external_path") })
        assertTrue(files.places.filter { it.kind == "public_dir" }.all { it.dir.isNotBlank() })
        assertTrue(files.places.filter { it.kind == "external_path" }.all { it.path.isNotBlank() && !it.path.startsWith("/") })
        assertTrue(files.sortKeys.containsAll(listOf("name", "size", "modified", "type")))
        assertTrue(files.defaultSort in files.sortKeys)
        assertEquals("all", files.filters.first().id)
        files.filters.drop(1).forEach { f -> assertTrue("filter ${f.id} has a rule", f.mime != null || f.mimePrefix != null || f.extensions.isNotEmpty()) }
        assertTrue("zip" in files.archiveExtensions)
        assertTrue(files.tabsPerPaneMax >= 2)
    }

    @Test fun filterRulesMatchAsDeclared() {
        val files = Declarations.parseFiles(section("files"))
        val images = files.filters.first { it.id == "images" }
        assertTrue(images.matches(false, "image/jpeg", "jpg"))
        assertFalse(images.matches(false, "video/mp4", "mp4"))
        assertTrue("folders always pass", images.matches(true, "inode/directory", ""))
        val docs = files.filters.first { it.id == "documents" }
        assertTrue(docs.matches(false, "application/octet-stream", "PDF"))
        val all = files.filters.first { it.id == "all" }
        assertTrue(all.matches(false, "anything/else", "xyz"))
        val folders = files.filters.first { it.id == "folders" }
        assertTrue(folders.matches(true, "inode/directory", ""))
        assertFalse(folders.matches(false, "text/plain", "txt"))
    }

    @Test fun visibleEntriesApplyHiddenFilterAndSort() {
        val files = Declarations.parseFiles(section("files"))
        fun e(name: String, dir: Boolean = false, mime: String = "text/plain", size: Long = 1) =
            FileOps.Entry(Location.Local("/x/$name"), name, dir, size, 0, if (dir) "inode/directory" else mime, FileOps.extensionOf(name), name.startsWith("."))
        val entries = listOf(e("b.txt", size = 5), e("a.jpg", mime = "image/jpeg", size = 9), e(".secret"), e("dir", dir = true))
        val pane = PaneState(listOf(PaneTab(Location.Local("/x"))), filterId = "images")
        assertEquals(listOf("dir", "a.jpg"), FilesController.visibleEntries(pane, entries, files.filters).map { it.name })
        val all = pane.copy(filterId = "all", showHidden = true, sort = "size", descending = true)
        assertEquals(listOf("dir", "a.jpg", "b.txt", ".secret"), FilesController.visibleEntries(all, entries, files.filters).map { it.name })
    }

    @Test fun dataFilesKeepTheirShape() {
        val data = File(root, "data")
        val apps = Declarations.parseApps(Json.parseToJsonElement(File(data, "drive-apps.json").readText()).jsonObject["apps"].toString())
        assertTrue(apps.size >= 5); assertTrue(apps.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        // #603 GitSync and RSync are IN-APP routes into declared Configs sub-pages, never packages.
        val configs = Declarations.parseConfigs(section("configs"))
        val pageIds = configs.pages.map { it.id }
        listOf("GitSync", "RSync").forEach { label ->
            val tile = apps.single { it.label == label }
            assertEquals("a routed tile carries no package", "", tile.packageName)
            assertEquals("configs", tile.routeTab)
            assertTrue("$label routes to a declared sub-page (${tile.routePage})", tile.routePage in pageIds)
        }
        assertTrue("an ordinary tile has no route", apps.filter { it.routeTab.isBlank() }.size >= 5)
        val connections = Declarations.parseConnections(File(data, "drive-connections.json").readText())
        assertTrue(connections.size >= 10); assertTrue(connections.all { it.status.isNotBlank() })
        val family = Declarations.parseGitFamily(File(data, "drive-git-repos.json").readText())
        assertNotNull(family.upstream)
        assertTrue(family.repos.any { it.name == "cloud-data-my-ai-memory" && it.private })
        // #603 the store's first-run seed set: marked, public, and never a private repository —
        // an anonymous clone of one fails every time, so seeding it would bake a permanent red.
        val seeded = family.repos.filter { it.seed }
        assertTrue("the store would start empty", seeded.isNotEmpty())
        assertEquals("private repositories must never be seeded", emptyList<String>(), seeded.filter { it.private }.map { it.name })
        assertTrue(family.repos.filter { it.private }.none { it.seed })
        assertEquals("https://github.com/diegonmarcos/cloud-infra.git", family.cloneUrl(family.repos.first { it.name == "cloud-infra" }))
        val mirrors = Declarations.parseMirrorJobs(Json.parseToJsonElement(File(data, "drive-mirror-jobs.json").readText()).jsonObject["jobs"].toString())
        assertTrue(mirrors.isNotEmpty()); assertTrue("declared mirror paths are relative", mirrors.all { !it.source.startsWith("/") && !it.destination.startsWith("/") })
        val remotes = Declarations.parseRemotes(Json.parseToJsonElement(File(data, "drive-remotes.json").readText()).jsonObject["remotes"].toString())
        assertTrue(remotes.any { it.declaredToPhone })
        assertTrue(remotes.filter { it.status == "unreachable" }.all { it.reason.isNotBlank() })
        val jobs = Declarations.parseRcloneJobs(Json.parseToJsonElement(File(data, "drive-rclone-jobs.json").readText()).jsonObject["jobs"].toString())
        assertTrue(jobs.isNotEmpty())
    }

    @Test fun blankAndBrokenInputParseToEmpty() {
        assertTrue(Declarations.parseTabs("").isEmpty())
        assertTrue(Declarations.parseTabs("{not json").isEmpty())
        assertTrue(Declarations.parseConfigs("").pages.isEmpty())
        assertTrue(Declarations.parseFiles("").sections.isEmpty())
        assertEquals("name", Declarations.parseFiles("").defaultSort)
        assertEquals("", Declarations.decode(""))
        assertEquals("[1]", Declarations.decode(java.util.Base64.getEncoder().encodeToString("[1]".toByteArray())))
    }
}
