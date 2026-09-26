package com.diegonmarcos.clouddrive

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #579 THE ONE reader of the build-time declarations. app/build.gradle bakes every
 * declarative list this app renders — build.json::ui (tabs, configs pages, files
 * sections/places/filters), data/drive-*.json — into BuildConfig as base64 JSON; this file
 * decodes each ONCE into typed models and nothing else touches a BuildConfig blob.
 *
 * The parse functions take the JSON text, not BuildConfig, so the JVM suite
 * (DeclarationsTest) exercises the exact parser the phone runs against the
 * repository's own build.json — a declared icon name IconCatalog does not know,
 * or a filter with no rule, fails there before it fails on a device.
 */
object Declarations {

    data class TabDecl(val id: String, val label: String, val icon: String)
    data class PageDecl(val id: String, val label: String, val icon: String)
    /** #609 the Configs strip: three sub-pages (Git/Rclone/Mounts moved out to ui.sync.pages). */
    data class ConfigsDecl(val pages: List<PageDecl>)
    /** #609 the Sync strip: Git/Rclone/Mounts, moved back out of Configs, plus the per-repository period choices the Git page offers. */
    data class SyncDecl(val pages: List<PageDecl>, val gitPeriodsMinutes: List<Int>)

    /** #604 one of the four classes of volume; VolumesScreen dispatches on [id]. */
    data class VolumeClassDecl(val id: String, val label: String, val icon: String)

    /**
     * #604 the Volumes tab: the four classes, the host kinds Cloud-Machines groups by,
     * the data/drive-remotes.json types the S3 class owns, and where one fleet app's
     * shared folder lives ([constellationPath] carries a `<package>` placeholder).
     */
    data class VolumesDecl(
        val classes: List<VolumeClassDecl>,
        val machineKinds: List<String>,
        val s3RemoteTypes: Set<String>,
        val constellationPath: String,
    ) {
        /** The shared external files folder of [packageName], or blank when nothing is declared. */
        fun constellationPathOf(packageName: String): String =
            if (constellationPath.isBlank() || packageName.isBlank()) "" else constellationPath.replace("<package>", packageName)
    }

    /** #604 a fleet app Volumes ▸ Cloud-Constellation lists; the package is resolved at build time. */
    data class ConstellationAppDecl(val label: String, val icon: String, val packageName: String)

    /**
     * #604 a folder↔folder sync rule. [direction] is upload | download | bidirectional;
     * [scheduleMinutes] is declarative only (build.json::_doc_sync_rules says why).
     */
    data class SyncRuleDecl(
        val id: String,
        val localPath: String,
        val remoteName: String,
        val remotePath: String,
        val direction: String,
        val scheduleMinutes: Int?,
        val enabled: Boolean,
        val notes: String,
    ) {
        /** `remote:path` — the rclone side of the rule, composed once. */
        val remoteLeg: String get() = "$remoteName:$remotePath"
    }

    /** #603 a section header of the Files navigator; [PlaceDecl.section] names one of these. */
    data class SectionDecl(val id: String, val label: String, val icon: String)

    /** kind: shared_root | external_root | public_dir (dir = Environment.DIRECTORY_<dir>) | external_path (path relative to shared storage). */
    data class PlaceDecl(val id: String, val label: String, val icon: String, val kind: String, val section: String, val dir: String, val path: String, val hero: Boolean)

    data class FilterDecl(val id: String, val label: String, val icon: String, val mime: String?, val mimePrefix: String?, val extensions: Set<String>) {
        /** Folders always pass so the tree stays walkable; `all` (no rule) passes everything. */
        fun matches(isDirectory: Boolean, mimeType: String, extension: String): Boolean {
            if (isDirectory) return mime == null || mime == "inode/directory" || (mimePrefix == null && extensions.isEmpty())
            if (mime == null && mimePrefix == null && extensions.isEmpty()) return true
            if (mime != null && mimeType == mime) return true
            if (mimePrefix != null && mimeType.startsWith(mimePrefix)) return true
            return extension.lowercase() in extensions
        }
    }

    data class FilesDecl(
        val sections: List<SectionDecl>,
        val places: List<PlaceDecl>,
        val sortKeys: List<String>,
        val defaultSort: String,
        val filters: List<FilterDecl>,
        val archiveExtensions: Set<String>,
        val textExtensions: Set<String>,
        val defaultDualPane: Boolean,
        val tabsPerPaneMax: Int,
    )

