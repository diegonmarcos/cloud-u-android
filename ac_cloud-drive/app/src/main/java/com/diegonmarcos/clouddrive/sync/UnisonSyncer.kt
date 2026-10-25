package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.mounts.MountCredentialStore
import com.diegonmarcos.cloudlib.mounts.MountStore
import com.diegonmarcos.cloudlib.mounts.MountType
import com.diegonmarcos.cloudlib.rclone.RcloneLink
import com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig
import com.diegonmarcos.cloudlib.rclone.unison.UnisonOutput
import com.diegonmarcos.cloudlib.rclone.unison.UnisonResolve
import com.diegonmarcos.cloudlib.rclone.unison.UnisonRunner
import com.diegonmarcos.cloudlib.rclone.unison.UnisonStateStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1084 the ONE place a declared Unison pair is run: the Sync tab's Sync now, the conflict buttons and
 * the WorkManager worker all come through here, so a scheduled run and a manual one are the same code
 * and leave the same record. The pairs are the user's (storage/UnisonPairStore; the shipped data/drive-sync-rules.json
 * `unison` is policy only); the engine is the pinned static unison in Cloud-Lib-Rclone-Engine.apk, reached through
 * libs:rclone's UnisonRunner; the state (last result, conflicts, log) is UnisonStateStore, app-private.
 *
 * Blocking; callers run it off the main thread. Never throws: a missing engine or credential is a
 * result with its sentence.
 */
class UnisonSyncer(private val ctx: Context) {
    val store = UnisonStateStore(File(ctx.filesDir, UnisonStateStore.FILE))
    private val mounts = MountStore(File(ctx.filesDir, "mounts/mounts.json"))
    private val credentials by lazy { MountCredentialStore(ctx) }
    val runner = UnisonRunner(RcloneLink(ctx), ::accessFor)

    /** The user's pairs over the shipped policy, and the sentences for the ones that did not parse (shown, never silently dropped). */
    val pairStore = com.diegonmarcos.clouddrive.storage.UnisonPairStore(ctx)
    val declared: UnisonConfig.Declared get() = pairStore.declared()

    /**
     * The SSH credentials for [pair]'s peer, from the phone's own mounts store: the mount the pair names, else the
     * first ssh mount on the same host. Null when there is none, which the runner turns into a sentence.
     */
    internal fun accessFor(pair: UnisonConfig.Pair): UnisonRunner.SshAccess? {
        val peer = pair.peer as? UnisonConfig.Peer.Ssh ?: return null
        // #1173 the SSH key is Cloud Account's: asked for first, handed to the engine as text for this one run, never kept here.
        val owned = (com.diegonmarcos.clouddrive.storage.AccountStorage.of(ctx).pull(com.diegonmarcos.superapp.fleetconfig.StorageConfigContract.SSH_KEY) as? com.diegonmarcos.clouddrive.storage.AccountStorage.Pulled.Value)?.text.orEmpty()
        if (owned.isNotBlank()) return UnisonRunner.SshAccess(keyPem = owned)
        val all = runCatching { mounts.load() }.getOrDefault(emptyList())
        val spec = all.firstOrNull { it.type == MountType.SSH && peer.mount.isNotBlank() && (it.name == peer.mount || it.id == peer.mount) }
            ?: all.firstOrNull { it.type == MountType.SSH && it.host == peer.host && it.username == peer.user }
            ?: return null
        val secret = runCatching { credentials.secretFor(spec) }.getOrNull() ?: return null
        return UnisonRunner.SshAccess(keyPath = spec.keyPath, passphrase = secret.passphrase.orEmpty(), password = secret.password.orEmpty())
    }

    /** Start a sync and return at once; [onLine] gets each readable log line, [onDone] the remembered result. */
    fun start(pair: UnisonConfig.Pair, trigger: String, onLine: (String) -> Unit = {}, onDone: (UnisonOutput.Result) -> Unit = {}): UnisonRunner.Handle {
        val log = CopyOnWriteArrayList<String>()
        return runner.sync(pair, { log += it; onLine(it) }) { r -> onDone(remember(pair, trigger, r, log)) }
    }

    /** The blocking form the WorkManager worker uses. */
    fun sync(pair: UnisonConfig.Pair, trigger: String, onLine: (String) -> Unit = {}): UnisonOutput.Result =
        start(pair, trigger, onLine).await()

    /** Resolve one conflict with [choice] (a scoped run, then keep-both's copy); returns at once. */
    fun startResolve(pair: UnisonConfig.Pair, path: String, choice: UnisonResolve.Choice, onLine: (String) -> Unit = {}, onDone: (UnisonOutput.Result) -> Unit = {}): UnisonRunner.Handle {
        val log = CopyOnWriteArrayList<String>()
        return runner.resolve(pair, path, choice, { log += it; onLine(it) }) { result ->
            if (result.rc in 0..1 && result.failures.isEmpty()) store.resolved(pair.id, path)
            // the pair's record keeps its remaining conflicts; the resolution's own lines join its log
            store.get(pair.id)?.let { cur ->
                store.put(cur.copy(log = cur.log + "[resolve ${choice.name.lowercase()}] $path" + log.map { "  $it" }, lastRunEpochSeconds = System.currentTimeMillis() / 1000))
            }
            onDone(result)
        }
    }

    /** Fold a finished run into the pair's remembered state. */
    private fun remember(pair: UnisonConfig.Pair, trigger: String, result: UnisonOutput.Result, log: List<String>): UnisonOutput.Result {
        store.put(UnisonStateStore.PairState(
            pairId = pair.id, lastRunEpochSeconds = System.currentTimeMillis() / 1000, trigger = trigger,
            headline = result.headline(), rc = result.rc, conflicts = result.conflicts, failures = result.failures, log = log,
        ))
        return result
    }

    companion object {
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_SCHEDULED = "scheduled"
    }
}
