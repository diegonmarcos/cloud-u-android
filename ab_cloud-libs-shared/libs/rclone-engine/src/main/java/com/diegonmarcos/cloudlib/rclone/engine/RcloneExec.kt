package com.diegonmarcos.cloudlib.rclone.engine

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs the pinned static rclone, in this process, for a client that owns the rclone.conf.
 *
 * THE CONFIG IS THE CALLER'S. Every call carries the whole rclone.conf text; it is written to a
 * private temp file for that one run and deleted after, so no remote or obscured secret is kept
 * here between calls. rclone rewrites the file when it refreshes an OAuth token; when the text
 * after the run differs from the text before, the new text is handed back so the client stores it.
 *
 * Pure JVM (the binary, the dirs and the child environment are handed in), so the suite runs it
 * against a fake binary.
 */
internal class RcloneExec(
    private val binary: File,
    private val cacheDir: File,
    private val workDir: File,
    private val env: () -> Map<String, String>,
) {
    val isAvailable: Boolean get() = binary.isFile

    /** One finished run. [config] is non-null only when rclone changed the rclone.conf it was given. */
    class Result(val rc: Int, val stdout: String, val stderr: String, val config: String?)

    /** A running job: lines (stdout and stderr merged, as rclone's JSON log wants) readable by index. */
    inner class Job(val id: String) {
        private val lock = Any()
        private val lines = ArrayDeque<String>()
        private var first = 0                       // index of lines[0]
        @Volatile var done = false; private set
        @Volatile var rc = -1; private set
        @Volatile var cancelled = false; private set
        @Volatile var configOut: String? = null; private set
        internal lateinit var process: Process

        internal fun add(line: String) = synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) { lines.removeFirst(); first++ }
        }

        /** Lines from [cursor] on (at most [limit]), and the cursor to ask next. A cursor behind the buffer jumps forward. */
        fun read(cursor: Int, limit: Int = POLL_LINES): Pair<List<String>, Int> = synchronized(lock) {
            val from = maxOf(cursor, first)
            val end = minOf(first + lines.size, from + limit)
            (from until end).map { lines[it - first] } to end
        }

        internal fun finish(code: Int, config: String?) { rc = code; configOut = config; done = true }
        fun cancel() { cancelled = true; if (::process.isInitialized) process.destroy() }
    }

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun job(id: String): Job? = jobs[id]

    private fun configFile(): File {
        workDir.mkdirs()
        val f = File(workDir, "rc-${UUID.randomUUID()}.conf")
        f.createNewFile()
        // owner-only: the file holds obscured secrets and token JSON
        f.setReadable(false, false); f.setWritable(false, false)
        f.setReadable(true, true); f.setWritable(true, true)
        return f
    }

    private fun command(conf: File, args: List<String>): List<String> =
        listOf(binary.absolutePath, "--config", conf.absolutePath, "--cache-dir", cacheDir.absolutePath) + args

    private fun builder(conf: File, args: List<String>): ProcessBuilder =
        ProcessBuilder(command(conf, args)).also { it.environment().putAll(env()) }

    /** After a run: the config text if rclone changed it, and the temp file gone either way. */
    private fun harvest(conf: File, sent: String): String? {
        val now = runCatching { conf.readText() }.getOrDefault(sent)
        conf.delete()
        return now.takeIf { it != sent }
    }

    /** Run to completion (bounded by [timeoutSeconds]); capture stdout and stderr. Blocking. */
    fun run(config: String, args: List<String>, timeoutSeconds: Long = RUN_TIMEOUT_S): Result {
        val conf = configFile()
        conf.writeText(config)
        try {
            val p = builder(conf, args).redirectErrorStream(false).start()
            var err = ""
            val errThread = Thread { err = p.errorStream.bufferedReader().readText() }.apply { isDaemon = true; start() }
            val outBox = arrayOf("")
            val outThread = Thread { outBox[0] = p.inputStream.bufferedReader().readText() }.apply { isDaemon = true; start() }
            val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) }
            outThread.join(2000); errThread.join(2000)
            val rc = if (finished) p.exitValue() else -1
            return Result(rc, outBox[0], if (finished) err else err + "\nrclone did not finish in ${timeoutSeconds}s and was stopped", harvest(conf, config))
        } catch (t: Throwable) {
            conf.delete()
            throw t
        }
    }

    /** Start a job; returns at once. The caller polls [Job.read] and finally reads [Job.rc] and [Job.configOut]. */
    fun start(config: String, args: List<String>): Job {
        val conf = configFile()
        conf.writeText(config)
        val job = Job(UUID.randomUUID().toString())
        try {
            val p = builder(conf, args).redirectErrorStream(true).start()
            job.process = p
            jobs[job.id] = job
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { job.add(it) }
                    val code = try { p.waitFor(30, TimeUnit.SECONDS); p.exitValue() } catch (e: IllegalThreadStateException) { -1 }
                    job.finish(if (job.cancelled) -2 else code, harvest(conf, config))
                } catch (t: Throwable) {
                    // destroy() closes the child's streams, so a cancelled job's reader ends in an IOException: that is the
                    // cancel, not a failure of the engine
                    if (!job.cancelled) job.add("engine: ${t.message ?: t.javaClass.simpleName}")
                    job.finish(if (job.cancelled) -2 else -1, harvest(conf, config))
                }
            }.apply { isDaemon = true; start() }
        } catch (t: Throwable) {
            conf.delete()
            throw t
        }
        return job
    }

    /** Forget a finished job (the client has read its end). */
    fun forget(id: String) { jobs.remove(id) }

    companion object {
        const val MAX_LINES = 5000
        const val POLL_LINES = 400
        const val RUN_TIMEOUT_S = 600L
    }
}
