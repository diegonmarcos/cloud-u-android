package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxUnread
import app.sterna.core.data.filter.SourceText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The unified unread total, BROKEN DOWN per account — the number a large widget cell shows one
 */
class UnifiedUnreadByAccountTest {

    /** Neither branch is ever taken by accident: the fakes answer with numbers nothing else uses. */
    private fun imapOnly(vararg ids: String): (String) -> Boolean = { it in ids }

    /**
     * Codeberg #121, executed. Two accounts on the same server are routinely handed the SAME
     */
    @Test fun `two accounts sharing a mailbox id each get their own number`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("a" to "42", "b" to "42"),
            live = listOf(
                MailboxUnread(accountId = "a", mailboxId = "42", count = 3),
                MailboxUnread(accountId = "b", mailboxId = "42", count = 5),
            ),
            isImap = imapOnly(),
            storedUnread = { error("no IMAP account here, the stored counter must not be read") },
        )

        assertEquals(
            "matching on the mailbox id alone folds two same-server accounts onto one row: each " +
                "account must be looked up by BOTH halves of its scope (Codeberg #121).",
            mapOf("a" to 3, "b" to 5),
            counts,
        )
    }

    /**
     * The OTHER half of the same guard, and it had no witness at all: every other case here hands
     */
    @Test fun `an account's line is its inbox, not whichever folder Room listed first`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("a" to "inbox-1"),
            live = listOf(
                MailboxUnread(accountId = "a", mailboxId = "archive-9", count = 7),
                MailboxUnread(accountId = "a", mailboxId = "inbox-1", count = 2),
            ),
            isImap = imapOnly(),
            storedUnread = { error("no IMAP account here, the stored counter must not be read") },
        )

        assertEquals(
            "matching on the account id alone reads whatever folder the aggregate listed first: " +
                "the inbox must be found by BOTH halves of its scope, folder id included.",
            mapOf("a" to 2),
            counts,
        )
    }

    /**
     * The two halves of the app side by side. A JMAP inbox contributes the live Room aggregate —
     */
    @Test fun `an IMAP account contributes its stored counter, a JMAP account its live aggregate`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("imap" to "INBOX", "jmap" to "inbox-1"),
            live = listOf(
                MailboxUnread(accountId = "jmap", mailboxId = "inbox-1", count = 6),
                // What the IMAP account's windowed cache holds — deliberately NOT its number.
                MailboxUnread(accountId = "imap", mailboxId = "INBOX", count = 1),
            ),
            isImap = imapOnly("imap"),
            storedUnread = { id -> if (id == "imap") 9 else error("only IMAP reads the store, not $id") },
        )

        assertEquals(
            "the IMAP account must carry its stored server counter (9), not the 1 row its " +
                "windowed cache happens to hold; the JMAP account must carry the live aggregate.",
            mapOf("imap" to 9, "jmap" to 6),
            counts,
        )
    }

    /**
     * An account with no inbox is ABSENT, never an entry worth 0. `UnifiedInbox.countedInboxes`
     */
    @Test fun `an account that is not in the scopes is absent, not a zero`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("synced" to "ia"),
            live = listOf(MailboxUnread(accountId = "synced", mailboxId = "ia", count = 4)),
            isImap = imapOnly(),
            storedUnread = { error("an account outside the scopes must not be read at all") },
        )

        assertEquals(
            "a never-synced account must not appear at all: `{synced=4, never-synced=0}` would " +
                "put a 0 on the widget, which claims something the app does not know.",
            mapOf("synced" to 4),
            counts,
        )
        assertEquals("and the map holds exactly the scoped accounts", 1, counts.size)
    }

    /** A JMAP inbox Room has never aggregated is 0 — cached nothing, so nothing unread to show. */
    @Test fun `a scoped inbox missing from the live aggregate counts zero`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("a" to "ia"),
            live = emptyList(),
            isImap = imapOnly(),
            storedUnread = { error("a JMAP account must not fall back to the stored counter") },
        )

        assertEquals(mapOf("a" to 0), counts)
    }

    /**
     * WHOLE LINES, never a `contains`: a substring rule is satisfied by every mutation that
     */
    @Test fun `the repository hands the decision the mode-appropriate aggregate and the store`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt",
        )
        assertEquals(
            "MailRepository.observeUnifiedInboxUnreadByAccount(scopes) must combine the two live " +
                "aggregates with the conversation-view setting and hand unreadByAccountFrom the " +
                "mode-appropriate one, the IMAP predicate and the stored counter.",
            listOf(
                "fun observeUnifiedInboxUnreadByAccount(scopes: List<Pair<String, String>>): Flow<Map<String, Int>> {",
                "val conversationView = settings?.conversationView ?: flowOf(true)",
                "return combine(",
                "emailDao.observeThreadUnreadCounts(),",
                "emailDao.observeMessageUnreadCounts(),",
                "conversationView,",
                ") { threads, messages, conversation ->",
                "unreadByAccountFrom(",
                "scopes = scopes,",
                "live = if (conversation) threads else messages,",
                "isImap = ::isImapAccount,",
                "storedUnread = { accountId -> accountStore.account(accountId)?.unread ?: 0 },",
                ")",
                "}",
                "}",
            ),
            SourceText.codeLines(
                SourceText.functionSource(
                    source,
                    "fun observeUnifiedInboxUnreadByAccount(scopes: List<Pair<String, String>>)",
                ),
            ),
        )
    }
}
