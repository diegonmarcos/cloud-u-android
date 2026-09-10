package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT — where a folder row's unread count comes from when the local cache has nothing to
 * say about that folder (#247).
 *
 * Nothing in this module can run `MailRepository.observeMailboxes`: it combines two Room flows.
 * So the rule is read off the shipped source, with the instrument [LostRoleClaimBordersTest]
 * already uses over the very same function.
 *
 * THE RULE: the live aggregate wins where it exists, and the row's own stored counter stands where
 * it does not. `observeUnreadByMailbox` is a `GROUP BY accountId, mailboxId` over the cached
 * `emails` table, so a folder with no cached rows yields NO GROUP — not a group of zero. `?: 0`
 * turned that silence into a claim: it read "this folder has no mail in the cache" as "this folder
 * has no unread mail", which is not something the app knows about a folder it has never synced.
 * On JMAP it is provably false, because the server already said otherwise — `Mailbox/get` names no
 * `properties`, so `unreadEmails` arrives for every mailbox and `toMailbox` puts it on the row.
 *
 * The shipped symptom was the owner's: one folder badged `(249)` and the other fifteen badged
 * nothing, because one folder was the one they had open.
 */
class MailboxUnreadFallbackTest {

    @Test
    fun `a folder absent from the live aggregate keeps its stored counter, and never reads as zero`() {
        assertEquals(
            "MailRepository.observeMailboxes stopped falling back to the row's stored count. The " +
                "elvis must land on the mailbox itself — whose unreadForList is that stored " +
                "counter, put there by MailboxMapper — and never on a literal.",
            listOf("unread[row.id]?.let { mailbox.copy(unreadForList = it) } ?: mailbox"),
            codeLines(MAIL_REPOSITORY).filter { it.startsWith("unread[row.id]") },
        )
        assertTrue(
            "a `?: 0` came back to the folder-list read. Every folder the user has not opened " +
                "badges nothing again, and the drawer's Unread tab empties out with it — which " +
                "reads as 'you have no unread mail', the one thing the drawer must not say wrongly.",
            codeLines(MAIL_REPOSITORY).none { "unreadForList = unread[" in it && "?: 0" in it },
        )
    }

    @Test
    fun `the count is still read per folder, from the one combine that feeds every protocol`() {
        assertEquals(
            "the folder-list read changed shape. Since #247 ONE combine serves every protocol — " +
                "IMAP included, whose rows used to be handed through with the hard 0 that " +
                "`imapMailboxEntity` writes — so a branch reappearing here means one protocol has " +
                "quietly stopped counting.",
            1,
            codeLines(MAIL_REPOSITORY).count {
                it.startsWith("combine(mailboxDao.observeAll(accountId), observeUnreadByMailbox(accountId))")
            },
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val MAIL_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MAIL_REPOSITORY_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val MAIL_REPOSITORY: File by lazy { File(root, MAIL_REPOSITORY_PATH) }
    }
}
