package com.diegonmarcos.cloudlib.rclone.unison

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1084 THE UNISON SYNC PAIRS, as the host app declares them (cloud-drive's data/drive-sync-rules.json
 * `unison`, baked into the APK). The declaration is the ONE source of truth: [UnisonProfile] generates
 * the Unison profile from it, the scheduler reads [UnisonPair.scheduleMinutes] from it, and nothing
 * else names a root, an ignore rule or a conflict policy.
 *
 * Pure Kotlin + org.json, so the JVM suite exercises exactly what the phone parses.
 */
object UnisonConfig {

    /** How the second root is reached. */
    sealed class Peer {
        /** A fleet host or desktop over SSH on the WireGuard mesh, by its `<host>.app` mesh name. */
        data class Ssh(val host: String, val port: Int, val user: String, val path: String, val mount: String, val serverCommand: String) : Peer()
        /** Another folder on this phone (e.g. shared storage ↔ another volume). */
        data class Local(val path: String) : Peer()
    }

    /**
     * One folder pair. [prefer] is the declared conflict policy: null = ask (every conflict waits for a
     * keep-A / keep-B / keep-both decision in the UI), "a" / "b" = that side wins, "newer" / "older" = by
     * modification time; a policy that wins automatically REQUIRES [backup] (a silent overwrite is refused
     * at parse time, never at run time).
     */
    data class Pair(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val rootA: String,
        val peer: Peer,
        val scheduleMinutes: Int?,
        val ignore: List<String>,
        val batch: Boolean,
        val auto: Boolean,
        val fastcheck: String,
        val prefer: String?,
        val backup: Boolean,
        val maxBackups: Int,
        val times: Boolean,
        /** Scheduled runs wait for an unmetered network (a mesh sync can move gigabytes); a manual Sync now never waits. */
        val requireUnmetered: Boolean,
    ) {
        /** The archive/profile name: stable per pair, a file name in the engine. */
        val profile: String get() = id
    }

    data class Declared(val pairs: List<Pair>, val problems: List<String>)

    val FASTCHECK = setOf("default", "true", "false", "yes", "no")
    val PREFER = setOf("a", "b", "newer", "older")
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,40}")
    /** The mesh name rule: a fleet host is addressed as `<name>.app`, never a raw IP or a public name. */
    val MESH_HOST = Regex("[a-z0-9][a-z0-9-]*\\.app")
    /** An ignore directive in Unison's own grammar, so the profile can carry it verbatim. */
    private val IGNORE = Regex("(Name|Path|BelowPath|Regex) \\S.*")

    private fun JSONObject.str(k: String, d: String = ""): String = if (has(k) && !isNull(k)) optString(k, d) else d
    private fun JSONObject.boolOr(k: String, d: Boolean): Boolean = if (has(k) && !isNull(k)) optBoolean(k, d) else d
    private fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    /**
     * Parse the declaration. Every defect is a sentence in [Declared.problems] and that pair is left
     * out, so one bad pair never hides the rest and a bad pair never runs half-valid.
     */
    fun parse(text: String): Declared {
        if (text.isBlank()) return Declared(emptyList(), emptyList())
        val root = JSONObject(text)
        val d = root.optJSONObject("defaults") ?: JSONObject()
        val defaultIgnore = root.optJSONArray("ignore").strings()
        val problems = mutableListOf<String>()
        val pairs = mutableListOf<Pair>()
        val arr = root.optJSONArray("pairs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val id = p.str("id")
            fun bad(why: String) { problems += "unison pair '${id.ifBlank { "#$i" }}': $why" }
            if (!ID.matches(id)) { bad("id must be [a-z0-9-], 1..41 characters"); continue }
            if (pairs.any { it.id == id }) { bad("id is declared twice"); continue }
            val rootA = p.str("root_a")
            if (!rootA.startsWith("/") || '\n' in rootA || rootA.startsWith("/data/data/") || rootA.startsWith("/data/user/")) {
                bad("root_a must be an absolute folder outside any app's private data (the engine cannot open it): '$rootA'"); continue
            }
            val b = p.optJSONObject("root_b")
            if (b == null) { bad("root_b is missing"); continue }
            val peer: Peer = if (b.has("host")) {
                val host = b.str("host")
                val user = b.str("user")
                val path = b.str("path")
                if (!MESH_HOST.matches(host)) { bad("root_b.host '$host' is not a <name>.app mesh name"); continue }
                if (user.isBlank() || '@' in user || ' ' in user) { bad("root_b.user is missing or malformed"); continue }
                if (path.isBlank() || '\n' in path || path.contains("..")) { bad("root_b.path is missing or climbs"); continue }
                Peer.Ssh(host, b.optInt("port", 22), user, path, b.str("mount"), b.str("server_cmd", "unison -server"))
            } else {
                val path = b.str("path")
                if (!path.startsWith("/") || '\n' in path) { bad("root_b.path must be an absolute local folder"); continue }
                if (path == rootA) { bad("both roots are the same folder"); continue }
                Peer.Local(path)
            }
            val prefer = (if (p.has("prefer")) p.str("prefer") else d.str("prefer")).ifBlank { null }
            if (prefer != null && prefer !in PREFER) { bad("prefer '$prefer' is not one of ${PREFER.joinToString()}"); continue }
            val backup = p.boolOr("backup", d.boolOr("backup", true))
            if (prefer != null && !backup) { bad("prefer '$prefer' overwrites on its own, so backup must stay on (no silent overwrite)"); continue }
            val fast = (if (p.has("fastcheck")) p.str("fastcheck") else d.str("fastcheck", "default")).lowercase()
            if (fast !in FASTCHECK) { bad("fastcheck '$fast' is not one of ${FASTCHECK.joinToString()}"); continue }
            val maxBackups = if (p.has("max_backups")) p.optInt("max_backups") else d.optInt("max_backups", 5)
            if (maxBackups !in 1..50) { bad("max_backups must be 1..50"); continue }
            val schedule = p.intOrNull("schedule_minutes")
            if (schedule != null && schedule < 15) { bad("schedule_minutes below 15 (WorkManager's floor)"); continue }
            val ignore = (defaultIgnore + p.optJSONArray("ignore").strings()).distinct()
            val badIgnore = ignore.firstOrNull { !IGNORE.matches(it) || '\n' in it }
            if (badIgnore != null) { bad("ignore rule '$badIgnore' is not `Name|Path|BelowPath|Regex <pattern>`"); continue }
            pairs += Pair(
                id = id, name = p.str("name", id), enabled = p.boolOr("enabled", true), rootA = rootA.trimEnd('/').ifEmpty { "/" }, peer = peer,
                scheduleMinutes = schedule, ignore = ignore,
                batch = p.boolOr("batch", d.boolOr("batch", true)), auto = p.boolOr("auto", d.boolOr("auto", true)),
                fastcheck = fast, prefer = prefer, backup = backup, maxBackups = maxBackups, times = p.boolOr("times", d.boolOr("times", true)),
                requireUnmetered = p.boolOr("require_unmetered", d.boolOr("require_unmetered", true)),
            )
        }
        return Declared(pairs, problems)
    }
}
