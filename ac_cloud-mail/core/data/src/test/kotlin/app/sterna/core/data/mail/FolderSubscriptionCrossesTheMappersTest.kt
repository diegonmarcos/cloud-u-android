package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.imap.ImapFolder
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder's subscription, EXECUTED across the three crossings it has to survive to reach the
 */
class FolderSubscriptionCrossesTheMappersTest {

    private fun imapFolder(subscribed: Boolean) = ImapFolder(
        name = "Projekte",
        path = "Shared/team/Projekte",
        role = null,
        delimiter = "/",
        isSubscribed = subscribed,
    )

    private fun jmapMailbox(subscribed: Boolean) = Mailbox(
        id = "mb2",
        name = "Projekte",
        role = null,
        sortOrder = 6,
        totalEmails = 4,
        unreadEmails = 1,
        isSubscribed = subscribed,
    )

    private fun row(subscribed: Boolean) = MailboxEntity(
        accountId = "acc-1",
        id = "mb2",
        name = "Projekte",
        role = null,
        sortOrder = 6001,
        totalEmails = 4,
        unreadEmails = 1,
        isSubscribed = subscribed,
    )

    // --- IMAP listing → cache row ------------------------------------------------------------------

    @Test fun anUnsubscribedImapFolderIsCachedUnsubscribed() {
        assertEquals(false, imapMailboxEntity("acc-1", imapFolder(subscribed = false), index = 3).isSubscribed)
    }

    @Test fun aSubscribedImapFolderIsCachedSubscribed() {
        assertEquals(true, imapMailboxEntity("acc-1", imapFolder(subscribed = true), index = 3).isSubscribed)
    }

    /** The rest of the row is untouched: carrying the subscription is not a rename or a reorder. */
    @Test fun carryingTheSubscriptionChangesNothingElseOnTheRow() {
        val subscribed = imapMailboxEntity("acc-1", imapFolder(subscribed = true), index = 3)
        val not = imapMailboxEntity("acc-1", imapFolder(subscribed = false), index = 3)
        assertEquals(subscribed.copy(isSubscribed = false), not)
        assertEquals("Shared/team/Projekte", not.id)
        assertEquals("Projekte", not.name)
        assertEquals(null, not.role)
        assertEquals(6003, not.sortOrder)
    }

    // --- JMAP model → cache row, and back to the UI model -----------------------------------------

    @Test fun anUnsubscribedJmapMailboxIsCachedUnsubscribed() {
        assertEquals(false, jmapMailbox(subscribed = false).toEntity("acc-1").isSubscribed)
    }

    @Test fun aSubscribedJmapMailboxIsCachedSubscribed() {
        assertEquals(true, jmapMailbox(subscribed = true).toEntity("acc-1").isSubscribed)
    }

    @Test fun anUnsubscribedRowReachesTheUiModelUnsubscribed() {
        assertEquals(false, row(subscribed = false).toMailbox().isSubscribed)
    }

    @Test fun aSubscribedRowReachesTheUiModelSubscribed() {
        assertEquals(true, row(subscribed = true).toMailbox().isSubscribed)
    }

    /** Round trip, both values: nothing in the pair of mappers flips or forgets it. */
    @Test fun theSubscriptionSurvivesTheRoundTripBothWays() {
        assertEquals(false, jmapMailbox(subscribed = false).toEntity("acc-1").toMailbox().isSubscribed)
        assertEquals(true, jmapMailbox(subscribed = true).toEntity("acc-1").toMailbox().isSubscribed)
    }

    // --- what "nobody said" means ------------------------------------------------------------------

    /**
     * Unknown is SUBSCRIBED, on all three types. This volet reads the subscription nowhere yet:
     */
    @Test fun aFolderNobodySaidAnythingAboutIsSubscribed() {
        assertTrue(ImapFolder(name = "Projekte", path = "Projekte", role = null, delimiter = "/").isSubscribed)
        assertTrue(Mailbox(id = "mb2", name = "Projekte").isSubscribed)
        assertTrue(
            MailboxEntity(
                accountId = "acc-1",
                id = "mb2",
                name = "Projekte",
                role = null,
                sortOrder = 6001,
                totalEmails = 4,
                unreadEmails = 1,
            ).isSubscribed,
        )
    }
}
