package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchableFoldersTest {

    private fun folders(vararg pairs: Pair<String, String?>) = pairs.map { MailboxIdRole(it.first, it.second) }

    @Test fun trashAndJunkAreLeftOut() {
        val ids = searchableFolderIds(
            folders("i" to "inbox", "a" to "archive", "j" to "junk", "t" to "trash"),
        )
        assertEquals(listOf("i", "a"), ids)
    }

    @Test fun spamIsTheOtherNameForJunk() {
        assertEquals(listOf("i"), searchableFolderIds(folders("i" to "inbox", "s" to "spam")))
    }

    @Test fun caseAndSpacingDoNotDecide() {
        assertEquals(listOf("i"), searchableFolderIds(folders("i" to "inbox", "t" to " Trash ")))
    }

    @Test fun aFolderWithoutARoleIsSearched() {
        assertEquals(listOf("i", "x"), searchableFolderIds(folders("i" to "inbox", "x" to null)))
    }

    @Test fun theGivenOrderIsKept() {
        assertEquals(
            listOf("i", "a", "s", "d", "x"),
            searchableFolderIds(
                folders("i" to "inbox", "a" to "archive", "s" to "sent", "d" to "drafts", "x" to "custom"),
            ),
        )
    }

    // excludedSearchFolderIds is the exact complement (same role source), used to exclude Trash/Junk
    // from the JMAP server search and the FTS crawl so every server behaves like the IMAP walk.
    @Test fun excludedIsJustTheTrashAndJunkFolders() {
        assertEquals(
            listOf("j", "t"),
            excludedSearchFolderIds(
                folders("i" to "inbox", "a" to "archive", "j" to "junk", "t" to "trash"),
            ),
        )
    }

    @Test fun excludedCatchesSpamAndIgnoresCaseAndSpacing() {
        assertEquals(listOf("s", "t"), excludedSearchFolderIds(folders("i" to "inbox", "s" to "SPAM", "t" to " Trash ")))
    }

    @Test fun excludedIsEmptyWhenNothingIsTrashOrJunk() {
        assertEquals(emptyList<String>(), excludedSearchFolderIds(folders("i" to "inbox", "x" to null)))
    }

    // A folder that CLAIMED trash or junk and lost the role to another folder of the account is
    // stored with the role marked (`~trash`, `~junk` — imapMailboxEntity). It is an ordinary folder
    // everywhere else, but a search must skip it just as it skips the elected one: a shared
    // `Shared/…/Trash` holds mail the user threw away.
    @Test fun aTrashThatLostTheRoleIsStillLeftOut() {
        assertEquals(
            listOf("i", "a"),
            searchableFolderIds(folders("i" to "inbox", "a" to "archive", "t" to "trash", "s" to "~trash")),
        )
    }

    @Test fun aJunkThatLostTheRoleIsStillLeftOut() {
        assertEquals(listOf("i"), searchableFolderIds(folders("i" to "inbox", "s" to "~junk")))
    }

    @Test fun excludedReturnsTheFoldersThatLostTheirClaimToo() {
        assertEquals(
            listOf("t", "s", "j"),
            excludedSearchFolderIds(
                folders("i" to "inbox", "t" to "trash", "s" to "~trash", "j" to "~junk", "x" to null),
            ),
        )
    }

    // The witness: the mark is not a wildcard. Only the roles a search already skipped are marked
    // (imapMailboxEntity marks nothing else), so a role that merely begins with the mark is a
    // folder like any other — otherwise the three cases above would pass under a rule that hides
    // every folder whose role starts with '~'.
    @Test fun aMarkOnAnyOtherRoleDoesNotLeaveAFolderOutOfASearch() {
        assertEquals(
            listOf("i", "s", "a", "m"),
            searchableFolderIds(folders("i" to "inbox", "s" to "~sent", "a" to "~archive", "m" to "~")),
        )
    }

    @Test fun searchableAndExcludedPartitionTheFolders() {
        val all = folders("i" to "inbox", "a" to "archive", "j" to "junk", "t" to "trash", "x" to null)
        assertEquals(listOf("i", "a", "x"), searchableFolderIds(all))
        assertEquals(listOf("j", "t"), excludedSearchFolderIds(all))
    }

    // ---- may the answer be called a total? ----
    //
    // An IMAP search only ever covers the folders the drawer has cached. When that list is empty
    // the walk falls back to the inbox alone, and calling THAT a total is how "flagged only" would
    // end up quietly hiding every star filed in a folder that was never synced — under a flat
    // "No results" if there happened to be none in the inbox.

    @Test fun aWalkOverKnownFoldersThatFinishedUnderTheCapIsATotal() {
        assertTrue(imapSearchComplete(listOf("i", "a"), walkComplete = true, found = 3, limit = 50))
    }

    @Test fun theInboxFallbackIsNeverATotal() {
        // No cached folder list: whatever the walk found, it looked at a fraction of the account.
        assertFalse(imapSearchComplete(emptyList(), walkComplete = true, found = 3, limit = 50))
        // Including — especially — when it found nothing.
        assertFalse(imapSearchComplete(emptyList(), walkComplete = true, found = 0, limit = 50))
    }

    @Test fun aWalkThatCouldNotFinishIsNeverATotal() {
        // A folder that refused to be searched, or an attachment scan stopped on its cap.
        assertFalse(imapSearchComplete(listOf("i"), walkComplete = false, found = 3, limit = 50))
        assertFalse(imapSearchComplete(listOf("i"), walkComplete = false, found = 0, limit = 50))
    }

    @Test fun fillingTheCapMeansTheServerMayHaveHadMore() {
        assertFalse(imapSearchComplete(listOf("i"), walkComplete = true, found = 50, limit = 50))
        assertTrue(imapSearchComplete(listOf("i"), walkComplete = true, found = 49, limit = 50))
    }
}
