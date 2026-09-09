package app.sterna.core.data.mail

import app.sterna.core.imap.ImapAddress
import app.sterna.core.imap.ImapMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An IMAP account folds into conversations like a JMAP one: the thread key is rebuilt on the
 */
class ImapThreadKeyTest {

    private fun message(
        uid: Long,
        messageId: String?,
        inReplyTo: String?,
        references: String?,
    ) = ImapMessage(
        uid = uid,
        subject = "Re: budget",
        fromName = "Alex Rivera",
        fromEmail = "alex.rivera@masto.top",
        to = listOf(ImapAddress(name = "Team", email = "team@masto.top")),
        dateMillis = 1_780_000_000_000L + uid,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = false,
        messageId = messageId,
        inReplyTo = inReplyTo,
        references = references,
    )

    // --- the rule itself -----------------------------------------------------------------------

    @Test fun theRootOfReferencesWins() {
        assertEquals("<m1@x>", imapThreadKey("<m1@x> <r1@x> <r2@x>", "<r2@x>", "<r3@x>"))
    }

    @Test fun aBlankReferencesFallsBackToInReplyTo() {
        assertEquals("<p@x>", imapThreadKey("", "<p@x>", "<me@x>"))
        assertEquals("<p@x>", imapThreadKey("   ", "<p@x>", "<me@x>"))
        assertEquals("<p@x>", imapThreadKey(null, "<p@x>", "<me@x>"))
    }

    @Test fun withNeitherReferencesNorInReplyToTheMessageIdIsTheKey() {
        assertEquals("<me@x>", imapThreadKey(null, null, "<me@x>"))
        assertEquals("<me@x>", imapThreadKey("", "  ", "<me@x>"))
    }

    @Test fun withNoHeaderAtAllThereIsNoKey() {
        assertNull(imapThreadKey(null, null, null))
        assertNull(imapThreadKey("", "", ""))
    }

    @Test fun commentsAndRunsOfWhitespaceInReferencesDoNotMoveTheRoot() {
        assertEquals("<m1@x>", imapThreadKey("(thread)   <m1@x>\t\t<r1@x>  (end)", null, "<me@x>"))
    }

    @Test fun anInReplyToNamingTwoParentsKeysOnTheFirst() {
        assertEquals("<p1@x>", imapThreadKey(null, "<p1@x> <p2@x>", "<me@x>"))
    }

    @Test fun aBareMessageIdIsWrappedInBrackets() {
        assertEquals("<abc@x>", imapThreadKey(null, null, "abc@x"))
        assertEquals("<abc@x>", imapThreadKey(null, null, "  abc@x  "))
    }

    /**
     * A bracket-less `References` naming SEVERAL ids keys on the FIRST word, wrapped. Were the
     */
    @Test fun aBareReferencesWithSeveralIdsKeysOnTheFirstWord() {
        assertEquals("<a@x>", imapThreadKey("a@x b@x c@x", null, null))
        assertEquals("<p@x>", imapThreadKey(null, "p@x q@x", null))
    }

    @Test fun aPipeInsideAnIdIsKeptAsIs() {
        assertEquals("<a|b@x>", imapThreadKey("<a|b@x> <r@x>", null, null))
        assertEquals("<a|b@x>", imapThreadKey(null, null, "<a|b@x>"))
    }

    // --- through toEntity: the key reaches the row --------------------------------------------

    @Test fun aParentAndItsReplyLandOnTheSameThreadKey() {
        val parent = message(uid = 1, messageId = "<m1@x>", inReplyTo = null, references = null)
        val reply = message(uid = 2, messageId = "<r2@x>", inReplyTo = "<r1@x>", references = "<m1@x> <r1@x>")

        val parentRow = parent.toEntity("accA", "INBOX", 42L, preview = null)
        val replyRow = reply.toEntity("accA", "INBOX", 42L, preview = null)

        assertEquals("the parent is keyed by its own Message-ID", "<m1@x>", parentRow.threadId)
        assertEquals("the reply is keyed by the ROOT of its References", "<m1@x>", replyRow.threadId)
        assertEquals(parentRow.threadId, replyRow.threadId)
    }

    @Test fun aReplyWhoseParentIsOffThePageStillCarriesTheRootKey() {
        val orphan = message(uid = 3, messageId = "<r3@x>", inReplyTo = "<m1@x>", references = "<m1@x>")

        assertEquals("<m1@x>", orphan.toEntity("accA", "INBOX", 42L, preview = null).threadId)
    }

    @Test fun aMessageWithNoThreadingHeaderAtAllHasNoKey() {
        val bare = message(uid = 4, messageId = null, inReplyTo = null, references = null)

        assertNull(bare.toEntity("accA", "INBOX", 42L, preview = null).threadId)
    }

    @Test fun aMessageWithOnlyAnInReplyToIsKeyedByIt() {
        val reply = message(uid = 5, messageId = "<r5@x>", inReplyTo = "<m1@x>", references = null)

        assertEquals("<m1@x>", reply.toEntity("accA", "INBOX", 42L, preview = null).threadId)
    }
}
