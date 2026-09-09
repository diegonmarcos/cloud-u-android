package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a mail notification is allowed to reveal at each position of the notification-content
 */
class MailNotificationTextTest {

    private fun resolve(content: NotificationContent, preview: String? = null) =
        MailNotificationText.resolve(content, SENDER, SUBJECT, GENERIC, preview)

    @Test fun `sender and subject shows both, collapsed and expanded`() {
        val reveal = resolve(NotificationContent.SENDER_AND_SUBJECT)
        assertEquals(SENDER, reveal.title)
        assertEquals(SUBJECT, reveal.text)
        assertEquals(SUBJECT, reveal.bigText)
    }

    @Test fun `sender only keeps the name and hides the subject`() {
        val reveal = resolve(NotificationContent.SENDER_ONLY)
        assertEquals(SENDER, reveal.title)
        assertEquals(GENERIC, reveal.text)
        assertNull(reveal.bigText)
    }

    @Test fun `neither reveals nothing identifying`() {
        val reveal = resolve(NotificationContent.NONE)
        assertEquals(GENERIC, reveal.title)
        assertNull(reveal.text)
        assertNull(reveal.bigText)
    }

    @Test fun `the subject never leaks through a hidden position`() {
        listOf(NotificationContent.SENDER_ONLY, NotificationContent.NONE).forEach { content ->
            val reveal = resolve(content)
            assertNull("bigText leaks the subject at $content", reveal.bigText)
            assertEquals("title leaks the subject at $content", false, reveal.title == SUBJECT)
            assertEquals("text leaks the subject at $content", false, reveal.text == SUBJECT)
        }
    }

    @Test fun `the sender never leaks when the setting hides it`() {
        val reveal = resolve(NotificationContent.NONE)
        assertEquals(false, reveal.title == SENDER)
        assertEquals(false, reveal.text == SENDER)
    }

    // -- the talkative position ---------------------------------------------------------------

    @Test fun `body preview shows the subject collapsed and the preview only when expanded`() {
        val reveal = resolve(NotificationContent.BODY_PREVIEW, PREVIEW)
        assertEquals(SENDER, reveal.title)
        assertEquals("the collapsed line stays the subject", SUBJECT, reveal.text)
        assertEquals("$SUBJECT\n$PREVIEW", reveal.bigText)
    }

    /**
     * The rule the group summary depends on. [Notifications.updateGroupSummary] rebuilds the
     */
    @Test fun `the preview never reaches the collapsed line`() {
        NotificationContent.entries.forEach { content ->
            val reveal = resolve(content, PREVIEW)
            assertEquals("the preview reached the collapsed line at $content", false, reveal.text == PREVIEW)
            assertEquals(
                "the collapsed line carries the preview at $content: ${reveal.text}",
                false,
                reveal.text?.contains(PREVIEW) ?: false,
            )
            assertEquals("the preview became the title at $content", false, reveal.title == PREVIEW)
        }
    }

    @Test fun `a missing preview leaves the subject alone, with no separator`() {
        listOf(null, "", "   ", "\n\n").forEach { empty ->
            val reveal = resolve(NotificationContent.BODY_PREVIEW, empty)
            assertEquals("preview=${empty?.let { "'$it'" }}", SUBJECT, reveal.bigText)
        }
    }

    @Test fun `the preview is trimmed of the blank the body starts with`() {
        assertEquals("$SUBJECT\n$PREVIEW", resolve(NotificationContent.BODY_PREVIEW, "\n  $PREVIEW  \n").bigText)
    }

    // -- the shape of what a producer is allowed to hand over ------------------------------------

    /**
     * A body arrives with the blank of the format it was written in: quoted-printable soft breaks,
     */
    @Test fun `the preview is flattened to a single line`() {
        val raw = "Dear Alex,\n\nthe results\tare in.\r\n  Please call    the surgery."
        assertEquals(
            "$SUBJECT\nDear Alex, the results are in. Please call the surgery.",
            resolve(NotificationContent.BODY_PREVIEW, raw).bigText,
        )
    }

    @Test fun `the separator is the only newline left`() {
        val reveal = resolve(NotificationContent.BODY_PREVIEW, "one\ntwo\nthree\nfour")
        assertEquals(1, reveal.bigText?.count { it == '\n' })
        assertEquals("$SUBJECT\none two three four", reveal.bigText)
    }

    /**
     * The cap, and where it is: in here, because the preview will come from two protocols and this
     */
    @Test fun `the preview is capped at 256 characters, with nothing added`() {
        val body = "a".repeat(4_000)
        val reveal = resolve(NotificationContent.BODY_PREVIEW, body)
        assertEquals("$SUBJECT\n" + "a".repeat(256), reveal.bigText)
        assertEquals("no ellipsis, no marker: nothing is added", false, reveal.bigText!!.endsWith("…"))
    }

