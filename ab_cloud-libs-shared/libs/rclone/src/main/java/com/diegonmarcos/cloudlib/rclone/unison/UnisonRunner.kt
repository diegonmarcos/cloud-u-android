package com.diegonmarcos.cloudlib.rclone.unison

import com.diegonmarcos.cloudlib.rclone.RcloneTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #1084 runs a declared Unison pair THROUGH THE ENGINE (the pinned static unison lives in
 * Cloud-Lib-Rclone-Engine.apk and is exec'd there, as rclone is). The client's part is: generate the
 * profile from the declaration, hand over the SSH credentials of the pair's mount for the length of
 * one call, stream the engine's lines, and fold them into a [UnisonOutput.Result]. A resolution is one
 * more scoped run ([UnisonResolve]); keep-both adds the engine's file copy.
 *
 * Blocking calls run on the caller's thread; [sync] and [resolve] return a [Handle] at once.
 * An absent or too-old engine is a sentence in the result, never an exception.
 */
class UnisonRunner(
    private val transport: RcloneTransport,
    /** The SSH credentials for a pair's SSH peer (from the phone's mounts store), or null when none are set up. */
    private val ssh: (UnisonConfig.Pair) -> SshAccess?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** What the engine needs to reach a peer; handed over for one call and not stored there. */
    data class SshAccess(val keyPath: String = "", val passphrase: String = "", val password: String = "")

    class Handle internal constructor(private val stop: () -> Unit, private val exit: CountDownLatch, private val result: () -> UnisonOutput.Result) {
        private val cancelledFlag = AtomicBoolean(false)
        val cancelled: Boolean get() = cancelledFlag.get()
        fun cancel() { cancelledFlag.set(true); stop() }
        fun await(): UnisonOutput.Result { exit.await(); return result() }
    }

    /** Null when the engine can run Unison, else the sentence that says what to install or update. */
    fun unavailable(): String? = transport.unavailable()

    private fun requestFor(pair: UnisonConfig.Pair, extra: List<String>): JSONObject? {
        val req = JSONObject()
            .put("profile", pair.profile)
            .put("profile_text", UnisonProfile.generate(pair))
            .put("extra", JSONArray(extra))
            .put("roots", JSONArray(UnisonProfile.roots(pair)))
        val peer = pair.peer
        if (peer is UnisonConfig.Peer.Ssh) {
            val access = ssh(pair) ?: return null
            req.put("ssh", JSONObject()
                .put("host", peer.host).put("port", peer.port).put("user", peer.user)
                .put("key_path", access.keyPath).put("passphrase", access.passphrase).put("password", access.password)
                .put("server_cmd", peer.serverCommand))
        }
        return req
    }

    /** Sync the pair; [onLine] gets each readable log line, [onDone] the folded result. */
    fun sync(pair: UnisonConfig.Pair, onLine: (String) -> Unit, onDone: (UnisonOutput.Result) -> Unit): Handle =
        execute(pair, emptyList(), onLine, onDone)

    /** The engine call underneath [sync] and [resolve]'s scoped run. */
    private fun execute(pair: UnisonConfig.Pair, extra: List<String>, onLine: (String) -> Unit, onDone: (UnisonOutput.Result) -> Unit): Handle {
        val exit = CountDownLatch(1)
        var result = UnisonOutput.parse(emptyList(), -1)
        fun refused(why: String): Handle {
            result = UnisonOutput.parse(listOf("Fatal error: $why"), -1)
            Thread { runCatching { onLine("[engine] $why") }; runCatching { onDone(result) }; exit.countDown() }.apply { isDaemon = true; start() }
            return Handle({}, exit) { result }
        }
        val req = requestFor(pair, extra) ?: return refused("no SSH credentials for ${(pair.peer as UnisonConfig.Peer.Ssh).host}: add a key or password under Sync ▸ Mounts")
        val session = try { transport.session() } catch (e: IOException) { return refused(e.message ?: "the engine is not available") }
        val id = try {
            val a = session.unisonStart(req)
            if (!a.optBoolean("ok", false)) {
                val why = a.optString("error").ifBlank { "the engine refused the call" }
                session.close()
                return refused(if (why.contains("unknown method")) "the sync engine is too old for Unison — update Cloud-Lib-Rclone-Engine from Store ▸ Cloud Constellation ▸ Libs" else why)
            }
            a.getString("id")
        } catch (e: Exception) {
            runCatching { session.close() }
            return refused(e.message ?: e.javaClass.simpleName)
        }
        val handle = Handle({ runCatching { session.unisonCancel(JSONObject().put("id", id)) } }, exit) { result }
        Thread {
            val all = mutableListOf<String>()
            var cursor = 0
            var rc = -1
            var back = false
            try {
                while (true) {
                    val p = session.unisonPoll(JSONObject().put("id", id).put("cursor", cursor))
                    if (!p.optBoolean("ok", false)) throw IOException(p.optString("error").ifBlank { "the engine lost the run" })
                    val lines = p.getJSONArray("lines")
                    for (i in 0 until lines.length()) {
                        val raw = lines.getString(i)
                        all += raw
                        UnisonOutput.display(raw)?.let(onLine)
                    }
                    cursor = p.getInt("cursor")
                    if (p.getBoolean("done")) { rc = p.optInt("rc", -1); back = p.optBoolean("rolled_back", false); break }
                    if (lines.length() == 0) Thread.sleep(POLL_IDLE_MS)
                }
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                all += "Fatal error: $why"; onLine("[engine] $why")
            } finally { runCatching { session.close() } }
            result = UnisonOutput.parse(all, if (handle.cancelled) -2 else rc, back)
            runCatching { onDone(result) }
            exit.countDown()
        }.apply { isDaemon = true; start() }
        return handle
    }

    /**
     * Resolve one conflicted [path] with [choice]: a scoped run, then (keep-both) the copy of what it
     * replaced. [onDone] gets the run's result; a failed copy is a failure line, never a silent loss.
     */
    fun resolve(pair: UnisonConfig.Pair, path: String, choice: UnisonResolve.Choice, onLine: (String) -> Unit, onDone: (UnisonOutput.Result) -> Unit): Handle {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(clock()))
        val plan = UnisonResolve.plan(pair, path, choice, stamp)
        return execute(pair, plan.extra, onLine) { r ->
            if (plan.keepCopyRoot == null || r.rc !in 0..1) { onDone(r); return@execute }
            // exit 1 is allowed: other files may still be conflicted, this path was scoped
            val copied = try {
                transport.session().use { s ->
                    s.unisonKeepCopy(JSONObject().put("profile", pair.profile).put("root", plan.keepCopyRoot).put("path", path).put("suffix", plan.suffix))
                }
            } catch (e: IOException) { JSONObject().put("ok", false).put("error", e.message ?: "engine") }
            if (copied.optBoolean("ok", false)) {
                onLine("[keep both] kept your version as ${copied.optString("copied")}")
                onDone(r)
            } else {
                val why = copied.optString("error").ifBlank { "the copy failed" }
                onLine("[keep both] $why")
                onDone(r.copy(failures = r.failures + UnisonOutput.Failure(path, "keep both: $why")))
            }
        }
    }

    private companion object { const val POLL_IDLE_MS = 400L }
}
