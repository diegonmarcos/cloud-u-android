package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT the "unread" view pages — the scope, run as a function.
 */
class UnreadViewScopesTest {

    private fun folder(id: String, role: String? = null) = Mailbox(id = id, name = id, role = role)

    @Test
    fun `the four kinds of folder the view is not about are left out`() {
        val scopes = unreadViewScopes(
            accountId = "a",
            folders = listOf(
                folder("inbox", "inbox"),
                folder("trash", "trash"),
                folder("junk", "junk"),
                folder("spam", "spam"),
                folder("sent", "sent"),
                folder("drafts", "drafts"),
                folder("archive", "archive"),
            ),
        )
        assertEquals(
            "Trash, Junk, Spam, Sent and Drafts must not be paged by a view called \"Unread\": what " +
                "was thrown away or refused is not waiting to be read, and outgoing mail has no " +
                "meaningful read state. Scopes were: $scopes",
            listOf("a" to "inbox", "a" to "archive"),
            scopes,
        )
    }

    @Test
    fun `a user folder with no role at all is paged`() {
        // The rule excludes what it can NAME. A folder the user made ("Projets") has no role, and
        // unread mail filed there is unread mail — dropping it would make the view lie by omission,
        // which is the one direction of error this scope cannot afford.
        val scopes = unreadViewScopes("a", listOf(folder("inbox", "inbox"), folder("projets")))
        assertEquals(listOf("a" to "inbox", "a" to "projets"), scopes)
    }

    @Test
    fun `a folder whose trash claim was refused is an ordinary folder`() {
        // `~trash` is MailRepository's UNELECTED_ROLE_MARK: this folder claimed the trash role and
        // another folder of the account was elected to it (an account with a personal Trash and a
        val scopes = unreadViewScopes(
            "a",
            listOf(folder("shared-trash", "~trash"), folder("shared-junk", "~junk"), folder("t", "trash")),
        )
        assertEquals(listOf("a" to "shared-trash", "a" to "shared-junk"), scopes)
    }

    @Test
    fun `roles are matched trimmed and case-insensitively`() {
        // The JMAP side hands roles through exactly as the server spells them; only the IMAP side
        // normalises. A server answering "Trash" or " Sent " must not slip a folder of destroyed or
        // outgoing mail into a view of unread mail.
        val scopes = unreadViewScopes(
            "a",
            listOf(folder("t", "Trash"), folder("s", " sent "), folder("j", "JUNK"), folder("i", "inbox")),
        )
        assertEquals("only the Inbox should be left. Scopes were: $scopes", listOf("a" to "i"), scopes)
    }

    @Test
    fun `every scope carries the account id, never a bare folder id`() {
        // Codeberg #121/#31: servers number mailboxes per account, so a bare folder id matches a
        // sibling account's homonymous folder — and a REMOVED account's leftover rows, which no
        // account could then label, sync or act on. See MailRepository.folderScopeSql.
        val scopes = unreadViewScopes("acct-2", listOf(folder("a", "inbox"), folder("b", "archive")))
        assertEquals(listOf("acct-2" to "a", "acct-2" to "b"), scopes)
        assertEquals(
            "every pair must name the account it belongs to",
            listOf("acct-2", "acct-2"),
            scopes.map { it.first },
        )
    }

    @Test
    fun `no current account pages nothing`() {
        // Not "every folder unscoped" and not the previous account's: there is no account to pin
        // the pairs to, and an empty scope list is what pagedMailbox answers PagingData.empty() to.
        assertEquals(
            emptyList<Pair<String, String>>(),
            unreadViewScopes(null, listOf(folder("inbox", "inbox"), folder("archive", "archive"))),
        )
    }

    @Test
    fun `an account with no folders yet pages nothing`() {
        assertEquals(emptyList<Pair<String, String>>(), unreadViewScopes("a", emptyList()))
    }

    @Test
    fun `an account whose every folder is excluded pages nothing`() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            unreadViewScopes("a", listOf(folder("t", "trash"), folder("s", "sent"))),
        )
    }
}
