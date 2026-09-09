package app.sterna.ui.home

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.collapsedFolderIds
import app.sterna.ui.inbox.drawerUnreadCount
import app.sterna.ui.inbox.mailboxTree
import app.sterna.ui.inbox.visibleFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Home page SAYS about each account, EXECUTED. Two accounts is the case built explicitly
 * here: it is where a per-account page is always wrong, and the failure it produces — plausible
 * numbers filed under the wrong name — is one nothing on screen would betray.
 */
class AccountMailStatsTest {

    private fun mailbox(
        id: String,
        unread: Int = 0,
        role: String? = null,
        subscribed: Boolean = true,
    ) = Mailbox(
        id = id,
        name = id.substringAfterLast('/'),
        role = role,
        unreadForList = unread,
        isSubscribed = subscribed,
    )

    private fun account(
        id: String,
        protocol: MailProtocol = MailProtocol.JMAP,
        onlySubscribed: Boolean = false,
    ) = StoredAccount(
        id = id,
        server = "$id.example.org",
        username = "$id@example.org",
        accountName = id,
        protocol = protocol,
        showOnlySubscribedFolders = onlySubscribed,
    )

    /** The plan's tree, the same shape [app.sterna.ui.inbox.DrawerUnreadCountTest] uses. */
    private val work = listOf(
        mailbox("Inbox", unread = 5, role = "inbox"),
        mailbox("Travail", unread = 1),
        mailbox("Travail/Client A", unread = 3),
        mailbox("Travail/Client A/Devis", unread = 4),
        mailbox("Travail/Client B", unread = 2),
    )

    /**
     * THE assertion this page exists to keep true. Both accounts hold a folder called `Inbox` and a
     * folder called `Travail` — servers number mailboxes per account, so colliding ids are the
     * normal case and not a contrivance — and every number differs between them.
     */
    @Test fun `two accounts each answer their own numbers, with folder ids that collide`() {
        val personal = listOf(
            mailbox("Inbox", unread = 9, role = "inbox"),
            mailbox("Travail", unread = 0, subscribed = false),
        )
        val cards = accountMailStatsList(
            accounts = listOf(account("work"), account("personal")),
            foldersPerAccount = listOf(work, personal),
            cachedMessages = mapOf("work" to 4_000, "personal" to 12),
            unreadIsCounted = { true },
        )
        assertEquals("one card per account, in the order the accounts came in.", 2, cards.size)
        assertEquals(
            "the first card must carry the FIRST account's numbers: 5+1+3+4+2 = 15 unread over its " +
                "five folders, 4000 cached, 5 folders all subscribed. A 9 unread, a 12 cached or a " +
                "2 folders is the personal account's list read under the work account's name — the " +
                "defect this whole file is here for, and one that looks entirely plausible on screen.",
            AccountMailStats(
                accountId = "work",
                label = "work",
                color = null,
                protocol = MailProtocol.JMAP,
                host = "work.example.org",
                unread = 15,
                cachedMessages = 4_000,
                folders = 5,
                subscribedFolders = 5,
            ),
            cards[0],
        )
        assertEquals(
            "the second card must carry the SECOND account's numbers: 9 unread, 12 cached, 2 " +
                "folders of which 1 subscribed. A 15, a 4000 or a 5 is the work account bleeding " +
                "across; identical cards mean both were built from one list.",
            AccountMailStats(
                accountId = "personal",
                label = "personal",
                color = null,
                protocol = MailProtocol.JMAP,
                host = "personal.example.org",
                unread = 9,
                cachedMessages = 12,
                folders = 2,
                subscribedFolders = 1,
            ),
            cards[1],
        )
    }

    /**
     * The unread on this page and the unread on the drawer's rows are read on the same screen
     * seconds apart, so they have to be the same arithmetic and not two spellings of it. This
     * assertion computes the drawer's total the way the DRAWER does — the folded set it derives,
     * the rows [mailboxTree] actually draws, each row's badge from [drawerUnreadCount] — and
     * demands the page's single number equal it. The two walks are genuinely different: the
     * drawer's stops descending at a folded row and recovers what is hidden through the badge,
     * this page's never folds anything.
     */
    @Test fun `the account total is what the drawer's own rows badge, added up`() {
        val drawn = visibleFolders(work, showOnlySubscribed = false)
        // `true`: a JMAP account, whose rows can badge unread, which is what makes the default
        // fold apply at all ([collapsedFolderIds]).
        val folded = collapsedFolderIds(drawn, choices = emptyMap(), rowsBadgeUnread = true)
        val onScreen = mailboxTree(drawn, folded).sumOf { drawerUnreadCount(it.mailbox, drawn, folded) }
        assertEquals(
            "the drawer opens on this tree with Travail and Client A folded by default, so it " +
                "draws two rows: Inbox badging 5 and Travail badging 1+3+4+2 = 10. Its visible " +
                "total is 15 and the page must say 15 too.",
            15,
            onScreen,
        )
        assertEquals(
            "the page's unread total no longer equals what the drawer badges. A 28 is what asking " +
                "with everything folded gives on this tree (5+10+7+4+2): every folded badge added " +
                "to the folders it already contains, which is the double count [accountUnreadTotal] " +
                "avoids by asking with nothing folded. Either way the two numbers, read on one " +
                "screen seconds apart, disagree.",
            onScreen,
            accountUnreadTotal(drawn),
        )
    }

