package app.sterna.core.data.mail

import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.imap.ImapMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions of the IMAP list preview, EXECUTED (#187): whether to read opening lines at
 */
class ListPreviewDecisionTest {

    /**
     * THE WHOLE PRODUCT OF BOTH ENUMERATIONS, sixteen couples, each stated. Two claims live in
     */
    @Test fun `the list wants an opening line for these settings, and only these`() {
        val wanted = mutableListOf<Pair<PreviewLines, NotificationContent>>()
        PreviewLines.entries.forEach { lines ->
            NotificationContent.entries.forEach { content ->
                if (previewWantedInList(lines, content)) wanted += lines to content
            }
        }

        assertEquals(
            "the couples that must read a body, written out rather than derived: a NONE/NONE " +
                "account reads nothing, and every other combination reads",
            listOf(
                PreviewLines.NONE to NotificationContent.BODY_PREVIEW,
                PreviewLines.ONE to NotificationContent.BODY_PREVIEW,
                PreviewLines.ONE to NotificationContent.SENDER_AND_SUBJECT,
                PreviewLines.ONE to NotificationContent.SENDER_ONLY,
                PreviewLines.ONE to NotificationContent.NONE,
                PreviewLines.THREE to NotificationContent.BODY_PREVIEW,
                PreviewLines.THREE to NotificationContent.SENDER_AND_SUBJECT,
                PreviewLines.THREE to NotificationContent.SENDER_ONLY,
                PreviewLines.THREE to NotificationContent.NONE,
                PreviewLines.FIVE to NotificationContent.BODY_PREVIEW,
                PreviewLines.FIVE to NotificationContent.SENDER_AND_SUBJECT,
                PreviewLines.FIVE to NotificationContent.SENDER_ONLY,
                PreviewLines.FIVE to NotificationContent.NONE,
            ),
            wanted,
        )
    }

    /** And the ONE couple that reads nothing, stated on its own so the line cannot be lost in
     *  the list above: an account that shows no preview anywhere spends nothing on the radio. */
    @Test fun `an account that shows no preview anywhere reads no body`() {
        assertFalse(previewWantedInList(PreviewLines.NONE, NotificationContent.SENDER_AND_SUBJECT))
        assertFalse(previewWantedInList(PreviewLines.NONE, NotificationContent.SENDER_ONLY))
        assertFalse(previewWantedInList(PreviewLines.NONE, NotificationContent.NONE))
    }

    private fun message(uid: Long) = ImapMessage(
        uid = uid,
        subject = "Subject $uid",
        fromName = "Alex",
        fromEmail = "alex@example.org",
        to = emptyList(),
        dateMillis = 1_700_000_000_000L,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = false,
        messageId = "<$uid@example.org>",
        inReplyTo = null,
    )

    /**
     * THE END OF THE ROAD, and the line issue #187 was: the value read for a message has to reach
     */
    @Test fun `the opening line read for a message reaches the cache row`() {
        assertEquals(
            "Bonjour, l'été arrive",
            message(7L).toEntity("accA", "INBOX", 42L, preview = "Bonjour, l'été arrive").preview,
        )
    }

    /** And `null` stays `null`: the four read paths that cache no opening line say so, and the row
     *  they write must not invent one. */
    @Test fun `no opening line read means no opening line stored`() {
        assertNull(message(7L).toEntity("accA", "INBOX", 42L, preview = null).preview)
    }
}
