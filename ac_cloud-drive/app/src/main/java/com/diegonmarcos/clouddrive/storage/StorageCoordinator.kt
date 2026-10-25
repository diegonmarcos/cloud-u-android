package com.diegonmarcos.clouddrive.storage

import android.content.Context
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.cloudlib.rclone.RcloneConfig
import com.diegonmarcos.cloudlib.rclone.RcloneRemote
import com.diegonmarcos.cloudlib.rclone.RcloneRunner
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Fsync and Mounts pages' one reading of REAL state: the phone's rclone remotes (rclone itself answers), the FUSE
 * mounts and rsync jobs the user defined and what the Termux guest says about them, the Unison pairs, and the credentials
 * Cloud Account owns. Nothing is declared in the APK; every list starts empty and fills from what exists.
 *
 * Blocking work (the rclone engine, CLOUD_EXEC into the Termux guest, the Account door) runs on IO; every result lands in a
 * StateFlow the screens collect. A sentence for the user is always about state, never a credential.
 */
class StorageCoordinator(private val ctx: Context, private val scope: CoroutineScope, private val rclone: RcloneCoordinator, private val prefs: DrivePrefs) {

    private val dir = File(ctx.filesDir, "storage").also { it.mkdirs() }
    val ops = RcloneConfigOps(rclone.runner)
    private val exec by lazy { StoreExec(ctx) }

    // ── Cloud Account (owner of the credentials) ─────────────────────────────────────────────────────────────────
    private val creds = DriveCredentialsClient.of(ctx)
    /** What Account last published to this app; null until it answered. */
    val accountSnapshot = MutableStateFlow<DriveCredentialsClient.Snapshot?>(null)
    /** The last thing the Account door said that the user should know (null = all level). */
    val accountNote = MutableStateFlow<String?>(null)

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    private fun fuseFromDisk(): List<FuseWire.Spec> = if (fuseFile.isFile) FuseWire.fromJson(runCatching { fuseFile.readText() }.getOrDefault("")) else emptyList()

    /**
     * Called when a page opens: ask Account what it holds and bring this phone level ([CredentialMerge]): what only Account has is
     * restored here (a new phone), what only this phone has (a remote just made, a refreshed token, a mount, a pair) is handed over.
     */
    fun refreshAccount() {
        scope.launch {
            val note = withContext(Dispatchers.IO) { levelWithAccount() }
            accountNote.value = note
            rclone.refresh(); rclone.unison.refresh()
            fuseSpecs.value = withContext(Dispatchers.IO) { fuseFromDisk() }
        }
    }

    private fun levelWithAccount(): String? {
        val snap = when (val r = creds.read()) {
            is DriveCredentialsClient.Read.Unavailable -> { accountSnapshot.value = null; return r.why }
            is DriveCredentialsClient.Read.Ok -> r.snapshot
        }
        accountSnapshot.value = snap
        val problems = ArrayList<String>()
        snap.missingGrants?.let { problems += "Cloud Account has not opened: $it (Account > Secrets)" }
        // remotes
        val conf = rclone.runner.configFile
        val local = RcloneConfig.read(conf)
        CredentialMerge.remotesToRestore(local, snap.rcloneConfig).forEach { RcloneConfig.upsert(conf, it) }
        CredentialMerge.remotesToPush(local, snap.remotes, snap.rcloneConfig).forEach { r ->
            creds.putRemote(r.name, r.type, r.options)?.let { problems += "${r.name}: $it" }
        }
        // mounts
        val mounts = fuseFromDisk()
        val restored = CredentialMerge.mountsToRestore(mounts, snap.mounts)
        if (restored.isNotEmpty()) write(fuseFile, FuseWire.toJson(mounts + restored))
        CredentialMerge.mountsToPush(mounts, snap.mounts).forEach { m -> creds.putMount(CredentialMerge.mountToAccount(m))?.let { problems += "mount ${m.id}: $it" } }
        // pairs
        val store = rclone.unison.syncer.pairStore
        when (CredentialMerge.pairs(store.text(), snap.unison, accountReadable = true)) {
            CredentialMerge.PairAction.RESTORE -> snap.unison?.let { store.write(it) }
            CredentialMerge.PairAction.PUSH -> creds.putUnison(store.text())?.let { problems += "pairs: $it" }
            CredentialMerge.PairAction.NONE -> {}
        }
        return problems.firstOrNull()
    }

