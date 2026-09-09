package app.sterna.core.data.db

import app.sterna.core.data.mail.ImapMailService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id a local draft carries ([newLocalDraftId]) shares a namespace with the ids of cached server
 */
class LocalDraftIdTest {
    @Test fun `an imap reader gets nothing out of a local draft id`() {
        val id = newLocalDraftId()

        assertNull("a UID was read out of a draft that has never been on a server", ImapMailService.uidOf(id))
        assertNull("a mailbox path was read out of a local draft id", ImapMailService.mailboxOf(id))
    }

    @Test fun `the prefix cannot be mistaken for a JMAP id either`() {
        // RFC 8620 §1.2: a JMAP id is made of A-Za-z0-9, '-' and '_' only. A ':' therefore cannot
        // occur in one, which is what keeps a local id out of that namespace too — and it is why
        // the prefix ends with a colon rather than a dash.
        assertTrue("a local draft id must carry a character no JMAP id can hold", ':' in LOCAL_DRAFT_ID_PREFIX)
        assertTrue(newLocalDraftId().startsWith(LOCAL_DRAFT_ID_PREFIX))
        assertEquals(
            "the id would be read as an IMAP cache id",
            false,
            newLocalDraftId().startsWith("imap:"),
        )
    }

    @Test fun `two drafts minted in a row are two drafts`() {
        assertNotEquals(newLocalDraftId(), newLocalDraftId())
    }
}
