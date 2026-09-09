package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the `~trash` mark STOPS — [MailboxEntity.toMailbox], EXECUTED.
 */
class UnelectedRoleStopsAtTheDataBoundaryTest {

    private fun row(role: String?) = MailboxEntity(
        accountId = "acc-1",
        id = "Shared/team/Trash",
        name = "Trash",
        role = role,
        parentId = "Shared/team",
        sortOrder = 6003,
        totalEmails = 12,
        unreadEmails = 3,
    )

    @Test fun aMarkedTrashCrossesAsNoRoleAtAll() {
        assertNull(row("~trash").toMailbox().role)
    }

    @Test fun aMarkedJunkCrossesAsNoRoleAtAll() {
        assertNull(row("~junk").toMailbox().role)
    }

    /** The witness: the folder that WON the role still crosses as the trash, or the erasure would
     *  be taking the icon and the delete destination off the real one. */
    @Test fun theElectedTrashKeepsItsRoleAcross() {
        assertEquals("trash", row("trash").toMailbox().role)
        assertEquals("junk", row("junk").toMailbox().role)
        assertEquals("inbox", row("inbox").toMailbox().role)
        assertEquals("sent", row("sent").toMailbox().role)
    }

    @Test fun aFolderThatNeverHadARoleStillCrossesWithNone() {
        assertNull(row(null).toMailbox().role)
    }

    /**
     * The gesture this buys back. `InboxScreen` shows New subfolder / Rename / Delete only when
     */
    @Test fun theFolderMenuStillOffersSubfolderRenameAndDeleteOnALosingSharedTrash() {
        val onScreen = row("~trash").toMailbox()
        assertTrue(
            "a folder that lost its trash claim must reach the drawer as an ordinary folder, or " +
                "its ⋮ menu is down to Watch — no rename, no delete",
            onScreen.role == null,
        )
    }

    /** And the elected trash keeps the menu it always had (no subfolder/rename/delete on it). */
    @Test fun theFolderMenuStillWithholdsThemFromTheRealTrash() {
        assertTrue(row("trash").toMailbox().role != null)
    }

    /** The erasure touches the ROLE and nothing else: same id, name, parent, rank and counters, so
     *  the drawer row is character for character the one `main` drew. */
    @Test fun everyOtherFieldCrossesUntouched() {
        val marked = row("~trash").toMailbox()
        assertEquals("Shared/team/Trash", marked.id)
        assertEquals("Trash", marked.name)
        assertEquals("Shared/team", marked.parentId)
        assertEquals(6003, marked.sortOrder)
        assertEquals(12, marked.totalEmails)
        assertEquals(3, marked.unreadEmails)
        assertEquals(3, marked.unreadForList)
        assertNull("the role is still erased, and that is what buys the ⋮ menu back", marked.role)
    }

    /**
     * The ONE field that now differs from the role-less row this used to be equal to, and why it
     */
    @Test fun theLostClaimIsTheSINGLEDifferenceFromTheRoleLessRow() {
        val marked = row("~trash").toMailbox()
        val roleLess = row(null).toMailbox()
        assertEquals(roleLess.copy(lostRoleClaim = true), marked)
        assertTrue(marked.lostRoleClaim)
        assertFalse(roleLess.lostRoleClaim)
    }

    /**
     * The case that closes the defect: a `Junk` and a `Spam` on one IMAP account, one of them
     */
    @Test fun aLosingJunkCrossesWithNoRoleAndACarriedClaim() {
        val loser = row("~junk").toMailbox()
        assertNull("a lost claim is not a role and must not reach the screen as one", loser.role)
        assertTrue("the losing Spam must stay reachable from the drawer (#174)", loser.lostRoleClaim)
    }

    /** And nothing else carries it: an elected role, and no role at all, are not lost claims. */
    @Test fun anElectedRoleAndNoRoleAtAllCarryNoClaim() {
        assertFalse(row("junk").toMailbox().lostRoleClaim)
        assertFalse(row("trash").toMailbox().lostRoleClaim)
        assertFalse(row("inbox").toMailbox().lostRoleClaim)
        assertFalse(row("sent").toMailbox().lostRoleClaim)
        assertFalse(row(null).toMailbox().lostRoleClaim)
    }

    /**
     * The erasure is not a hole in the search fix: what excludes a losing trash reads the STORED
     * role, never this model. Both filters still answer on the marked row.
     */
    @Test fun theSearchFiltersStillSeeTheMarkOnTheStoredRow() {
        val stored = listOf(
            MailboxIdRole("i", "inbox"),
            MailboxIdRole("t", "~trash"),
        )
        assertEquals(listOf("i"), searchableFolderIds(stored))
        assertEquals(listOf("t"), excludedSearchFolderIds(stored))
    }
}