    /** #603 [routeTab]/[routePage] non-blank ⇒ the tile stays in the app and selects that declared tab and sub-page. */
    data class AppTileDecl(val label: String, val icon: String, val packageName: String, val fallbackUrl: String, val routeTab: String, val routePage: String)
    /** [machine] is #604's host class — vm | pc | phone, or `container` (the default) for the container mesh. */
    data class ConnectionDecl(val name: String, val kind: String, val endpoint: String, val auth: String, val vm: String, val status: String, val scope: String, val notes: String, val reason: String, val uri: String, val machine: String)
    data class GitInstanceDecl(val name: String, val kind: String, val org: String, val host: String, val port: Int?, val reachable: Boolean, val reach: String)
    data class GitRepoDecl(val name: String, val label: String, val githubOwner: String, val giteaOwner: String, val private: Boolean, val seed: Boolean, val notes: String)
    data class GitFamilyDecl(val instances: List<GitInstanceDecl>, val repos: List<GitRepoDecl>) {
        val upstream: GitInstanceDecl? get() = instances.firstOrNull { it.kind == "upstream" && it.host.isNotBlank() }
        /** `https://<upstream host>/<github_owner>/<name>.git` — the #575 composition, once. */
        fun cloneUrl(repo: GitRepoDecl): String? = upstream?.let { "https://${it.host}/${repo.githubOwner}/${repo.name}.git" }
    }
    data class MirrorJobDecl(val name: String, val source: String, val destination: String, val delete: Boolean, val notes: String)
    data class RemoteDecl(val name: String, val type: String, val purpose: String, val endpoint: String, val host: String, val auth: String, val status: String, val reason: String, val notes: String, val declaredToPhone: Boolean)
    data class RcloneJobDecl(val name: String, val kind: String, val source: String, val destination: String, val schedule: String, val notes: String)

    // ── the baked declarations, decoded once ───────────────────────────────

    val tabs: List<TabDecl> by lazy { parseTabs(decode(BuildConfig.UI_TABS_B64)) }
    val defaultTab: String get() = BuildConfig.UI_DEFAULT_TAB
    val configs: ConfigsDecl by lazy { parseConfigs(decode(BuildConfig.UI_CONFIGS_B64)) }
    val sync: SyncDecl by lazy { parseSync(decode(BuildConfig.UI_SYNC_B64)) }
    val files: FilesDecl by lazy { parseFiles(decode(BuildConfig.UI_FILES_B64)) }
    val volumes: VolumesDecl by lazy { parseVolumes(decode(BuildConfig.UI_VOLUMES_B64)) }
    val constellation: List<ConstellationAppDecl> by lazy { parseConstellation(decode(BuildConfig.UI_CONSTELLATION_B64)) }
    val syncRules: List<SyncRuleDecl> by lazy { parseSyncRules(decode(BuildConfig.SYNC_RULES_B64)) }
    val iconDefault: String get() = BuildConfig.UI_ICON_DEFAULT
    val apps: List<AppTileDecl> by lazy { parseApps(decode(BuildConfig.UI_APPS_B64)) }
    val connections: List<ConnectionDecl> by lazy { parseConnections(decode(BuildConfig.CONNECTIONS_B64)) }
    val gitFamily: GitFamilyDecl by lazy { parseGitFamily(decode(BuildConfig.GIT_REPOS_B64)) }
    val mirrorJobs: List<MirrorJobDecl> by lazy { parseMirrorJobs(decode(BuildConfig.MIRROR_JOBS_B64)) }
    val remotes: List<RemoteDecl> by lazy { parseRemotes(decode(BuildConfig.RCLONE_REMOTES_B64)) }
    val rcloneJobs: List<RcloneJobDecl> by lazy { parseRcloneJobs(decode(BuildConfig.RCLONE_JOBS_B64)) }
    /** #587 build.json::auth.applies → artifact section id → the key inside it that a drive sign-in applies. */
    val authApplies: Map<String, String> by lazy { parseAuthApplies(decode(BuildConfig.AUTH_APPLIES_B64)) }

    fun decode(b64: String): String = if (b64.isBlank()) "" else runCatching { String(Base64.getDecoder().decode(b64), Charsets.UTF_8) }.getOrDefault("")

