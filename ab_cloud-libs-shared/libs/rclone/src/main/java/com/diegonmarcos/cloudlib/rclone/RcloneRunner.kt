package com.diegonmarcos.cloudlib.rclone

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Runs rclone THROUGH THE ENGINE (#871): the pinned static binary lives in Cloud-Lib-Rclone-Engine.apk
 * and is exec'd there, in the engine's uid; this class is the same typed face the screens were
 * written against, over [RcloneTransport]. The rclone.conf stays HERE, in the app's private files
 * (remotes and their obscured secrets never leave the sandbox except for the length of one call):
 * every call carries its text, and a copy rclone rewrote (an OAuth refresh) is written back.
 *
 * Blocking; callers run it on IO. An absent, too-old or failing engine is an [IOException] naming
 * what to install ([unavailableReason]); [start] never throws, it ends the job with that sentence.
 */
class RcloneRunner(
    private val transport: RcloneTransport,
    val configFile: File,
    /** Where "download" lands files the host may then open: the shared store, declared as build.json::storage.shared_root (shared storage, not the store: the engine cannot open the store). */
    val downloadDir: File,
) {
    constructor(context: Context) : this(
        RcloneLink(context),
        File(context.filesDir, "rclone/rclone.conf"),
        File(Environment.getExternalStorageDirectory(), BuildConfig.HANDOFF_ROOT + "/rclone-downloads"),
    )

    /** Null when the engine can be called, else the sentence that says what to install or update. */
    val unavailableReason: String? get() = transport.unavailable()

    val isAvailable: Boolean get() = unavailableReason == null

    private val configLock = Any()

    /** A running rclone: its lines stream to the callbacks given to [start]; [cancel] stops it. */
    class Handle internal constructor(private val stop: () -> Unit, private val exit: java.util.concurrent.CountDownLatch, private val code: () -> Int) {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true; stop() }
        fun waitFor(): Int { exit.await(); return code() }
    }

    private fun configText(): String = synchronized(configLock) { if (configFile.isFile) configFile.readText() else "" }

    /** rclone rewrote the rclone.conf it was given (a refreshed token): keep it. */
    private fun keepConfig(text: String?) {
        if (text == null) return
        synchronized(configLock) {
            if (configFile.isFile && configFile.readText() == text) return
            configFile.parentFile?.mkdirs()
            val tmp = File(configFile.parentFile, configFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(configFile)) { configFile.writeText(text); tmp.delete() }
        }
    }

    private fun request(args: List<String>) = JSONObject().put("config", configText()).put("args", JSONArray(args))

    private fun JSONObject.checked(): JSONObject {
        if (!optBoolean("ok", false)) throw IOException(optString("error").ifBlank { "the rclone engine refused the call" })
        return this
    }

    /** Run to completion, capture stdout; stderr lines go to [onStderr]. Returns (exit code, stdout). */
    fun capture(args: List<String>, onStderr: (String) -> Unit = {}): Pair<Int, String> =
        transport.session().use { s ->
            val a = s.run(request(args)).checked()
            keepConfig(a.optString("config").takeIf { a.has("config") })
            a.optString("stderr").lineSequence().filter { it.isNotEmpty() }.forEach(onStderr)
            val out = StringBuilder(a.optString("stdout"))
            var more = a.optBoolean("more", false)
            val id = a.optString("id")
            while (more) {
                val c = s.outChunk(JSONObject().put("id", id).put("offset", out.length)).checked()
                out.append(c.optString("stdout")); more = c.optBoolean("more", false)
            }
            a.getInt("rc") to out.toString()
        }

    fun version(): String? = if (!isAvailable) null else runCatching { RcloneOutput.parseVersion(capture(listOf("version")).second) }.getOrNull()

    fun remotes(): List<RcloneRemote> = RcloneConfig.read(configFile)

    fun lsjson(remotePath: String): Result<List<RcloneEntry>> = runCatching {
        val errs = StringBuilder()
        val (rc, out) = capture(listOf("lsjson", "--no-mimetype", remotePath)) { errs.append(it).append('\n') }
        if (rc != 0) error(errs.toString().ifBlank { "rclone lsjson exited $rc" })
        RcloneOutput.parseLsjson(out)
    }

    /** `rclone obscure` — what rclone.conf expects for pass fields. */
    fun obscure(secret: String): String {
        val (rc, out) = capture(listOf("obscure", secret))
        check(rc == 0) { "rclone obscure failed ($rc)" }
        return out.trim()
    }

    /** Test a remote: list its root. */
    fun test(remoteName: String): Result<Int> = lsjson("$remoteName:").map { it.size }

    /**
     * Start a job with live JSON stats every second. Returns a handle at once; [onStats] and
     * [onLine] are called from the polling thread; [onExit] with the exit code when the job ends
     * (rclone: 0 ok, non-zero errors; -2 cancelled; -1 the engine could not run it, and the
     * sentence why went to [onLine]).
     */
    fun start(job: RcloneJob, onStats: (RcloneStats) -> Unit, onLine: (String) -> Unit, onExit: (Int) -> Unit): Handle {
        val base = job.arguments()
        val hook = legStaging ?: return startArgs(base, onStats, onLine, onExit)
        hook.refusal(base)?.let { why ->
            val exit = java.util.concurrent.CountDownLatch(1)
            Thread { Thread.sleep(REFUSED_DELAY_MS); onLine("[engine] $why"); onExit(-1); exit.countDown() }.apply { isDaemon = true; start() }
            return Handle({}, exit) { -1 }
        }
        val staged = hook.begin(base) ?: return startArgs(base, onStats, onLine, onExit)
        // A leg in the shared store: the engine cannot open it, so it works on a staged copy (see LegStaging).
        val exit = java.util.concurrent.CountDownLatch(1)
        val code = java.util.concurrent.atomic.AtomicInteger(-1)
        val inner = java.util.concurrent.atomic.AtomicReference<Handle?>(null)
        val handle = Handle({ inner.get()?.cancel() }, exit) { code.get() }
        Thread {
            val why = staged.prepare()
            if (why != null || handle.cancelled) {
                if (why != null) onLine("[engine] $why")
                staged.finish(-1)
                code.set(if (handle.cancelled) -2 else -1); onExit(code.get()); exit.countDown()
                return@Thread
            }
            inner.set(startArgs(staged.args, onStats, onLine) { rc ->
                val back = staged.finish(rc)
                if (back != null) onLine("[engine] $back")
                code.set(if (handle.cancelled) -2 else if (back != null && rc == 0) -1 else rc)
                onExit(code.get()); exit.countDown()
            })
            if (handle.cancelled) inner.get()?.cancel()
        }.apply { isDaemon = true; start() }
        return handle
    }

    /**
     * Jobs whose local leg is in the shared store. The store is in cld.termux's private home and the
     * engine APK runs in its own uid, so it cannot open it: [begin] stages the legs where the engine
     * can (shared storage), [Staged.finish] copies a destination back and removes the staging. The
     * host app sets it once; unset (or a job with no store leg) runs the args as written.
     */
    interface LegStaging {
        fun refusal(args: List<String>): String?
        fun begin(args: List<String>): Staged?
    }

    class Staged(val args: List<String>, val prepare: () -> String?, val finish: (Int) -> String?)

    private fun startArgs(jobArgs: List<String>, onStats: (RcloneStats) -> Unit, onLine: (String) -> Unit, onExit: (Int) -> Unit): Handle {
        val args = jobArgs + listOf("--use-json-log", "--stats", "1s", "--stats-log-level", "NOTICE", "-v")
        val exit = java.util.concurrent.CountDownLatch(1)
        val code = java.util.concurrent.atomic.AtomicInteger(-1)
        /** A job that never started: the sentence why, the exit -1, a handle that is already done. */
        fun refused(why: String?): Handle {
            val h = Handle({}, exit) { code.get() }
            // reported from a thread, after the caller has recorded the handle, as a job that ran would be
            Thread { Thread.sleep(REFUSED_DELAY_MS); onLine("[engine] $why"); onExit(-1); exit.countDown() }.apply { isDaemon = true; start() }
            return h
        }
        val session = try { transport.session() } catch (e: IOException) { return refused(e.message) }
        val id = try {
            session.jobStart(request(args)).checked().getString("id")
        } catch (e: Exception) {
            session.close()
            return refused(e.message ?: e.javaClass.simpleName)
        }
        val handle = Handle({ runCatching { session.jobCancel(JSONObject().put("id", id)) } }, exit) { code.get() }
        Thread {
            var cursor = 0
            var rc = -1
            try {
                while (true) {
                    val p = session.jobPoll(JSONObject().put("id", id).put("cursor", cursor)).checked()
                    val lines = p.getJSONArray("lines")
                    for (i in 0 until lines.length()) line(lines.getString(i), onStats, onLine)
                    cursor = p.getInt("cursor")
                    if (p.getBoolean("done")) {
                        keepConfig(p.optString("config").takeIf { p.has("config") })
                        rc = p.optInt("rc", -1)
                        break
                    }
                    if (lines.length() == 0) Thread.sleep(POLL_IDLE_MS)
                }
            } catch (e: Exception) {
                onLine("[engine] ${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { session.close() }
            }
            code.set(if (handle.cancelled) -2 else rc)
            onExit(code.get())
            exit.countDown()
        }.apply { isDaemon = true; start() }
        return handle
    }

    private fun line(line: String, onStats: (RcloneStats) -> Unit, onLine: (String) -> Unit) {
        RcloneOutput.parseStatsLine(line)?.let(onStats)
        val msg = RcloneOutput.parseMessage(line)
        if (msg != null) { if (msg.second.isNotBlank() && !line.contains("\"stats\"")) onLine("[${msg.first}] ${msg.second}") }
        else if (line.isNotBlank()) onLine(line)
    }

    /** Copy one remote file into [downloadDir]; returns the local file. */
    fun download(remotePath: String, name: String): Result<File> = runCatching {
        downloadDir.mkdirs()
        val errs = StringBuilder()
        val (rc, _) = capture(listOf("copyto", remotePath, File(downloadDir, name).absolutePath)) { errs.append(it).append('\n') }
        if (rc != 0) error(errs.toString().ifBlank { "rclone copyto exited $rc" })
        File(downloadDir, name)
    }

    companion object {
        /** Set by the host app (Drive) to stage legs that name the shared store; null = run args as written. */
        @Volatile var legStaging: LegStaging? = null
        private const val POLL_IDLE_MS = 400L
        private const val REFUSED_DELAY_MS = 200L
    }
}
