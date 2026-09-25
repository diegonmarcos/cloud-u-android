package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.cloudlib.mounts.MountCredentialStore
import com.diegonmarcos.cloudlib.mounts.MountFsFactory
import com.diegonmarcos.cloudlib.mounts.MountSpec
import com.diegonmarcos.cloudlib.mounts.MountStore
import com.diegonmarcos.cloudlib.rclone.RcloneJob
import com.diegonmarcos.cloudlib.rclone.RcloneJobStore
import com.diegonmarcos.cloudlib.rclone.RcloneRemote
import com.diegonmarcos.cloudlib.rclone.RcloneRunner
import com.diegonmarcos.cloudlib.rclone.RcloneStats
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #579 the chrome's reading of the rclone and mounts engines for the Sync cards: the
 * phone's remotes and jobs (the SAME files the engine screens use), a running job's
 * live stats held HERE so a run survives leaving the tab, remote/mount reachability
 * tests, and the last test result per id (DrivePrefs, observed — a test is a look at
 * the thing itself).
 */
class RcloneCoordinator(private val ctx: Context, private val scope: CoroutineScope, private val prefs: DrivePrefs) {

    val runner = RcloneRunner(ctx)
    private val jobStore = RcloneJobStore(File(ctx.filesDir, "rclone/jobs.json"))
    private val mountStore = MountStore(File(ctx.filesDir, "mounts/mounts.json"))
    private val mountCredentials = MountCredentialStore(ctx)

    data class Run(val handle: RcloneRunner.Handle, val stats: RcloneStats? = null, val lines: List<String> = emptyList(), val exit: Int? = null)

    val version = MutableStateFlow<String?>(null)
    val remotes = MutableStateFlow<List<RcloneRemote>>(emptyList())
    val jobs = MutableStateFlow<List<RcloneJob>>(emptyList())
    val runs = MutableStateFlow<Map<String, Run>>(emptyMap())
    val mounts = MutableStateFlow<List<MountSpec>>(emptyList())
    val testing = MutableStateFlow<Set<String>>(emptySet())
    /** id → result text of the last test in this session. */
    val testResults = MutableStateFlow<Map<String, String>>(emptyMap())

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val v = runner.version()
                val r = runner.remotes()
                val j = jobStore.load()
                val m = mountStore.load()
                version.value = v; remotes.value = r; jobs.value = j; mounts.value = m
            }
        }
    }

    fun mountCredentialState(m: MountSpec): Int = when {
        m.keyPath.isNotBlank() -> 2
        mountCredentials.has(m.id) -> 1
        else -> 0
    }

    fun testRemote(remote: RcloneRemote, onDone: (Boolean, String) -> Unit) {
        testing.update { it + remote.name }
        scope.launch {
            val res = withContext(Dispatchers.IO) { runner.test(remote.name) }
            testing.update { it - remote.name }
            val ok = res.isSuccess
            prefs.setLastTest("remote:" + remote.name, ok)
            val text = res.fold({ "$it" }, { it.message?.lines()?.firstOrNull { l -> l.isNotBlank() } ?: it.toString() })
            testResults.update { it + (remote.name to text) }
            onDone(ok, text)
        }
    }

    fun testMount(m: MountSpec, onDone: (Boolean, String) -> Unit) {
        testing.update { it + m.id }
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { MountFsFactory.open(m, mountCredentials.secretFor(m), mountCredentials.knownHosts).use { it.list(m.path).size } } }
            testing.update { it - m.id }
            val ok = res.isSuccess
            prefs.setLastTest("mount:" + m.id, ok)
            val text = res.fold({ "$it" }, { it.message ?: it.toString() })
            testResults.update { it + (m.id to text) }
            onDone(ok, text)
        }
    }

    fun run(job: RcloneJob) {
        if (!runner.isAvailable || runs.value[job.id]?.exit == null && runs.value.containsKey(job.id)) return
        val handle = runner.start(job,
            onStats = { s -> runs.update { m -> m[job.id]?.let { r -> m + (job.id to r.copy(stats = s)) } ?: m } },
            onLine = { l -> runs.update { m -> m[job.id]?.let { r -> m + (job.id to r.copy(lines = (r.lines + l).takeLast(200))) } ?: m } },
            onExit = { rc ->
                runs.update { m -> m[job.id]?.let { r -> m + (job.id to r.copy(exit = rc)) } ?: m }
                val st = runs.value[job.id]
                val summary = when (rc) { 0 -> "ok"; -2 -> "cancelled"; else -> "exit $rc" } + (st?.stats?.let { " · ${com.diegonmarcos.cloudlib.rclone.RcloneOutput.humanBytes(it.bytes)}, ${it.transfers} transfer(s), ${it.errors} error(s)" } ?: "")
                scope.launch { withContext(Dispatchers.IO) { jobStore.upsert(job.copy(lastRunEpochSeconds = System.currentTimeMillis() / 1000, lastRunSummary = summary)) }; refresh() }
            })
        runs.update { it + (job.id to Run(handle)) }
    }

    fun cancel(job: RcloneJob) { runs.value[job.id]?.handle?.cancel() }
    fun dismiss(job: RcloneJob) { runs.update { it - job.id } }
}