    // ── parsers (pure; JVM-tested) ─────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun element(text: String): JsonElement? = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
    private fun JsonObject.str(key: String, default: String = ""): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: default
    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    private fun objects(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun parseTabs(text: String): List<TabDecl> = objects(element(text)).mapNotNull { o ->
        val id = o.str("id"); if (id.isBlank()) return@mapNotNull null
        TabDecl(id, o.str("label", id), o.str("icon"))
    }

    fun parseConfigs(text: String): ConfigsDecl {
        val o = element(text) as? JsonObject ?: return ConfigsDecl(emptyList())
        val pages = objects(o["pages"]).mapNotNull { p -> val id = p.str("id"); if (id.isBlank()) null else PageDecl(id, p.str("label", id), p.str("icon")) }
        return ConfigsDecl(pages)
    }

    /** #609 the Sync strip: Git/Rclone/Mounts, the mirror of parseConfigs. */
    fun parseSync(text: String): SyncDecl {
        val o = element(text) as? JsonObject ?: return SyncDecl(emptyList(), emptyList())
        val pages = objects(o["pages"]).mapNotNull { p -> val id = p.str("id"); if (id.isBlank()) null else PageDecl(id, p.str("label", id), p.str("icon")) }
        val periods = (o["git_periods_minutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it >= 15 } ?: emptyList()
        return SyncDecl(pages, periods)
    }

    fun parseFiles(text: String): FilesDecl {
        val o = element(text) as? JsonObject ?: return FilesDecl(emptyList(), emptyList(), listOf("name"), "name", emptyList(), emptySet(), emptySet(), true, 6)
        val sections = objects(o["sections"]).mapNotNull { s ->
            val id = s.str("id"); if (id.isBlank()) null else SectionDecl(id, s.str("label", id), s.str("icon"))
        }
        val places = objects(o["places"]).mapNotNull { p ->
            val id = p.str("id"); if (id.isBlank()) return@mapNotNull null
            PlaceDecl(id, p.str("label", id), p.str("icon"), p.str("kind"), p.str("section"), p.str("dir"), p.str("path"), p.bool("hero"))
        }
        val filters = objects(o["filters"]).mapNotNull { f ->
            val id = f.str("id"); if (id.isBlank()) return@mapNotNull null
            FilterDecl(id, f.str("label", id), f.str("icon"), f.str("mime").ifBlank { null }, f.str("mime_prefix").ifBlank { null }, f.strings("extensions").map { it.lowercase() }.toSet())
        }
        val sortKeys = o.strings("sort_keys").ifEmpty { listOf("name") }
        return FilesDecl(
            sections = sections,
            places = places,
            sortKeys = sortKeys,
            defaultSort = o.str("default_sort", sortKeys.first()),
            filters = filters,
            archiveExtensions = o.strings("archive_extensions").map { it.lowercase() }.toSet(),
            textExtensions = o.strings("text_extensions").map { it.lowercase() }.toSet(),
            defaultDualPane = o.bool("default_dual_pane", true),
            tabsPerPaneMax = (o.int("tabs_per_pane_max") ?: 6).coerceAtLeast(1),
        )
    }

    fun parseVolumes(text: String): VolumesDecl {
        val o = element(text) as? JsonObject ?: return VolumesDecl(emptyList(), emptyList(), emptySet(), "")
        val classes = objects(o["classes"]).mapNotNull { c ->
            val id = c.str("id"); if (id.isBlank()) null else VolumeClassDecl(id, c.str("label", id), c.str("icon"))
        }
        return VolumesDecl(classes, o.strings("machine_kinds"), o.strings("s3_remote_types").toSet(), o.str("constellation_path"))
    }

    fun parseConstellation(text: String): List<ConstellationAppDecl> = objects(element(text)).mapNotNull { a ->
        val label = a.str("label"); if (label.isBlank()) return@mapNotNull null
        ConstellationAppDecl(label, a.str("icon"), a.str("package"))
    }

    fun parseSyncRules(text: String): List<SyncRuleDecl> = objects(element(text)).mapNotNull { r ->
        val id = r.str("id"); if (id.isBlank()) return@mapNotNull null
        SyncRuleDecl(id, r.str("local_path"), r.str("remote_name"), r.str("remote_path"), r.str("direction", DIRECTION_BIDIRECTIONAL), r.int("schedule_minutes"), r.bool("enabled", true), r.str("notes"))
    }

    fun parseApps(text: String): List<AppTileDecl> = objects(element(text)).mapNotNull { a ->
        val label = a.str("label"); if (label.isBlank()) return@mapNotNull null
        val route = a["route"] as? JsonObject
        AppTileDecl(label, a.str("icon"), a.str("package"), a.str("fallback_url"), route?.str("tab") ?: "", route?.str("page") ?: "")
    }

    fun parseConnections(text: String): List<ConnectionDecl> = objects(element(text)).mapNotNull { c ->
        val name = c.str("name"); if (name.isBlank()) return@mapNotNull null
        ConnectionDecl(name, c.str("kind"), c.str("endpoint"), c.str("auth"), c.str("vm"), c.str("status"), c.str("scope"), c.str("notes"), c.str("reason"), c.str("uri"), c.str("machine", MACHINE_CONTAINER))
    }

    fun parseGitFamily(text: String): GitFamilyDecl {
        val o = element(text) as? JsonObject ?: return GitFamilyDecl(emptyList(), emptyList())
        val instances = objects(o["instances"]).mapNotNull { i ->
            val name = i.str("name"); if (name.isBlank()) return@mapNotNull null
            GitInstanceDecl(name, i.str("kind"), i.str("org"), i.str("host"), i.int("port"), i.bool("reachable"), i.str("reach"))
        }
        val repos = objects(o["repos"]).mapNotNull { r ->
            val name = r.str("name"); if (name.isBlank()) return@mapNotNull null
            GitRepoDecl(name, r.str("label", name), r.str("github_owner"), r.str("gitea_owner"), r.bool("private"), r.bool("seed"), r.str("notes"))
        }
        return GitFamilyDecl(instances, repos)
    }

    fun parseMirrorJobs(text: String): List<MirrorJobDecl> = objects(element(text)).mapNotNull { j ->
        val name = j.str("name"); if (name.isBlank()) return@mapNotNull null
        MirrorJobDecl(name, j.str("source"), j.str("destination"), j.bool("delete"), j.str("notes"))
    }

    fun parseRemotes(text: String): List<RemoteDecl> = objects(element(text)).mapNotNull { r ->
        val name = r.str("name"); if (name.isBlank()) return@mapNotNull null
        RemoteDecl(name, r.str("type"), r.str("purpose"), r.str("endpoint"), r.str("host"), r.str("auth"), r.str("status"), r.str("reason"), r.str("notes"), r["rclone"] is JsonObject)
    }

    fun parseRcloneJobs(text: String): List<RcloneJobDecl> = objects(element(text)).mapNotNull { j ->
        val name = j.str("name"); if (name.isBlank()) return@mapNotNull null
        RcloneJobDecl(name, j.str("kind"), j.str("source"), j.str("destination"), j.str("schedule"), j.str("notes"))
    }

    fun parseAuthApplies(text: String): Map<String, String> {
        val o = element(text) as? JsonObject ?: return emptyMap()
        return o.entries.asSequence().filterNot { it.key.startsWith("_") }
            .mapNotNull { (id, v) -> (v as? JsonObject)?.str("key")?.takeIf { it.isNotBlank() }?.let { id to it } }
            .toMap()
    }

    /** Every icon name the declarations use — what test-drive-shell.sh and DeclarationsTest hold IconCatalog to. */
    fun iconNames(tabs: List<TabDecl>, configs: ConfigsDecl, files: FilesDecl, volumes: VolumesDecl? = null, sync: SyncDecl? = null): Set<String> =
        (tabs.map { it.icon } + configs.pages.map { it.icon } + files.sections.map { it.icon } + files.places.map { it.icon } + files.filters.map { it.icon } +
            (volumes?.classes?.map { it.icon } ?: emptyList()) + (sync?.pages?.map { it.icon } ?: emptyList())).filter { it.isNotBlank() }.toSet()

    /** #604 the machine class a connection without a declared `machine` belongs to: the container mesh. */
    const val MACHINE_CONTAINER = "container"
    const val DIRECTION_UPLOAD = "upload"
    const val DIRECTION_DOWNLOAD = "download"
    const val DIRECTION_BIDIRECTIONAL = "bidirectional"

    /** #603 the seed set of the shared store: the PUBLIC repositories marked `seed` in the manifest. */
    val seedRepos: List<GitRepoDecl> get() = gitFamily.repos.filter { it.seed && !it.private }
}
