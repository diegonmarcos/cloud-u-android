package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.UnisonSyncWorker
import com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig
import com.diegonmarcos.cloudlib.rclone.unison.UnisonOutput
import com.diegonmarcos.cloudlib.rclone.unison.UnisonResolve
import com.diegonmarcos.cloudlib.rclone.unison.UnisonRunner
import com.diegonmarcos.cloudlib.rclone.unison.UnisonStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1084 the Sync tab's reading of the Unison pairs: the declared pairs, what each last did (conflicts,
 * failures, log: [UnisonStateStore], the SAME file the scheduled worker writes, so a conflict found at
 * 3 a.m. is on the card in the morning), and a running pair's live lines held HERE so a run survives
 * leaving the tab. Sync now, Stop and the three conflict buttons are one tap each and go through
 * [UnisonSyncer], the code the worker runs.
 */
class UnisonCoordinator(private val ctx: Context, private val scope: CoroutineScope, private val prefs: DrivePrefs) {
    val syncer = UnisonSyncer(ctx)

    /** The user's pairs (over the shipped policy), read from the phone's own store; empty until the user makes one. */
    val declaredState = MutableStateFlow(UnisonConfig.Declared(emptyList(), emptyList()))
    val declared: UnisonConfig.Declared get() = declaredState.value
    /** Set by the storage coordinator: called after the pair list changed, so Cloud Account's copy follows. */
    @Volatile var onPairsChanged: () -> Unit = {}
    val states = MutableStateFlow<Map<String, UnisonStateStore.PairState>>(emptyMap())
    /** pair id → the live log of the run in flight (cleared when it ends; the finished log is in [states]). */
    val live = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** The sentence the engine gives when it cannot run Unison at all (not installed, too old), null when it can. */
    val unavailable = MutableStateFlow<String?>(null)

    private val handles = java.util.concurrent.ConcurrentHashMap<String, UnisonRunner.Handle>()

    /** #1195 the declared pairs and their remembered state (file reads on IO), without asking the engine whether it runs. */
    fun loadDeclared() {
        scope.launch {
            withContext(Dispatchers.IO) { val now = syncer.declared; declaredState.value = now; states.value = syncer.store.load() }
        }
    }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val now = syncer.declared
                declaredState.value = now
                syncer.store.retain(now.pairs.map { it.id }.toSet())
                states.value = syncer.store.load()
                unavailable.value = syncer.runner.unavailable()
            }
        }
    }

    /** Add a pair through the engine's own parser; [done] gets null or the parser's sentence. */
    fun addPair(draft: com.diegonmarcos.clouddrive.storage.UnisonPairs.Draft, done: (String?) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { syncer.pairStore.add(draft) }
            if (r.isSuccess) { refresh(); onPairsChanged() }
            done(r.exceptionOrNull()?.message)
        }
    }

    fun removePair(pair: UnisonConfig.Pair) {
        scope.launch {
            withContext(Dispatchers.IO) { syncer.pairStore.remove(pair.id); syncer.store.retain(declared.pairs.map { it.id }.toSet() - pair.id) }
            refresh(); onPairsChanged()
        }
    }

    fun enabled(pair: UnisonConfig.Pair): Boolean = prefs.ruleEnabled(UnisonSyncWorker.RULE_PREFIX + pair.id, pair.enabled)
    fun setEnabled(pair: UnisonConfig.Pair, on: Boolean) = prefs.setRuleEnabled(UnisonSyncWorker.RULE_PREFIX + pair.id, on)

    fun isRunning(id: String): Boolean = live.value.containsKey(id)

    private fun line(id: String): (String) -> Unit = { l -> live.update { m -> m[id]?.let { m + (id to (it + l).takeLast(LIVE_LINES)) } ?: m } }

    private fun ended(id: String): (UnisonOutput.Result) -> Unit = { _ ->
        handles.remove(id)
        live.update { it - id }
        states.value = syncer.store.load()
    }

    /** Sync now. A pair that is already running is left alone. */
    fun syncNow(pair: UnisonConfig.Pair) {
        if (isRunning(pair.id)) return
        live.update { it + (pair.id to emptyList()) }
        scope.launch(Dispatchers.IO) { handles[pair.id] = syncer.start(pair, UnisonSyncer.TRIGGER_MANUAL, line(pair.id), ended(pair.id)) }
    }

    /** Resolve one conflicted path with keep-A / keep-B / keep-both. */
    fun resolve(pair: UnisonConfig.Pair, path: String, choice: UnisonResolve.Choice) {
        if (isRunning(pair.id)) return
        live.update { it + (pair.id to listOf("resolving $path: ${choice.name.lowercase().replace('_', ' ')}")) }
        scope.launch(Dispatchers.IO) { handles[pair.id] = syncer.startResolve(pair, path, choice, line(pair.id), ended(pair.id)) }
    }

    fun cancel(pair: UnisonConfig.Pair) { handles[pair.id]?.cancel() }

    private companion object { const val LIVE_LINES = 200 }
}
