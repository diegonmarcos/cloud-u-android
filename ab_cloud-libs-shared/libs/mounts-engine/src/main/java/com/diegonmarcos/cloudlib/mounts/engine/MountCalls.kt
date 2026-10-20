package com.diegonmarcos.cloudlib.mounts.engine

import com.diegonmarcos.cloudlib.mounts.MountSecret
import com.diegonmarcos.cloudlib.mounts.MountSpec
import com.diegonmarcos.cloudlib.mounts.MountWire
import com.diegonmarcos.cloudlib.mounts.RemoteEntry
import com.diegonmarcos.cloudlib.mounts.RemoteFs
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's answers, as JSON in and JSON out (the wire is IMountsEngine.call(method, request), the names are
 * MountWire's). No Android in here: the service hands in the scratch dir and the all-files check, the JVM suite
 * a local WebDAV server (or a fake [opener]).
 *
 * Every answer is {"ok":true,...} or {"ok":false,"error":...}; nothing throws across the binder.
 *   info    {}                                  -> {version, files_access, open}
 *   open    {spec, password, passphrase, key?, known_hosts}      -> {handle, known_hosts?}
 *   fs      {handle, verb, args}                -> {result?, more?, id?}
 *   chunk   {id, offset}                        -> {result, more}      the rest of an over-long result
 *   close   {handle}                            -> {}
 *
 * THE CREDENTIALS ARE THE CALLER'S, for the call that opens a connection: the password or passphrase is a value on
 * this call's stack, an ssh key and known_hosts are written to a private scratch directory and deleted as soon as
 * the connection is made, and a known_hosts that a first contact extended is handed back so the caller stores it.
 * What this object keeps between calls is the authenticated connection, by handle, until it is closed or has been
 * idle for [IDLE_MS]; the engine process may be stopped by Android at any time, and a handle it no longer knows
 * answers "unknown handle" so the caller opens the connection again.
 */
