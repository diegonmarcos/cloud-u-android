package app.sterna.core.data.mail

/** Empty or absent = the next sync of that folder takes the full-query branch, the only one that
 *  sends the account's sync window to the server. */
data class SyncState(val queryState: String, val emailState: String)

/** The persisted half of the cursors, implemented by [SyncStateStore] over SharedPreferences. */
interface SyncCursorStore {
    fun save(key: String, queryState: String, emailState: String)
    fun load(key: String): Pair<String, String>?
    fun remove(key: String)
    fun clear()

    /** Every cursor key held, excluding the store's own schema markers. */
    fun keys(): Set<String>
}

/** A key is `"<accountId><mailboxId>"` with no separator, so membership can only be read as a
 *  prefix and the longest matching sibling id wins: a tie-break, not a proof. [accountId] must not
 *  be blank, a blank id prefixing every key and turning a per-account drop into a global reset. */
fun cursorKeysOfAccount(
    keys: Collection<String>,
    accountId: String,
    siblingAccountIds: Collection<String> = emptyList(),
): List<String> {
    require(accountId.isNotBlank()) {
        "cursorKeysOfAccount() needs a real account id: a blank one prefixes every key and would " +
            "drop every account's cursors."
    }
    // Only an id longer than this one and starting with it can compete for the same key.
    val rivals = siblingAccountIds.filter {
        it != accountId && it.length > accountId.length && it.startsWith(accountId)
    }
    return keys.filter { key -> key.startsWith(accountId) && rivals.none { key.startsWith(it) } }
}

/** An in-memory map with write-through to [store] so the cursors survive process death (#17). Every
 *  operation touches both halves: dropping one only in memory looks right until the next cold start
 *  reads the stale cursor back off disk and resumes a forgotten delta. */
class SyncCursors(private val store: SyncCursorStore? = null) {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, SyncState>()

    fun put(key: String, state: SyncState) {
        memory[key] = state
        store?.save(key, state.queryState, state.emailState)
    }

    fun drop(key: String) {
        memory.remove(key)
        store?.remove(key)
    }

    fun load(key: String): SyncState? =
        memory[key]
            ?: store?.load(key)
                ?.let { (queryState, emailState) -> SyncState(queryState, emailState) }
                ?.also { memory[key] = it }

    /** Both halves are asked for their own keys: a cursor written by a previous process life is on
     *  disk and not in memory, and it is exactly the one a cold start would resume from. */
    fun dropAccount(accountId: String, siblingAccountIds: Collection<String> = emptyList()) {
        cursorKeysOfAccount(memory.keys, accountId, siblingAccountIds).forEach { memory.remove(it) }
        val persisted = store ?: return
        cursorKeysOfAccount(persisted.keys(), accountId, siblingAccountIds).forEach { persisted.remove(it) }
    }

    fun clear() {
        memory.clear()
        store?.clear()
    }
}
