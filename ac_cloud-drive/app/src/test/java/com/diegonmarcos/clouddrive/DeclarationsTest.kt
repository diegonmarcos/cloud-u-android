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
        assertEquals(listOf("files", "apps", "sync", "backups", "configs"), tabs.map { it.id })
        assertTrue(tabs.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        assertTrue(tabs.map { it.id }.contains(ui["default_tab"].toString().trim('"')))
    }

    @Test fun everyDeclaredIconIsInTheCatalog() {
        val tabs = Declarations.parseTabs(section("tabs"))
        val sync = Declarations.parseSync(section("sync"))
        val files = Declarations.parseFiles(section("files"))
        val names = Declarations.iconNames(tabs, sync, files)
        assertTrue(names.size >= 10)
        val unknown = names.filterNot { IconCatalog.knows(it) }
        assertEquals("icons declared but unknown to IconCatalog: $unknown", emptyList<String>(), unknown)
        assertTrue(IconCatalog.knows(ui["icons"]!!.jsonObject["_default"].toString().trim('"')))
        assertFalse(IconCatalog.knows("no-such-glyph"))
    }

    @Test fun syncPagesAndPeriods() {
        val sync = Declarations.parseSync(section("sync"))
        assertEquals(listOf("git", "rclone", "mounts"), sync.pages.map { it.id })
        assertTrue(sync.gitPeriodsMinutes.isNotEmpty())
        assertTrue("every period is at or above WorkManager's floor", sync.gitPeriodsMinutes.all { it >= 15 })
        assertEquals(sync.gitPeriodsMinutes.sorted(), sync.gitPeriodsMinutes)
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
        val connections = Declarations.parseConnections(File(data, "drive-connections.json").readText())
        assertTrue(connections.size >= 10); assertTrue(connections.all { it.status.isNotBlank() })
        val family = Declarations.parseGitFamily(File(data, "drive-git-repos.json").readText())
        assertNotNull(family.upstream)
        assertTrue(family.repos.any { it.name == "cloud-data-my-ai-memory" && it.private })
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
        assertTrue(Declarations.parseSync("").pages.isEmpty())
        assertEquals("name", Declarations.parseFiles("").defaultSort)
        assertEquals("", Declarations.decode(""))
        assertEquals("[1]", Declarations.decode(java.util.Base64.getEncoder().encodeToString("[1]".toByteArray())))
    }
}