    /**
     * IMAP folder rows carry no unread count at all: `ImapMailService.imapMailboxEntity` writes a
     * hard 0 into every folder's counter, and `MailRepository.folderRowsBadgeUnread` is the
     * account's own answer to whether a row can badge anything. A page printing 0 there would be
     * stating in numerals that there is no unread mail, which nothing on this phone knows.
     */
    @Test fun `an account whose rows cannot badge unread answers nothing, not zero`() {
        val cards = accountMailStatsList(
            accounts = listOf(account("imap", protocol = MailProtocol.IMAP)),
            foldersPerAccount = listOf(work),
            cachedMessages = mapOf("imap" to 7),
            unreadIsCounted = { false },
        )
        assertNull(
            "an IMAP account must answer null so the screen can say so in words. A 0 is the " +
                "silent lie; a 15 is the folder counters, which IMAP writes as zeroes anyway.",
            cards.single().unread,
        )
        assertEquals(
            "everything the protocol does NOT put out of reach must still be answered — the folder " +
                "counts and the cache count are protocol-independent.",
            listOf(5, 5, 7),
            cards.single().let { listOf(it.folders, it.subscribedFolders, it.cachedMessages) },
        )
    }

    /**
     * The two lists [accountMailStats] takes are not interchangeable. An account asking for
     * subscribed folders only (#174) still HAS its unsubscribed folders — the folder counts are
     * about the account — but the drawer never draws them, so their unread is on no row and the
     * page must not add it.
     */
    @Test fun `hidden folders count towards the folder totals and not towards the unread`() {
        val folders = listOf(
            mailbox("Inbox", unread = 5, role = "inbox"),
            mailbox("Vieux", unread = 40, subscribed = false),
        )
        val card = accountMailStatsList(
            accounts = listOf(account("work", onlySubscribed = true)),
            foldersPerAccount = listOf(folders),
            cachedMessages = emptyMap(),
            unreadIsCounted = { true },
        ).single()
        assertEquals(
            "the unread must be 5: `Vieux` is unsubscribed and this account hides it, so the drawer " +
                "badges it nowhere. A 45 is the page counting mail the drawer beside it cannot show.",
            5,
            card.unread,
        )
        assertEquals(
            "both folders must still be counted — the account has two, one of them subscribed. A 1 " +
                "here is the drawn list reused for a question about the account, and then 'folders' " +
                "and 'subscribed folders' would always be the same number and say nothing.",
            listOf(2, 1),
            listOf(card.folders, card.subscribedFolders),
        )
        assertEquals(
            "an account with nothing cached is absent from the aggregate's map, which reads as 0.",
            0,
            card.cachedMessages,
        )
    }

    /** An IMAP account keeps its host in its own field; [StoredAccount.server] is the JMAP URL. */
    @Test fun `each protocol names the host it actually connects to`() {
        assertEquals(
            "an IMAP account must show imapHost. Falling through to `server` shows the JMAP " +
                "session URL, which on an IMAP account is whatever autoconfig happened to leave there.",
            "imap.example.org",
            accountHost(account("a", protocol = MailProtocol.IMAP).copy(imapHost = "imap.example.org")),
        )
        assertEquals(
            "an IMAP account configured by hand may have no imapHost yet; `server` is better than " +
                "an empty line where the server's name belongs.",
            "a.example.org",
            accountHost(account("a", protocol = MailProtocol.IMAP)),
        )
        assertEquals("a JMAP account names its session endpoint.", "a.example.org", accountHost(account("a")))
    }

    /** A fresh install. Nothing must throw on the way to the empty state. */
    @Test fun `no accounts is an empty list of cards, not a failure`() {
        assertEquals(
            emptyList<AccountMailStats>(),
            accountMailStatsList(emptyList(), emptyList(), emptyMap()) { true },
        )
    }
}
