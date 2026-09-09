package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * SOURCE LINT, same instrument and disclaimer as [CollapsedFoldersStoreWiringTest]: it reads
 */
class LinkedAccountMemoryStoreWiringTest {

    @Test fun `reconcile reads the memory, delegates the whole transformation, and saves both`() {
        assertEquals(
            "AccountStore.reconcileLinkedAccounts changed shape. It must, under @Synchronized: " +
                "diff against the RAW discovered list with its probes, return early on an empty " +
                "diff, hand the WHOLE account list plus linkedMemory() to reconciledAccounts with " +
                "a fresh UUID minter, then store reconciled.memory AND reconciled.accounts. Each " +
                "line is load-bearing: drop linkedMemory() and every re-granted share is factory " +
                "fresh again (notifications the user muted come back on); drop saveLinkedMemory " +
                "and the prune is never filed, same result one revocation later; pass `list` " +
                "instead of reconciled.accounts to saveAccounts and the reconcile becomes a no-op " +
                "that still reports pruned ids; lose @Synchronized and a concurrent write to the " +
                "account blob is lost with whatever it carried.",
            listOf(
                "@Synchronized",
                "fun reconcileLinkedAccounts(",
                "loginId: String,",
                "discovered: List<DiscoveredMailAccount>,",
                "probes: Map<String, Result<*>>,",
                "): List<String> {",
                "val list = accounts()",
                "val login = list.firstOrNull { it.id == loginId } ?: return emptyList()",
                "val existingLinked = list.filter { it.loginId == loginId }",
                "val diff = diffLinkedAccounts(login, existingLinked, discovered, probes)",
                "if (diff.isEmpty()) return emptyList()",
                "val reconciled = reconciledAccounts(list, login, loginId, diff, linkedMemory()) {",
                "UUID.randomUUID().toString()",
                "}",
                "if (diff.prunedIds.isNotEmpty() && currentId() in diff.prunedIds) {",
                "prefs.edit().putString(KEY_CURRENT, loginId).apply()",
                "}",
                "saveLinkedMemory(reconciled.memory)",
                "saveAccounts(reconciled.accounts)",
                "return diff.prunedIds",
                "}",
            ),
            block("fun reconcileLinkedAccounts(", 20, before = 1),
        )
    }

    @Test fun `removeCascading forgets the removed records, read BEFORE the list is filtered`() {
        assertEquals(
            "AccountStore.removeCascading no longer forgets what it removes, or reads the removed " +
                "records from the wrong list. They must come from the list BEFORE filtering: " +
                "`remaining` is what SURVIVES, so filtering it finds nothing and the memory is " +
                "never cleared — a shared mailbox the user DELETED then comes back wearing its " +
                "old settings the next time the server offers the share, and a deleted login " +
                "leaves its shares filed in prefs for good.",
            listOf(
                "val before = accounts()",
                "val remaining = before.filterNot { it.id in ids }",
                "saveLinkedMemory(forgetRemovedLinked(linkedMemory(), before.filter { it.id in ids }))",
                "saveAccounts(remaining)",
            ),
            block("val before = accounts()", 4),
        )
    }

    /**
     * The THIRD removal path, pinned like its sibling. `remove(id)` is what `dismissImport`
     */
    @Test fun `remove forgets what it removes too, on the third removal path`() {
        assertEquals(
            "AccountStore.remove no longer forgets what it removes. A share dismissed from the " +
                "'accounts to sign in' list would keep its settings filed in prefs, and come back " +
                "wearing them the next time the server offers the share — the user's deletion " +
                "undone in silence, and PRIVACY.md's removal promise false on this path.",
            listOf(
                "val remaining = list.filterNot { it.id == id }",
                "saveLinkedMemory(forgetRemovedLinked(linkedMemory(), list.filter { it.id == id }))",
                "saveAccounts(remaining)",
            ),
            block("val remaining = list.filterNot { it.id == id }", 3),
        )
    }

    /**
     * The key's LITERAL value, and that it is not the account list's. Everything the memory
     */
    @Test fun `the memory has its own key, and it is not the account list's`() {
        assertEquals(
            "KEY_LINKED_MEMORY changed value. The comfort memory shares a preferences file with " +
                "the account list; any collision makes saveLinkedMemory overwrite somebody else's " +
                "blob, and pointing it at the account list costs the user every account she has.",
            listOf("""const val KEY_LINKED_MEMORY = "linked_account_memory""""),
            block("const val KEY_LINKED_MEMORY", 1),
        )
        assertNotEquals(
            "KEY_LINKED_MEMORY and KEY_ACCOUNTS are now the same string. The memory would be " +
                "written over the account list, which then fails to decode for good.",
            block("const val KEY_ACCOUNTS", 1).single().substringAfter("="),
            block("const val KEY_LINKED_MEMORY", 1).single().substringAfter("="),
        )
    }

    @Test fun `the memory is read defensively and answers an empty map on any failure`() {
        assertEquals(
            "AccountStore.linkedMemory lost its runCatching, or no longer falls back to an EMPTY " +
                "map. This blob is written by a build the user may later downgrade from, and it " +
                "is decoded on every connect: a throw here would take down reconcileLinkedAccounts " +
                "itself, which is the code path that MAINTAINS the account list. An unreadable " +
                "memory must cost exactly today's behaviour — a re-granted share minted from the " +
                "constructor — and nothing more.",
            listOf(
                "private fun linkedMemory(): Map<String, List<StoredAccount>> = runCatching {",
                "prefs.getString(KEY_LINKED_MEMORY, null)",
                "?.let { json.decodeFromString<Map<String, List<StoredAccount>>>(it) }",
                ".orEmpty()",
                "}.getOrDefault(emptyMap())",
            ),
            block("private fun linkedMemory()", 5),
        )
    }

    /**
     * The write, pinned as hard as the read, and pinned for what it does NOT do: it writes its
     */
    @Test fun `the memory is written outside the account blob's gate, and cannot throw`() {
        assertEquals(
            "AccountStore.saveLinkedMemory changed shape. It must write KEY_LINKED_MEMORY " +
                "directly, inside a runCatching, and NEVER through the account blob's gate: this " +
                "is comfort state, and it must not be able to block or corrupt the write of " +
                "KEY_ACCOUNTS. Route it through gate.writeGuarded and an unreadable memory can " +
                "cost the user her accounts; drop the runCatching and a failed encode throws out " +
                "of removeCascading, between the password wipe and saveAccounts.",
            listOf(
                "private fun saveLinkedMemory(memory: Map<String, List<StoredAccount>>) {",
                "runCatching {",
                "prefs.edit().putString(KEY_LINKED_MEMORY, json.encodeToString(memory)).apply()",
                "}",
                "}",
            ),
            block("private fun saveLinkedMemory(", 5),
        )
    }

    /**
     * [count] consecutive CODE lines of `AccountStore.kt` from the single one starting with
     */
    private fun block(prefix: String, count: Int, before: Int = 0, path: String = ACCOUNT_STORE): List<String> {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(maxOf(start - before, 0), minOf(start - before + count + before, code.size))
    }

    /** [path]'s lines, trimmed, comments and blanks dropped so no rule is satisfied by prose. */
    private fun codeLines(path: String): List<String> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        return java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val ACCOUNT_STORE = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
    }
}