internal class MountCalls(
    private val scratch: File,
    private val engineVersion: String,
    private val filesAccess: () -> Boolean,
    private val opener: (MountSpec, MountSecret, File) -> RemoteFs = { spec, secret, hosts -> MountFsFactory.open(spec, secret, hosts) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val fs: RemoteFs, @Volatile var last: Long)

    private val connections = ConcurrentHashMap<String, Entry>()

    /** Over-long results (a big listing) wait here for `chunk`: one binder transaction carries about 1 MB. */
    private val pending = ConcurrentHashMap<String, String>()

    fun info(): String = JSONObject().put("ok", true).put("version", engineVersion).put("files_access", filesAccess())
        .put("open", connections.size).toString()

    fun open(req: JSONObject): String {
        reap()
        val dir = File(scratch, "mount-" + UUID.randomUUID())
        try {
            val spec = MountWire.json.decodeFromString(MountSpec.serializer(), req.getString("spec"))
            val secret = MountSecret(text(req, "password"), text(req, "passphrase"))
            dir.mkdirs(); ownerOnly(dir, directory = true)
            val sent = req.optString("known_hosts")
            val hosts = File(dir, "known_hosts"); hosts.writeText(sent); ownerOnly(hosts)
            var effective = spec
            if (spec.keyPath.isNotBlank()) {
                val key = File(dir, "id"); key.writeText(req.getString("key")); ownerOnly(key)
                effective = spec.copy(keyPath = key.absolutePath)
            }
            val fs = opener(effective, secret, hosts)
            val handle = UUID.randomUUID().toString()
            connections[handle] = Entry(fs, clock())
            val out = JSONObject().put("ok", true).put("handle", handle)
            val now = runCatching { hosts.readText() }.getOrDefault(sent)
            if (now != sent) out.put("known_hosts", now)
            return out.toString()
        } catch (t: Throwable) {
            return fail(t.message ?: t.javaClass.simpleName)
        } finally {
            dir.deleteRecursively()
        }
    }

    fun fs(req: JSONObject): String {
        reap()
        val verb = req.optString("verb")
        if (verb !in MountWire.VERBS) return fail("mounts verb '$verb' is not one this engine runs (${MountWire.VERBS.joinToString(", ")})")
        val entry = connections[req.optString("handle")]
            ?: return fail("${MountWire.UNKNOWN_HANDLE}: the connection is gone (the engine restarted or dropped it after being idle)")
        val a = req.optJSONObject("args") ?: JSONObject()
        for (key in listOf("into", "local")) {
            if (a.has(key) && touchesSharedStorage(File(a.getString(key))) && !filesAccess())
                return fail("the mounts engine has no All files access, so it cannot read or write the shared storage — Store ▸ Access ▸ Android Perms ▸ Grant missing")
        }
        try {
            val result: String? = synchronized(entry) {
                entry.last = clock()
                val fs = entry.fs
                when (verb) {
                    "list" -> MountWire.json.encodeToString(ListSerializer(RemoteEntry.serializer()), fs.list(a.getString("path")))
                    "download" -> { fs.download(a.getString("path"), File(a.getString("into"))); null }
                    "upload" -> { fs.upload(File(a.getString("local")), a.getString("path")); null }
                    "mkdir" -> { fs.mkdir(a.getString("path")); null }
                    "delete" -> { fs.delete(a.getString("path"), a.optBoolean("is_dir", false)); null }
                    "rename" -> { fs.rename(a.getString("from"), a.getString("to")); null }
                    "exec" -> fs.exec(a.getString("command"))
                    else -> throw IllegalArgumentException("mounts verb '$verb' has no implementation")
                }
            }
            val out = JSONObject().put("ok", true)
            if (result == null) return out.toString()
            if (result.length <= CHUNK) return out.put("result", result).toString()
            val id = UUID.randomUUID().toString()
            pending[id] = result
            return out.put("result", result.substring(0, CHUNK)).put("more", true).put("id", id).toString()
        } catch (t: Throwable) {
            return fail(t.message ?: t.javaClass.simpleName)
        }
    }

    fun chunk(req: JSONObject): String {
        val id = req.optString("id")
        val whole = pending[id] ?: return fail("chunk: unknown or finished id")
        val from = req.optInt("offset", 0).coerceIn(0, whole.length)
        val to = minOf(whole.length, from + CHUNK)
        val more = to < whole.length
        if (!more) pending.remove(id)
        return JSONObject().put("ok", true).put("result", whole.substring(from, to)).put("more", more).toString()
    }

    fun close(req: JSONObject): String {
        connections.remove(req.optString("handle"))?.let { runCatching { it.fs.close() } }
        return JSONObject().put("ok", true).toString()
    }

    /** Close the connections nobody has used for [IDLE_MS]. */
    private fun reap() {
        val cutoff = clock() - IDLE_MS
        for ((h, e) in connections) if (e.last < cutoff && connections.remove(h, e)) runCatching { e.fs.close() }
    }

    /** A JSON null or an absent field is no secret (a key-auth mount has no password). */
    private fun text(req: JSONObject, key: String): String? = if (req.isNull(key)) null else req.getString(key)

    private fun fail(why: String) = JSONObject().put("ok", false).put("error", why).toString()

    private fun ownerOnly(f: File, directory: Boolean = false) {
        f.setReadable(false, false); f.setWritable(false, false); if (directory) f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true); if (directory) f.setExecutable(true, true)
    }

    companion object {
        const val CHUNK = 400_000
        const val IDLE_MS = 15 * 60 * 1000L

        /** True when [f] is in the shared store: only then does the all-files grant matter. */
        fun touchesSharedStorage(f: File): Boolean {
            val p = f.path
            return p.startsWith("/storage/") || p.startsWith("/sdcard") || p.startsWith("/mnt/") || p.startsWith("/data/media/")
        }
    }
}