    /** After a remote was created or updated: read it back as rclone holds it and hand it to Account. */
    private fun pushRemote(name: String) {
        val shown = ops.show(name).getOrNull() ?: return
        val why = creds.putRemote(name, shown.first, shown.second)
        accountNote.value = why?.let { "$name is saved on this phone; Cloud Account: $it" }
    }

    init { rclone.unison.onPairsChanged = { scope.launch(Dispatchers.IO) { accountNote.value = creds.putUnison(rclone.unison.syncer.pairStore.text())?.let { "pairs are saved on this phone; Cloud Account: $it" } } } }

    private fun sshKey(): String = (creds.read() as? DriveCredentialsClient.Read.Ok)?.snapshot?.sshPrivate.orEmpty()

    // ── Mounts > Rclone ──────────────────────────────────────────────────────────────────────────────────────────
    val providers = MutableStateFlow<List<RcloneProviders.Provider>>(emptyList())
    val providersError = MutableStateFlow<String?>(null)
    val providersLoading = MutableStateFlow(false)

    /** The backend list and its schema, asked of rclone (once per run; Retry asks again). */
    fun loadProviders(force: Boolean = false) {
        if (providersLoading.value || (!force && providers.value.isNotEmpty())) return
        providersLoading.value = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { ops.providers() }
            r.onSuccess { providers.value = it; providersError.value = null }.onFailure { providersError.value = it.message ?: "rclone did not answer" }
            providersLoading.value = false
        }
    }

    /** name → one-line result of the last lsd / about. */
    val remoteNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    val remoteBusy = MutableStateFlow<Set<String>>(emptySet())

    private fun note(name: String, text: String) = remoteNotes.update { it + (name to text) }
    private inline fun busy(name: String, block: () -> Unit) { remoteBusy.update { it + name }; try { block() } finally { remoteBusy.update { it - name } } }

    fun testRemote(name: String) {
        scope.launch {
            busy(name) {
                val r = withContext(Dispatchers.IO) { ops.lsd(name) }
                prefs.setLastTest("remote:$name", r.isSuccess)
                note(name, r.fold({ if (it.isEmpty()) "reachable, no folders at the top" else "reachable · ${it.size} folder(s): ${it.take(4).joinToString(", ")}${if (it.size > 4) "…" else ""}" }, { it.message ?: "failed" }))
            }
        }
    }

    fun aboutRemote(name: String) {
        scope.launch {
            busy(name) {
                val r = withContext(Dispatchers.IO) { ops.about(name) }
                note(name, r.fold({ a -> listOfNotNull(a.used?.let { "used " + com.diegonmarcos.cloudlib.rclone.RcloneOutput.humanBytes(it) }, a.total?.let { "of " + com.diegonmarcos.cloudlib.rclone.RcloneOutput.humanBytes(it) }, a.free?.let { "free " + com.diegonmarcos.cloudlib.rclone.RcloneOutput.humanBytes(it) }).joinToString(" · ").ifBlank { "no usage reported" } }, { it.message ?: "failed" }))
            }
        }
    }

    /**
     * Create or update a remote through `rclone config create|update`; [done] gets null or rclone's sentence. When a token is
     * handed over, the remote is read back with `rclone config show`: if rclone's OAuth state machine did not keep it (it may
     * ask "refresh the token?" instead), the section it would have written is written into rclone.conf directly, so the
     * sign-in the user just made is never lost. Then the remote goes to Cloud Account.
     */
    fun saveRemote(name: String, type: String, values: Map<String, String>, editing: Boolean, done: (String?) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                var out = if (editing) ops.update(name, values) else ops.create(name, type, values)
                if (values.containsKey("token") || values.containsKey("service_account_credentials")) {
                    val key = if (values.containsKey("token")) "token" else "service_account_credentials"
                    val shown = ops.show(name).getOrNull()
                    if (shown == null || shown.second[key].isNullOrBlank()) out = runCatching { writeSection(name, type, values) }
                }
                out.also { if (it.isSuccess) pushRemote(name) }
            }
            if (r.isSuccess) rclone.refresh()
            done(r.exceptionOrNull()?.message ?: if (r.isFailure) "failed" else null)
        }
    }

    private fun writeSection(name: String, type: String, values: Map<String, String>) {
        require(RcloneProviders.validName(name) && RcloneProviders.validKey(type)) { "not a usable remote name or backend" }
        val conf = rclone.runner.configFile
        val old = RcloneConfig.read(conf).firstOrNull { it.name == name }
        RcloneConfig.upsert(conf, RcloneRemote(name, old?.type ?: type, (old?.options.orEmpty() + values.filter { (k, v) -> RcloneProviders.validKey(k) && v.isNotBlank() })))
    }

    fun deleteRemote(name: String, done: (String?) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { ops.delete(name).also { if (it.isSuccess) accountNote.value = creds.removeRemote(name)?.let { why -> "$name is removed here; Cloud Account: $why" } } }
            if (r.isSuccess) { rclone.refresh(); remoteNotes.update { it - name } }
            done(r.exceptionOrNull()?.message)
        }
    }

    fun showRemote(name: String, done: (Result<Pair<String, Map<String, String>>>) -> Unit) {
        scope.launch { done(withContext(Dispatchers.IO) { ops.show(name) }) }
    }

    fun shared(name: String, done: (Result<List<RcloneProviders.SharedDrive>>) -> Unit) {
        scope.launch { done(withContext(Dispatchers.IO) { ops.drives(name) }) }
    }

    // serve (the rclone page's "mount" on a phone without FUSE: WebDAV on loopback, in the rclone engine)
    data class Serving(val port: Int, val handle: RcloneRunner.Handle, val lines: List<String> = emptyList())
    val serving = MutableStateFlow<Map<String, Serving>>(emptyMap())

    fun serve(name: String, cacheMode: String = FuseWire.DEFAULT_CACHE_MODE) {
        if (serving.value.containsKey(name) || !RcloneProviders.validName(name)) return
        val taken = serving.value.values.map { it.port }.toSet()
        val port = generateSequence(FuseWire.servePort(name)) { it + 1 }.first { it !in taken }
        val handle = ops.serve("$name:", port, cacheMode,
            onLine = { l -> serving.update { m -> m[name]?.let { s -> m + (name to s.copy(lines = (s.lines + l).takeLast(40))) } ?: m } },
            onExit = { serving.update { it - name } })
        serving.update { it + (name to Serving(port, handle)) }
    }

    fun stopServe(name: String) { serving.value[name]?.handle?.cancel() }

    // ── Mounts > FUSE (inside the Termux guest) ──────────────────────────────────────────────────────────────────
    private val fuseFile = File(dir, "fuse-mounts.json")
    val fuseSpecs = MutableStateFlow<List<FuseWire.Spec>>(emptyList())
    val fuseCaps = MutableStateFlow<FuseWire.Caps?>(null)
    val fuseVerdict = MutableStateFlow<FuseWire.Verdict?>(null)
    val fuseLive = MutableStateFlow<List<FuseWire.Live>>(emptyList())
    /** ids whose WebDAV fallback is serving in the guest. */
    val guestServing = MutableStateFlow<Set<String>>(emptySet())
    val fuseBusy = MutableStateFlow<Set<String>>(emptySet())
    val fuseNotes = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun fuseNote(id: String, text: String) = fuseNotes.update { it + (id to text) }

    private fun guest(script: String, stdin: String? = null, timeoutMs: Int = 60_000): StoreExec.Result = exec.run(script, stdin, timeoutMs)

    /** The guest's answer to the FUSE check plus its live mounts; any failure to reach the guest becomes the verdict's reason. */
    fun refreshFuse() {
        scope.launch {
            withContext(Dispatchers.IO) {
                fuseSpecs.value = fuseFromDisk()
                val probe = runCatching { guest(FuseWire.probe(), timeoutMs = 30_000) }
                val caps = probe.getOrNull()?.takeIf { it.ok }?.let { FuseWire.parseCaps(it.out) }
                fuseCaps.value = caps
                fuseVerdict.value = if (probe.isFailure) FuseWire.Verdict(false, "Cloud Terminal's Termux is not reachable (${probe.exceptionOrNull()?.message?.take(120) ?: "no answer"}): install and open it once, then retry.") else FuseWire.verdict(caps)
                if (caps != null) {
                    fuseLive.value = runCatching { FuseWire.parseStatus(guest(FuseWire.statusScript(), timeoutMs = 20_000).out) }.getOrDefault(emptyList())
                    guestServing.value = fuseSpecs.value.filter { it.kind == FuseWire.Kind.RCLONE }.filter { s ->
                        runCatching { guest(FuseWire.serveRunningScript(s.id), timeoutMs = 15_000).out.trim() == "running" }.getOrDefault(false)
                    }.map { it.id }.toSet()
                }
            }
        }
    }

    fun addFuse(spec: FuseWire.Spec, done: (String?) -> Unit) {
        val why = FuseWire.refusal(spec) ?: if (fuseSpecs.value.any { it.id == spec.id }) "a mount with this id exists" else null
        if (why != null) { done(why); return }
        fuseSpecs.update { it + spec }
        scope.launch {
            withContext(Dispatchers.IO) {
                write(fuseFile, FuseWire.toJson(fuseSpecs.value))
                accountNote.value = creds.putMount(CredentialMerge.mountToAccount(spec))?.let { "mount ${spec.id} is saved on this phone; Cloud Account: $it" }
            }
            done(null)
        }
    }

    fun removeFuse(spec: FuseWire.Spec) {
        if (FuseWire.isMounted(fuseLive.value, spec) || spec.id in guestServing.value) { fuseNote(spec.id, "unmount or stop it first"); return }
        fuseSpecs.update { l -> l.filterNot { it.id == spec.id } }
        scope.launch {
            withContext(Dispatchers.IO) {
                write(fuseFile, FuseWire.toJson(fuseSpecs.value))
                accountNote.value = creds.removeMount(spec.id)?.let { "mount ${spec.id} is removed here; Cloud Account: $it" }
            }
        }
    }

    /** rclone.conf cut down to the one remote the mount names: the guest never holds the other remotes' credentials. */
    private fun confFor(source: String): String {
        val remote = source.substringBefore(':')
        val text = runCatching { rclone.runner.configFile.readText() }.getOrDefault("")
        return RcloneConfig.serialize(RcloneConfig.parse(text).filter { it.name == remote })
    }

    /** Stage what the mount needs in the guest (config for rclone, key for sshfs); returns the sentence when it cannot. */
    private fun stage(spec: FuseWire.Spec): Pair<Boolean, String?> {
        if (spec.kind == FuseWire.Kind.RCLONE) {
            val conf = confFor(spec.source)
            if (conf.isBlank()) return false to "no rclone remote named ${spec.source.substringBefore(':')}: add it on Mounts > Rclone"
            val r = guest(FuseWire.confStage(spec.id), conf)
            return false to (if (r.ok) null else "could not stage the config in the guest: ${r.err.take(160)}")
        }
        val key = sshKey()
        if (key.isBlank()) return false to null
        val r = guest(FuseWire.keyStage(spec.id), key)
        return if (r.ok) true to null else false to "could not stage the key in the guest: ${r.err.take(160)}"
    }

    fun mount(spec: FuseWire.Spec) {
        val verdict = fuseVerdict.value
        if (verdict?.usable != true) { fuseNote(spec.id, verdict?.reason ?: "check FUSE first"); return }
        val missing = FuseWire.missingTools(spec.kind, fuseCaps.value)
        if (missing.isNotEmpty()) { fuseNote(spec.id, "missing in the guest: ${missing.joinToString()} (apt install ${missing.joinToString(" ")})"); return }
        runFuse(spec, "mounting…") {
            val (withKey, why) = stage(spec)
            if (why != null) why else {
                val r = guest(FuseWire.mountScript(spec, withKey = withKey), timeoutMs = 90_000)
                val last = r.out.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                if (r.ok && (last == "started" || last == "already-mounted")) null
                else (r.err.ifBlank { r.out }.ifBlank { "mount failed (exit ${r.exit})" } + guest(FuseWire.logTail(spec), timeoutMs = 15_000).out.takeLast(300)).trim().take(400)
            }
        }
    }

    fun unmount(spec: FuseWire.Spec) {
        runFuse(spec, "unmounting…") {
            val r = guest(FuseWire.unmountScript(spec, fuseCaps.value?.fusermount ?: "fusermount3"), timeoutMs = 30_000)
            if (r.ok) null else "still busy: close what uses ${spec.mountpoint}, then retry"
        }
    }

    /** The fallback when FUSE is not usable: the remote served as WebDAV on loopback inside the guest. */
    fun serveInGuest(spec: FuseWire.Spec) {
        if (spec.kind != FuseWire.Kind.RCLONE) { fuseNote(spec.id, "only rclone remotes can be served"); return }
        if (!FuseWire.validRcloneSource(spec.source)) { fuseNote(spec.id, "not a remote:path source"); return }
        runFuse(spec, "starting…") {
            val (_, why) = stage(spec)
            if (why != null) why else {
                val r = guest(FuseWire.serveScript(spec.id, spec.source, FuseWire.servePort(spec.id), spec.cacheMode), timeoutMs = 60_000)
                if (r.ok) null else (r.out.ifBlank { r.err }).trim().take(300)
            }
        }
    }

    fun stopServeInGuest(spec: FuseWire.Spec) {
        runFuse(spec, "stopping…") { if (guest(FuseWire.stopServeScript(spec.id), timeoutMs = 20_000).ok) null else "could not stop" }
    }

    private fun runFuse(spec: FuseWire.Spec, working: String, block: () -> String?) {
        if (spec.id in fuseBusy.value) return
        fuseBusy.update { it + spec.id }; fuseNote(spec.id, working)
        scope.launch {
            val why = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: it.javaClass.simpleName } }
            fuseBusy.update { it - spec.id }
            fuseNotes.update { it - spec.id + (spec.id to (why ?: "done")) }
            refreshFuse()
        }
    }

    // ── Fsync > Backup (rsync inside the Termux guest) ───────────────────────────────────────────────────────────
    private val backupFile = File(dir, "backup-jobs.json")
    val backups = MutableStateFlow<List<BackupJobs.Job>>(emptyList())
    val backupRunning = MutableStateFlow<Set<String>>(emptySet())
    val backupNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Whether rsync exists in the guest: null = not asked or not reachable. */
    val rsyncReady = MutableStateFlow<String?>(null)

    fun refreshBackups() {
        scope.launch {
            withContext(Dispatchers.IO) {
                backups.value = if (backupFile.isFile) BackupJobs.fromJson(runCatching { backupFile.readText() }.getOrDefault("")) else emptyList()
                val r = runCatching { guest("command -v rsync >/dev/null 2>&1 && echo ready || echo missing", timeoutMs = 30_000) }
                rsyncReady.value = r.fold({ if (it.out.trim() == "ready") "ready" else "rsync is not installed in the Termux guest (apt install rsync)" }, { "Cloud Terminal's Termux is not reachable: install and open it once" })
            }
        }
    }

    private fun saveBackups() = write(backupFile, BackupJobs.toJson(backups.value))

    fun addBackup(job: BackupJobs.Job, done: (String?) -> Unit) {
        val why = BackupJobs.refusal(job) ?: if (backups.value.any { it.id == job.id }) "a job with this id exists" else null
        if (why != null) { done(why); return }
        backups.update { it + job }
        scope.launch { withContext(Dispatchers.IO) { saveBackups() }; done(null) }
    }

    fun removeBackup(job: BackupJobs.Job) {
        if (job.id in backupRunning.value) return
        backups.update { l -> l.filterNot { it.id == job.id } }
        scope.launch { withContext(Dispatchers.IO) { saveBackups() } }
    }

    fun runBackup(job: BackupJobs.Job) {
        if (job.id in backupRunning.value) return
        backupRunning.update { it + job.id }
        backupNotes.update { it + (job.id to "running…") }
        scope.launch {
            val (exit, summary) = withContext(Dispatchers.IO) {
                try {
                    val key = if (job.target is BackupJobs.Target.Ssh && job.useKey) sshKey() else ""
                    if (job.target is BackupJobs.Target.Ssh && job.useKey && key.isBlank()) return@withContext -1 to "no SSH key in Cloud Account (or its ssh.private grant is closed): create one in Account, then run again"
                    val r = exec.job(BackupJobs.script(job), stdin = key.ifEmpty { null }, deadlineMs = 60 * 60_000L)
                    r.exit to BackupJobs.summary(r.exit, r.out + "\n" + r.err)
                } catch (e: Exception) { -1 to (e.message ?: e.javaClass.simpleName).take(200) }
            }
            val done = job.copy(lastRunEpochSeconds = System.currentTimeMillis() / 1000, lastExit = exit, lastSummary = summary)
            backups.update { l -> l.map { if (it.id == job.id) done else it } }
            withContext(Dispatchers.IO) { saveBackups() }
            backupRunning.update { it - job.id }
            backupNotes.update { it - job.id }
        }
    }
}
