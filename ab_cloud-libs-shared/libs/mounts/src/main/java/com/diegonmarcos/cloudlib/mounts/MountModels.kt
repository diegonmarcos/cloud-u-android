package com.diegonmarcos.cloudlib.mounts

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The connection kinds this engine speaks. `ssh` is SFTP plus a remote-command channel. */
enum class MountType(val scheme: String, val defaultPort: Int, val label: String) {
    SFTP("sftp", 22, "SFTP"),
    SSH("ssh", 22, "SSH (sftp + commands)"),
    FTP("ftp", 21, "FTP"),
    FTPS("ftps", 21, "FTPS (explicit TLS)"),
    WEBDAV("dav", 80, "WebDAV (http)"),
    WEBDAVS("davs", 443, "WebDAV (https)");

    companion object {
        fun fromScheme(s: String): MountType? = values().firstOrNull { it.scheme == s.lowercase() }
    }
}

/**
 * One mount as the user declared it. The secret (password or key passphrase)
 * is NOT here — it lives in [MountCredentialStore] keyed by [id]. `keyPath`
 * is an SSH private key file for sftp/ssh; empty means password auth.
 */
@Serializable
data class MountSpec(
    val id: String,
    val name: String,
    val type: MountType,
    val host: String,
    val port: Int,
    val username: String = "",
    val path: String = "/",
    val keyPath: String = "",
    /** Declared by the host app's build-time data (cloud-drive's connections file); not deletable here. */
    val declared: Boolean = false,
) {
    val uri: String get() = MountUri.format(this)
}

/** One directory entry on a remote. */
data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modifiedEpochMillis: Long)

/**
 * `scheme://user@host:port/path` ↔ [MountSpec]. Pure Kotlin, tested; the
 * form the host app's connection catalogue and the add dialog both speak.
 */
object MountUri {
    fun parse(text: String, id: String = "", name: String = ""): MountSpec? {
        val t = text.trim()
        val schemeEnd = t.indexOf("://"); if (schemeEnd <= 0) return null
        val type = MountType.fromScheme(t.substring(0, schemeEnd)) ?: return null
        var rest = t.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val path = if (slash >= 0) rest.substring(slash).ifEmpty { "/" } else "/"
        rest = if (slash >= 0) rest.substring(0, slash) else rest
        var user = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) { user = rest.substring(0, at); rest = rest.substring(at + 1) }
        var host = rest; var port = type.defaultPort
        val colon = rest.lastIndexOf(':')
        if (colon >= 0 && !rest.startsWith("[")) {
            host = rest.substring(0, colon); port = rest.substring(colon + 1).toIntOrNull() ?: return null
        } else if (rest.startsWith("[")) {   // [ipv6]:port
            val close = rest.indexOf(']'); if (close < 0) return null
            host = rest.substring(1, close)
            val after = rest.substring(close + 1)
            if (after.startsWith(":")) port = after.substring(1).toIntOrNull() ?: return null
        }
        if (host.isBlank()) return null
        val realId = id.ifBlank { idFor(type, host, port, user, path) }
        return MountSpec(realId, name.ifBlank { "$user@$host".trimStart('@') }, type, host, port, user, path, "")
    }

    fun format(m: MountSpec): String {
        val hostPart = if (m.host.contains(':')) "[${m.host}]" else m.host
        val portPart = if (m.port != m.type.defaultPort) ":${m.port}" else ""
        val userPart = if (m.username.isNotEmpty()) "${m.username}@" else ""
        return "${m.type.scheme}://$userPart$hostPart$portPart${if (m.path.startsWith("/")) m.path else "/" + m.path}"
    }

    fun idFor(type: MountType, host: String, port: Int, user: String, path: String): String {
        var h = 1125899906842597L
        for (c in "${type.scheme}|$host|$port|$user|$path") h = 31 * h + c.code
        return "mount-" + java.lang.Long.toHexString(h)
    }
}

/** The mount list as one JSON file (pure Kotlin; the host hands in `File(filesDir, "mounts/mounts.json")`). */
class MountStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = ListSerializer(MountSpec.serializer())

    fun load(): List<MountSpec> = if (!file.isFile) emptyList() else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyList() }

    fun save(mounts: List<MountSpec>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, mounts))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    fun upsert(m: MountSpec): List<MountSpec> { val next = load().filterNot { it.id == m.id } + m; save(next); return next }
    fun remove(id: String): List<MountSpec> { val next = load().filterNot { it.id == id }; save(next); return next }

    /** The host's declared connections replace the previous declared set; user-added mounts survive. */
    fun declare(mounts: List<MountSpec>): List<MountSpec> {
        val next = mounts.map { it.copy(declared = true) } + load().filterNot { it.declared }
        save(next); return next
    }
}
