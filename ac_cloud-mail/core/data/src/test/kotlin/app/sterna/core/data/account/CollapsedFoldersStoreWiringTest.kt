package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, same instrument and disclaimer as [ShowOnlySubscribedFoldersStoreWiringTest]: it
 */
class CollapsedFoldersStoreWiringTest {

    @Test fun `the getter answers from the stored account, falling back to an empty registry`() {
        assertEquals(
            "AccountStore.collapsedFolders no longer answers from the account the id names. " +
                "Answering a constant makes the whole memory inert while every other test stays " +
                "green; and an id it cannot resolve must read as an EMPTY registry, which is the " +
                "drawer exactly as it behaves today.",
            listOf("fun collapsedFolders(id: String): Map<String, Boolean> = account(id)?.collapsedFolders ?: emptyMap()"),
            block("fun collapsedFolders(", 1),
        )
    }

    @Test fun `the setter writes the mapped list back through saveAccounts, under the lock`() {
        assertEquals(
            "AccountStore.setFolderCollapsed changed shape. It must hand the WHOLE account list to " +
                "withCollapsedFolder with the id AND the folder id it was given, and store the " +
                "result through saveAccounts under @Synchronized. Passing anything else for the id " +
                "writes one account's fold onto another's; dropping @Synchronized loses whatever " +
                "another thread wrote to the account blob in the meantime — a watch flag, an " +
                "identity, a whole linked account — with every behavioural test still green.",
            listOf(
                "@Synchronized",
                "fun setFolderCollapsed(id: String, folderId: String, collapsed: Boolean) {",
                "saveAccounts(withCollapsedFolder(accounts(), id, folderId, collapsed))",
                "}",
            ),
            block("fun setFolderCollapsed(", 3, before = 1),
        )
    }

    /**
     * The re-key after an IMAP rename, pinned like its twin. On IMAP the id IS the path, so a
     */
    @Test fun `the rename re-key delegates to the pure function, under the lock`() {
        assertEquals(
            "AccountStore.replaceCollapsedFolder is gone or changed shape. It must hand the WHOLE " +
                "account list to withRenamedCollapsedFolder with the id, BOTH folder ids and the " +
                "delimiter it was given, and store the result through saveAccounts under " +
                "@Synchronized. Executed next door on real arguments ([CollapsedFoldersTest]); " +
                "what only this can see is that the store calls it at all, and with those " +
                "arguments in that order — swap oldId and newId and the fold moves onto a path " +
                "that no longer exists, silently.",
            listOf(
                "@Synchronized",
                "fun replaceCollapsedFolder(id: String, oldId: String, newId: String, delimiter: String = \"/\") {",
                "saveAccounts(withRenamedCollapsedFolder(accounts(), id, oldId, newId, delimiter))",
                "}",
            ),
            block("fun replaceCollapsedFolder(", 3, before = 1),
        )
    }

    /**
     * The two re-keys, SIDE BY SIDE in `MailRepository.renameFolder`, with the same delimiter.
     */
    @Test fun `the folder rename re-keys the watch flags AND the fold registry, same delimiter`() {
        assertEquals(
            "MailRepository.renameFolder no longer re-keys BOTH registries with the delimiter it " +
                "resolved for this account. Drop the second line and the folder the user had " +
                "opened by hand folds itself the moment it is renamed; hand it '/' instead of " +
                "`delim` and nothing moves at all on a Dovecot account.",
            listOf(
                "accountStore.replaceWatchedFolder(credentials.id, mailboxId, newPath, delim)",
                "accountStore.replaceCollapsedFolder(credentials.id, mailboxId, newPath, delim)",
            ),
            block("accountStore.replaceWatchedFolder(credentials.id", 2, path = MAIL_REPOSITORY),
        )
    }

    /**
     * V5's one line of repository, and the only thing that can read it: nothing in this module
     */
    @Test fun `the drawer's badge-availability flag delegates to the one IMAP test, negated`() {
        assertEquals(
            "MailRepository.folderRowsBadgeUnread is gone or changed shape. It must stay the " +
                "NEGATION of isImapAccount for the id it was handed. ⛔ Read the reason on the " +
                "function before touching it: since #247 gave every protocol the same live count, " +
                "this no longer says whether a row CAN badge — it is the #185 default-fold policy " +
                "and nothing else. Lose the `!` and every IMAP drawer folds its custom parents on " +
                "next launch, after a whole account lifetime of staying open, because the counts " +
                "arrived — a silent rearrangement nobody asked for.",
            listOf("fun folderRowsBadgeUnread(accountId: String): Boolean = !isImapAccount(accountId)"),
            block("fun folderRowsBadgeUnread(", 1, path = MAIL_REPOSITORY),
        )
        assertEquals(
            "MailRepository asks the account STORE for a protocol somewhere other than " +
                "isImapAccount, or isImapAccount's own test changed shape. There is exactly one " +
                "such lookup and it carries the written decision about the folder counters; a " +
                "copy of it elsewhere is a second answer nobody will keep in step. ⛔ The WHOLE " +
                "line is pinned, never a startsWith over a count: a count of prefixes is blind to " +
                "every mutation that LENGTHENS the line (`… && false` right here would restore " +
                "the IMAP default and stay green), and this repo has been bitten by that three " +
                "times. The credentials.protocol tests elsewhere are a different question and are " +
                "deliberately not matched: they read no store.",
            listOf("accountStore.account(accountId)?.protocol == MailProtocol.IMAP"),
            codeLines(MAIL_REPOSITORY).filter {
                it.contains("accountStore.account(") && it.contains("MailProtocol.IMAP")
            },
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
        const val MAIL_REPOSITORY = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
    }
}