    @Test fun `a long preview is capped after it is flattened, not before`() {
        // 4 000 characters of blank in front of 300 words: capping first would show a cap's worth of
        // nothing at all. The order is flatten, trim, then cap.
        val reveal = resolve(NotificationContent.BODY_PREVIEW, "\n".repeat(4_000) + "b".repeat(300))
        assertEquals("$SUBJECT\n" + "b".repeat(256), reveal.bigText)
    }

    @Test fun `a preview just under the cap is left exactly as it is`() {
        val body = "c".repeat(255)
        assertEquals("$SUBJECT\n$body", resolve(NotificationContent.BODY_PREVIEW, body).bigText)
    }

    @Test fun `no preview ever exceeds the cap, whatever blank it carries`() {
        listOf("d".repeat(1_000), "e f ".repeat(1_000), "\t\ng ".repeat(1_000)).forEach { body ->
            val bigText = resolve(NotificationContent.BODY_PREVIEW, body).bigText!!
            val preview = bigText.removePrefix("$SUBJECT\n")
            assertEquals("the preview outgrew the cap: ${preview.length}", true, preview.length <= 256)
        }
    }

    /**
     * The other three positions were settled with a reporter (#25, #84) and are what a reader who
     */
    @Test fun `handing a preview to the three quieter positions changes nothing`() {
        listOf(
            NotificationContent.SENDER_AND_SUBJECT,
            NotificationContent.SENDER_ONLY,
            NotificationContent.NONE,
        ).forEach { content ->
            assertEquals("$content changed when given a preview", resolve(content), resolve(content, PREVIEW))
        }
    }

    @Test fun `the body never leaks through a position that hides the subject`() {
        listOf(NotificationContent.SENDER_ONLY, NotificationContent.NONE).forEach { content ->
            val reveal = resolve(content, PREVIEW)
            assertNull("bigText leaks the body at $content", reveal.bigText)
        }
    }

    // -- the send-failure banner ------------------------------------------------------------------

    /**
     * A send that failed is announced by a banner of its own, and it used to name the message
     */
    @Test fun `what a failed send may name, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to SUBJECT,
            NotificationContent.SENDER_AND_SUBJECT to SUBJECT,
            NotificationContent.SENDER_ONLY to null,
            NotificationContent.NONE to null,
        )
        assertEquals(
            "a position was added to the setting without deciding what a failed send may name there",
            NotificationContent.entries.toSet(),
            expected.keys,
        )
        assertEquals(
            expected,
            NotificationContent.entries.associateWith {
                MailNotificationText.sendFailureLine(it, SUBJECT, NO_SUBJECT)
            },
        )
    }

    @Test fun `a failed send names the message where subjects already show`() {
        assertEquals(SUBJECT, MailNotificationText.sendFailureLine(NotificationContent.BODY_PREVIEW, SUBJECT, NO_SUBJECT))
        assertEquals(
            SUBJECT,
            MailNotificationText.sendFailureLine(NotificationContent.SENDER_AND_SUBJECT, SUBJECT, NO_SUBJECT),
        )
    }

    @Test fun `a failed send names nothing where the subject is hidden`() {
        assertNull(
            "the banner named the message at SENDER_ONLY",
            MailNotificationText.sendFailureLine(NotificationContent.SENDER_ONLY, SUBJECT, NO_SUBJECT),
        )
        assertNull(
            "the banner named the message at NONE",
            MailNotificationText.sendFailureLine(NotificationContent.NONE, SUBJECT, NO_SUBJECT),
        )
    }

    /** The stand-in is passed IN — this function resolves no resources — and only where a line shows. */
    @Test fun `a blank subject falls back to the stand-in, and only where a line is shown`() {
        assertEquals(NO_SUBJECT, MailNotificationText.sendFailureLine(NotificationContent.BODY_PREVIEW, "", NO_SUBJECT))
        assertEquals(
            NO_SUBJECT,
            MailNotificationText.sendFailureLine(NotificationContent.SENDER_AND_SUBJECT, "   ", NO_SUBJECT),
        )
        assertNull(MailNotificationText.sendFailureLine(NotificationContent.SENDER_ONLY, "", NO_SUBJECT))
        assertNull(MailNotificationText.sendFailureLine(NotificationContent.NONE, "   ", NO_SUBJECT))
    }

    private companion object {
        const val NO_SUBJECT = "(no subject)"
        const val SENDER = "Jordan Lee"
        const val SUBJECT = "Blood test results"
        const val GENERIC = "New message"
        const val PREVIEW = "Your results are in, please call the surgery"
    }
}
