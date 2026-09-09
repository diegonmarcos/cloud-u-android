package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.imap.ImapAddress
import app.sterna.core.imap.ImapMessage
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The end of the IMAP road: the envelope's copies have to land in the CACHE ROW.
 */
class ImapCopiesRowTest {

    private val team = ImapAddress(name = "Team", email = "team@masto.top")
    private val copy = ImapAddress(name = "Copy Cat", email = "copy@masto.top")
    private val billing = ImapAddress(name = "Billing", email = "billing@masto.top")
    private val hidden = ImapAddress(name = "Hidden One", email = "hidden@masto.top")

    private fun message(
        cc: List<ImapAddress> = listOf(copy),
        bcc: List<ImapAddress> = listOf(hidden),
    ) = ImapMessage(
        uid = 7L,
        subject = "Six o'clock",
        fromName = "Alex Rivera",
        fromEmail = "alex.rivera@masto.top",
        to = listOf(team),
        cc = cc,
        bcc = bcc,
        dateMillis = 1_780_000_000_000L,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = false,
        messageId = "<7@masto.top>",
        inReplyTo = null,
    )

    @Test
    fun `the cc addresses are written to the row`() {
        val entity = message().toEntity("accA", "INBOX", 42L, preview = null)

        assertNotNull("the IMAP row must carry the Cc, not only the To", entity.ccJson)
        assertEquals(
            listOf(EmailAddress(name = "Copy Cat", email = "copy@masto.top")),
            EmailRecipients.decode(entity.ccJson),
        )
    }

    @Test
    fun `the bcc addresses are written to the row`() {
        val entity = message().toEntity("accA", "INBOX", 42L, preview = null)

        assertNotNull("the IMAP row must carry the Bcc in its own column", entity.bccJson)
        assertEquals(
            listOf(EmailAddress(name = "Hidden One", email = "hidden@masto.top")),
            EmailRecipients.decode(entity.bccJson),
        )
    }

    /**
     * Four columns, four lists. Storing the blind copies in the visible column is not a display
     * bug: it is the app telling the user, and a reply-all, about people the sender hid.
     */
    @Test
    fun `each list goes to its own column, and the bcc never lands in the cc`() {
        val entity = message().toEntity("accA", "INBOX", 42L, preview = null)

        assertEquals(
            listOf(EmailAddress(name = "Team", email = "team@masto.top")),
            EmailRecipients.decode(entity.recipientsJson),
        )
        assertEquals(
            listOf(EmailAddress(name = "Copy Cat", email = "copy@masto.top")),
            EmailRecipients.decode(entity.ccJson),
        )
        assertEquals(
            listOf(EmailAddress(name = "Hidden One", email = "hidden@masto.top")),
            EmailRecipients.decode(entity.bccJson),
        )
        assertEquals(
            "a blind copy in the Cc column stops being blind",
            emptyList<String>(),
            EmailRecipients.decode(entity.ccJson).map { it.email }
                .filter { it == "hidden@masto.top" },
        )
    }

    /** What the reader actually holds: the row rendered back into an `Email`. */
    @Test
    fun `a row read back offline still has the people in copy`() {
        val email = message().toEntity("accA", "INBOX", 42L, preview = null).toEmail()

        assertEquals(listOf("copy@masto.top"), email.cc.map { it.email })
        assertEquals(listOf("hidden@masto.top"), email.bcc.map { it.email })
        assertEquals(listOf("team@masto.top"), email.to.map { it.email })
        assertEquals("alex.rivera@masto.top", email.from.single().email)
    }

    @Test
    fun `several cc addresses keep their order in the row`() {
        val email = message(cc = listOf(copy, billing)).toEntity("accA", "INBOX", 42L, preview = null).toEmail()

        assertEquals(listOf("copy@masto.top", "billing@masto.top"), email.cc.map { it.email })
        assertEquals(listOf("Copy Cat", "Billing"), email.cc.map { it.name })
    }

    /** No copies: nothing stored in those columns, and the recipients beside them are untouched. */
    @Test
    fun `a message without copies stores nothing in those columns`() {
        val entity = message(cc = emptyList(), bcc = emptyList()).toEntity("accA", "INBOX", 42L, preview = null)

        assertNull(entity.ccJson)
        assertNull(entity.bccJson)
        assertNotNull(entity.recipientsJson)
        assertEquals(listOf("team@masto.top"), entity.toEmail().to.map { it.email })
    }

    /**
     * A Cc with no Bcc — what a received message really looks like, since a server does not tell a
     * recipient who was blind-copied. The empty column must not swallow the full one.
     */
    @Test
    fun `a cc without a bcc is stored on its own`() {
        val entity = message(bcc = emptyList()).toEntity("accA", "INBOX", 42L, preview = null)

        assertEquals(listOf("copy@masto.top"), EmailRecipients.decode(entity.ccJson).map { it.email })
        assertNull(entity.bccJson)
    }

    /**
     * THE ONE-SIDED SHAPE THAT LEAKS: nobody in Cc, somebody in Bcc — a message this account
     */
    @Test
    fun `a bcc without a cc stays out of the visible column`() {
        val entity = message(cc = emptyList()).toEntity("accA", "INBOX", 42L, preview = null)

        assertNull("nobody was in visible copy; the Cc column must stay NULL", entity.ccJson)
        assertEquals(
            listOf("hidden@masto.top"),
            EmailRecipients.decode(entity.bccJson).map { it.email },
        )
        assertEquals(emptyList<String>(), entity.toEmail().cc.map { it.email })
        assertEquals(listOf("hidden@masto.top"), entity.toEmail().bcc.map { it.email })
    }
}
