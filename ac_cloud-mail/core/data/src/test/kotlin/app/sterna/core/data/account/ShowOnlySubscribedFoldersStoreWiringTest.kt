package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, same instrument and disclaimer as [UploadSentCopyStoreWiringTest]: it reads
 */
class ShowOnlySubscribedFoldersStoreWiringTest {

    @Test fun `the getter answers from the stored account, falling back to off`() {
        assertEquals(
            "AccountStore.showOnlySubscribedFolders no longer answers from the account the id " +
                "names. Answering a constant makes the whole setting inert while every other test " +
                "stays green; and an id it cannot resolve must read as false — hiding folders for " +
                "an account whose record was not found is how mail disappears from the folder list " +
                "with nobody having asked.",
            listOf("fun showOnlySubscribedFolders(id: String): Boolean = showOnlySubscribedFoldersOf(account(id))"),
            block("fun showOnlySubscribedFolders(", 1),
        )
    }

    @Test fun `the setter writes the mapped list back through saveAccounts, under the lock`() {
        assertEquals(
            "AccountStore.setShowOnlySubscribedFolders changed shape. It must hand the WHOLE " +
                "account list to withShowOnlySubscribedFolders with the id it was given, and store " +
                "the result through saveAccounts under @Synchronized. Passing anything else for " +
                "the id (or dropping the mapped list) writes one account's choice onto another's, " +
                "with every behavioural test still green.",
            listOf(
                "@Synchronized",
                "fun setShowOnlySubscribedFolders(id: String, enabled: Boolean) {",
                "saveAccounts(withShowOnlySubscribedFolders(accounts(), id, enabled))",
                "}",
            ),
            block("fun setShowOnlySubscribedFolders(", 3, before = 1),
        )
    }

    /**
     * [count] consecutive CODE lines of `AccountStore.kt` from the single one starting with
     */
    private fun block(prefix: String, count: Int, before: Int = 0): List<String> {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(maxOf(start - before, 0), minOf(start - before + count + before, code.size))
    }
}
