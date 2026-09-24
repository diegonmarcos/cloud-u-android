package com.diegonmarcos.cloudlib.rclone

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Execs the bundled static rclone. The binary lives where the installer put
 * it — applicationInfo.nativeLibraryDir/librclone.so — because that is the
 * one place an app may exec from on API 29+ (see data/rclone-binary.json).
 * The config lives in the app's private files, so remotes and their obscured
 * secrets never leave the sandbox. Blocking; callers run it on IO.
 */
class RcloneRunner(context: Context) {
    val binary: File = File(context.applicationInfo.nativeLibraryDir, BuildConfig.RCLONE_JNI_NAME)
    val configFile: File = File(context.filesDir, "rclone/rclone.conf")
    val cacheDir: File = File(context.cacheDir, "rclone")
    /** Where "download" lands files the host may then open: app-external, browsable, no permission needed. */
    val downloadDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "rclone-downloads")

    val isAvailable: Boolean get() = binary.isFile

    /** A running rclone: its lines stream to [onLine]; [cancel] kills it. */
    class Handle(private val process: Process) {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true; process.destroy() }
        fun waitFor(): Int = process.waitFor()
    }

    private fun base(): List<String> = listOf(binary.absolutePath, "--config", configFile.absolutePath, "--cache-dir", cacheDir.absolutePath)

    /** Run to completion, capture stdout; stderr lines go to [onStderr]. */
    fun capture(args: List<String>, onStderr: (String) -> Unit = {}): Pair<Int, String> {
        val p = ProcessBuilder(base() + args).redirectErrorStream(false).start()
        val errThread = Thread { p.errorStream.bufferedReader().forEachLine(onStderr) }.apply { isDaemon = true; start() }
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor(); errThread.join(2000)
        return rc to out
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
     * Start a job with live JSON stats every second. Returns a handle at once;
     * [onStats] and [onLine] are called from the reader thread; [onExit] with the
     * exit code when the process ends (rclone: 0 ok, non-zero errors).
     */
    fun start(job: RcloneJob, onStats: (RcloneStats) -> Unit, onLine: (String) -> Unit, onExit: (Int) -> Unit): Handle {
        val args = base() + job.arguments() + listOf("--use-json-log", "--stats", "1s", "--stats-log-level", "NOTICE", "-v")
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val handle = Handle(p)
        Thread {
            p.inputStream.bufferedReader().forEachLine { line ->
                RcloneOutput.parseStatsLine(line)?.let(onStats)
                val msg = RcloneOutput.parseMessage(line)
                if (msg != null) { if (msg.second.isNotBlank() && !line.contains("\"stats\"")) onLine("[${msg.first}] ${msg.second}") }
                else if (line.isNotBlank()) onLine(line)
            }
            val rc = try { p.waitFor(30, TimeUnit.SECONDS); p.exitValue() } catch (e: IllegalThreadStateException) { -1 }
            onExit(if (handle.cancelled) -2 else rc)
        }.apply { isDaemon = true; start() }
        return handle
    }

    /** Copy one remote file into [downloadDir]; returns the local file. */
    fun download(remotePath: String, name: String): Result<File> = runCatching {
        downloadDir.mkdirs()
        val errs = StringBuilder()
        val (rc, _) = capture(listOf("copyto", remotePath, File(downloadDir, name).absolutePath)) { errs.append(it).append('\n') }
        if (rc != 0) error(errs.toString().ifBlank { "rclone copyto exited $rc" })
        File(downloadDir, name)
    }
}
