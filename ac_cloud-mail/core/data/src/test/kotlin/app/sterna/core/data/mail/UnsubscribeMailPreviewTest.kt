package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The invariant behind the confirmation dialog: **what is named is what is sent.**
 */
class UnsubscribeMailPreviewTest {

    /**
     * The refuter's fixture, parsed from the raw header rather than hand-built: the whole path,
     * from the bytes a sender can put on the wire to the two strings that leave the device.
     */
    @Test fun `a resignation hidden in a List-Unsubscribe header is shown word for word`() {
        val options = UnsubscribeHeader.parse(
            "<mailto:hr@exemple.org?subject=Je%20d%C3%A9missionne&body=Effectif%20ce%20jour.>",
            null,
        )!!

        // MAIL, not OPEN_PAGE: this is the gesture two taps reach by default.
        assertEquals(UnsubscribeAction.MAIL, options.preferredAction())

        val preview = unsubscribePreview(options.mailto!!)
        assertEquals("Je démissionne", preview.subject)
        assertEquals("Effectif ce jour.", preview.body)

        val mail = unsubscribeMail(options.mailto!!, "Alex Doe", "alex@masto.top")
        assertEquals(listOf("hr@exemple.org"), mail.to)
        assertEquals(preview.subject, mail.subject)
        assertEquals(preview.body, mail.body)
    }

    /**
     * The two texts cannot come apart, over every shape a `mailto:` can take — including the two
     * that have no text at all, where the fallbacks ARE what is sent and so are what is shown.
     */
    @Test fun `the preview is, field for field, what the mail carries`() {
        val fixtures = listOf(
            MailtoUnsubscribe("leave@list.example.com"),
            MailtoUnsubscribe("leave@list.example.com", subject = "unsubscribe abc"),
            MailtoUnsubscribe("leave@list.example.com", body = "token=xyz"),
            MailtoUnsubscribe("leave@list.example.com", subject = "I resign", body = "Effective today."),
            MailtoUnsubscribe("hr@exemple.org", subject = "Je démissionne", body = "Effectif ce jour."),
        )

        fixtures.forEach { mailto ->
            val preview = unsubscribePreview(mailto)
            val mail = unsubscribeMail(mailto, "Alex Doe", "alex@masto.top")

            assertEquals("subject shown vs sent, for $mailto", mail.subject, preview.subject)
            assertEquals("body shown vs sent, for $mailto", mail.body, preview.body)
        }
    }

    /**
     * The empty case, pinned on its own: a bare `mailto:` sends the fixed English subject and an
     */
    @Test fun `a bare mailto previews the fixed subject and an empty body`() {
        val preview = unsubscribePreview(MailtoUnsubscribe("leave@list.example.com"))

        assertEquals("Unsubscribe", preview.subject)
        assertEquals("", preview.body)
        assertEquals(UNSUBSCRIBE_SUBJECT, preview.subject)
    }

    /** The sender's subject wins over the default — the case the default would hide. */
    @Test fun `the sender's own subject and body are the preview when the uri carries them`() {
        val preview = unsubscribePreview(
            MailtoUnsubscribe("leave@list.example.com", subject = "unsubscribe abc", body = "token=xyz"),
        )

        assertEquals("unsubscribe abc", preview.subject)
        assertEquals("token=xyz", preview.body)
    }
}
