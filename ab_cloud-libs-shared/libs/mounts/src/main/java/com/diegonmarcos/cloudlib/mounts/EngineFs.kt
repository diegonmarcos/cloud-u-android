package com.diegonmarcos.cloudlib.mounts

import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One mount, as the browser sees it ([RemoteFs]), over the mounts engine (#871 move 12): ftp / ftps / sftp /
 * ssh / webdav run in Cloud-Lib-Mounts-Engine.apk, which holds the open connection between steps by handle.
 * This class holds the handle, the spec and the secret; every step is one call.
 *
 * THE CONNECTION CAN BE LOST WITHOUT THE APP KNOWING: the engine is a separate process, and Android may stop
 * it between two taps. A step answered "unknown handle" opens the connection again, once, with the secret
 * this instance still holds, and repeats the step; the browser never sees it.
 *
 * Files cross as PATHS in the shared store (both this app and the engine hold All-files access): a download
 * lands where the caller said, an upload reads where the caller put the file. A step on a shared-storage path
 * while the engine lacks the grant raises the sentence that says what to grant.
 */
class EngineFs internal constructor(
    private val transport: MountTransport,
    private val spec: MountSpec,
    private val secret: MountSecret,
    private val knownHosts: File,
    private val readText: (String) -> String?,
) : RemoteFs {

    @Volatile private var handle: String? = null

    /** Opens the connection now, so a refused login or an unreachable host is the caller's exception at open. */
    internal fun connect() { transport.session().use { ensure(it) } }

    override fun list(path: String): List<RemoteEntry> =
        MountWire.json.decodeFromString(ListSerializer(RemoteEntry.serializer()), step("list", JSONObject().put("path", path)) ?: "[]")

    override fun download(path: String, into: File, onProgress: (Long) -> Unit) {
        into.parentFile?.mkdirs()
        step("download", JSONObject().put("path", path).put("into", into.absolutePath))
        onProgress(into.length())
    }

    override fun upload(local: File, path: String, onProgress: (Long) -> Unit) {
        step("upload", JSONObject().put("local", local.absolutePath).put("path", path))
        onProgress(local.length())
    }

    override fun mkdir(path: String) { step("mkdir", JSONObject().put("path", path)) }
    override fun delete(path: String, isDir: Boolean) { step("delete", JSONObject().put("path", path).put("is_dir", isDir)) }
    override fun rename(from: String, to: String) { step("rename", JSONObject().put("from", from).put("to", to)) }

    /** Only SSH mounts run commands; the engine answers no result for the rest. */
    override fun exec(command: String): String? = step("exec", JSONObject().put("command", command))

    override fun close() {
        val h = handle ?: return
        handle = null
        runCatching { transport.session().use { it.closeHandle(JSONObject().put("handle", h)) } }
    }

    private fun ensure(s: MountTransport.Session): String {
        handle?.let { return it }
        val a = s.open(MountWire.openRequest(spec, secret, knownHosts, readText))
        failIfRefused(a, "open")
        if (a.has("known_hosts")) { knownHosts.parentFile?.mkdirs(); knownHosts.writeText(a.getString("known_hosts")) }
        return a.getString("handle").also { handle = it }
    }

    private fun failIfRefused(a: JSONObject, what: String) {
        if (!a.optBoolean("ok", false)) throw IOException(a.optString("error").ifBlank { "the mounts engine refused $what" })
    }

    /** One step: its result text (reassembled from chunks), or null when the engine answered no result. */
    private fun step(verb: String, args: JSONObject): String? = transport.session().use { s ->
        fun ask(): JSONObject = s.fs(JSONObject().put("handle", ensure(s)).put("verb", verb).put("args", args))
        var a = ask()
        if (!a.optBoolean("ok", false) && a.optString("error").startsWith(MountWire.UNKNOWN_HANDLE)) { handle = null; a = ask() }
        failIfRefused(a, verb)
        if (!a.has("result")) return@use null
        val out = StringBuilder(a.optString("result"))
        var more = a.optBoolean("more", false)
        val id = a.optString("id")
        while (more) {
            val c = s.chunk(JSONObject().put("id", id).put("offset", out.length))
            failIfRefused(c, "the rest of $verb")
            out.append(c.optString("result")); more = c.optBoolean("more", false)
        }
        out.toString()
    }
}
