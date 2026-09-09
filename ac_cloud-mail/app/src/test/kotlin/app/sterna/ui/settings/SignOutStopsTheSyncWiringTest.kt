package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT. `AccountsViewModel` is an `AndroidViewModel` and this module has neither
 */
class SignOutStopsTheSyncWiringTest {

    private fun signOutBody(): String {
        val source = ACCOUNTS_VM.readText()
        val start = source.indexOf("fun signOut(")
        assertTrue("AccountsViewModel has no signOut() — renamed?", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i)
    }

    private fun lines(): List<String> = signOutBody().lines().map { it.trim() }.filter { !it.startsWith("//") }

    private fun indexOfLine(line: String): Int {
        val at = lines().indexOf(line)
        assertTrue(
            "AccountsViewModel.signOut no longer contains the line:\n  $line\nits body is:\n" +
                lines().joinToString("\n"),
            at >= 0,
        )
        return at
    }

    /**
     * The leading whitespace of [line] inside the body, as written.
     */
    private fun indentOfLine(line: String): Int {
        val raw = signOutBody().lines().firstOrNull { it.trim() == line }
        assertTrue("AccountsViewModel.signOut no longer contains the line:\n  $line", raw != null)
        return raw!!.takeWhile { it == ' ' }.length
    }

    @Test fun `the account leaves the store before anything is purged, and before the coroutine`() {
        // The removal is synchronous and the purges are not. Moving the removal into the
        // coroutine — or below the purge, which looks tidier — reopens the window this volet
        // closed: the walk goes on writing under an id that is still configured while its cache is
        // being purged, and the sweep at the end runs before the pages it should have collected.
        val removal = indexOfLine("val removed = store.removeCascading(id).ifEmpty { toRemove }")
        val launch = indexOfLine("viewModelScope.launch {")
        val purge = indexOfLine("storage.purgeAccount(it)")
        val sweep = indexOfLine("storage.purgeOrphanedAccounts { store.accounts().map { it.id } }")

        assertTrue("the account is now removed from inside the sign-out's coroutine", removal < launch)
        assertTrue("the cache is now purged before the account is removed from the store", removal < purge)
        assertTrue("the orphan sweep now runs before the account is removed", removal < sweep)
        assertTrue("the orphan sweep no longer runs after the per-account purges (#121)", purge < sweep)
    }

    @Test fun `the per-account purge and the general sweep are both still there`() {
        // Neither is replaced by this volet: the guard stops NEW writes, it does not remove the
        // rows a previous life left behind. Dropping either would trade one leak for another.
        indexOfLine("mail.disconnectImap(it)")
        indexOfLine("storage.purgeAccount(it)")
        indexOfLine("storage.purgeOrphanedAccounts { store.accounts().map { it.id } }")
    }

    @Test fun `push is torn down and the cascade resolved while the credentials still exist`() {
        // The other order this function already depended on: UnifiedPush needs the credentials to
        // unregister, and the linked sub-accounts have to be resolved before the removal takes
        // them out (#31). A "stop the sync first" edit that hoisted the removal above these would
        // leak a push subscription per sign-out.
        val cascade = indexOfLine("val toRemove = buildList {")
        val teardown = indexOfLine("store.allCredentials().filter { it.id in toRemove }.forEach {")
        val removal = indexOfLine("val removed = store.removeCascading(id).ifEmpty { toRemove }")
        assertTrue("the cascade is now resolved after the removal", cascade < removal)
        assertTrue("push is now torn down after the credentials are gone", teardown < removal)
    }

    /**
     * SOURCE LINT, and it has to be one: `AccountsViewModel` is an `AndroidViewModel` that no JVM
     */
    @Test fun `both account-scoped preferences are pruned, with the ids that were really removed`() {
        val removal = indexOfLine("val removed = store.removeCascading(id).ifEmpty { toRemove }")
        val banners = indexOfLine("removed.forEach { Notifications.cancelAccount(app, it) }")
        val ids = indexOfLine("val removedIds = removed.toSet()")
        val view = indexOfLine("store.setStoredView(prunedView(store.storedView(), removedIds))")
        val fresh = indexOfLine(
            "store.setRefreshFreshness(prunedFreshness(store.refreshFreshness(), removedIds))",
        )
        val launch = indexOfLine("viewModelScope.launch {")

        assertTrue("the removed ids are now taken before the removal that produces them", removal < ids)
        assertTrue("the pruning now runs before the notification teardown", banners < ids)
        assertTrue("the stored view is now pruned before the ids are resolved", ids < view)
        assertTrue("the freshness register is now pruned before the ids are resolved", ids < fresh)
        assertTrue(
            "the freshness pruning moved into the sign-out's coroutine, where a sign-out that is " +
                "never reached again leaves the register behind",
            fresh < launch,
        )
        // BOTH, not just the register. The stored view is the value that carries the IMAP folder
        // PATH in cleartext, so it is the one that must not slip below the coroutine — and pinning
        // only `fresh < launch` let exactly that move through green.
        assertTrue(
            "the stored view's pruning moved into the sign-out's coroutine: it is the value that " +
                "holds a folder path the reader named herself",
            view < launch,
        )
        // And order is not enough: `indexOfLine` compares trimmed lines, so wrapping all three
        // in `if (removed.size > 1) { … }` keeps every line and every relation above true while
        // the pruning stops running on an ordinary single-account sign-out — the defect reopened
        // in full, suite still green. The pruning runs UNCONDITIONALLY or it does not run.
        val depth = indentOfLine("val removed = store.removeCascading(id).ifEmpty { toRemove }")
        listOf(
            "val removedIds = removed.toSet()",
            "store.setStoredView(prunedView(store.storedView(), removedIds))",
            "store.setRefreshFreshness(prunedFreshness(store.refreshFreshness(), removedIds))",
        ).forEach {
            assertEquals(
                "the pruning is now nested deeper than the removal — a condition around it means " +
                    "some sign-outs no longer prune anything:\n  $it",
                depth,
                indentOfLine(it),
            )
        }
    }

    private companion object {
        const val ACCOUNTS_VM_PATH = "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt"

        val ACCOUNTS_VM: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ACCOUNTS_VM_PATH).isFile }
                ?.let { File(it, ACCOUNTS_VM_PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }
    }
}
