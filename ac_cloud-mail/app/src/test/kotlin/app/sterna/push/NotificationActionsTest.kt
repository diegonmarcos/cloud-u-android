package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which buttons a new-mail notification may carry (Codeberg #57). The rule is not "which setting
 */
class NotificationActionsTest {

    private val all = listOf(
        MailNotificationAction.REPLY,
        MailNotificationAction.MARK_READ,
        MailNotificationAction.DELETE,
    )

    /** The shipped decision, fed the way [Notifications.notifyNewMail] feeds it. */
    private fun actions(content: NotificationContent, sender: String?, subject: String?) =
        Notifications.actionsFor(content, hasSender = sender != null, hasSubject = subject != null)

    /** What the shipped text rule would put on screen for the same mail, stand-ins included. */
    private fun reveal(content: NotificationContent, sender: String?, subject: String?) =
        MailNotificationText.resolve(content, sender ?: GENERIC, subject ?: NO_SUBJECT, GENERIC)

    // -- the talkative position: same buttons as sender + subject, and for the same reason ------

    /**
     * BODY_PREVIEW reveals strictly more than SENDER_AND_SUBJECT and is read the same way here:
     */
    @Test fun `body preview keeps the same three actions, in the same order`() {
        assertEquals(all, actions(NotificationContent.BODY_PREVIEW, SENDER, SUBJECT))
        assertEquals(all, actions(NotificationContent.BODY_PREVIEW, sender = null, subject = SUBJECT))
        assertEquals(all, actions(NotificationContent.BODY_PREVIEW, SENDER, subject = null))
    }

    /**
     * And the decision this position had to settle: a preview of the body does NOT name the message.
     */
    @Test fun `a preview does not name the message`() {
        assertEquals(
            emptyList<MailNotificationAction>(),
            actions(NotificationContent.BODY_PREVIEW, sender = null, subject = null),
        )
        // Even though the notification is far from empty: the expanded line is showing the body.
        val onScreen = MailNotificationText.resolve(
            NotificationContent.BODY_PREVIEW, GENERIC, NO_SUBJECT, GENERIC, PREVIEW,
        )
        assertEquals("$NO_SUBJECT\n$PREVIEW", onScreen.bigText)
    }

    @Test fun `sender and subject keeps reply, mark as read and delete, in that order`() {
        assertEquals(all, actions(NotificationContent.SENDER_AND_SUBJECT, SENDER, SUBJECT))
    }

    @Test fun `sender only keeps the same three actions, in the same order`() {
        assertEquals(all, actions(NotificationContent.SENDER_ONLY, SENDER, SUBJECT))
    }

    @Test fun `neither offers no action at all`() {
        assertEquals(
            emptyList<MailNotificationAction>(),
            actions(NotificationContent.NONE, SENDER, SUBJECT),
        )
    }

    @Test fun `nothing destructive is offered when the message cannot be identified`() {
        val offered = actions(NotificationContent.NONE, SENDER, SUBJECT)
        assertFalse("Delete acts blind at NONE", MailNotificationAction.DELETE in offered)
        assertFalse("Mark as read acts blind at NONE", MailNotificationAction.MARK_READ in offered)
        assertFalse("Reply acts blind at NONE", MailNotificationAction.REPLY in offered)
    }

    /**
     * The narrow case the setting alone cannot see: daemon mail or a malformed header leaves no
     */
    @Test fun `a message with no sender gets no actions at sender only`() {
        val reveal = reveal(NotificationContent.SENDER_ONLY, sender = null, subject = SUBJECT)
        assertEquals("the notification names nothing", GENERIC, reveal.title)
        assertEquals("the notification names nothing", GENERIC, reveal.text)
        assertEquals(
            emptyList<MailNotificationAction>(),
            actions(NotificationContent.SENDER_ONLY, sender = null, subject = SUBJECT),
        )
    }

    @Test fun `a message with no sender keeps its actions at sender and subject`() {
        // There the subject IS displayed, so the notification still names the message.
        assertEquals(all, actions(NotificationContent.SENDER_AND_SUBJECT, sender = null, subject = SUBJECT))
    }

    @Test fun `a message with neither sender nor subject gets no actions anywhere`() {
        NotificationContent.entries.forEach { content ->
            assertTrue(
                "$content offers actions over a mail that carries nothing",
                actions(content, sender = null, subject = null).isEmpty(),
            )
        }
    }

    @Test fun `a message with no subject keeps its actions where the sender shows`() {
        assertEquals(all, actions(NotificationContent.SENDER_AND_SUBJECT, SENDER, subject = null))
        assertEquals(all, actions(NotificationContent.SENDER_ONLY, SENDER, subject = null))
    }

    /**
     * The two rules crossed, over every position and every combination of what the mail carries:
     */
    @Test fun `actions are offered exactly when the notification names the message`() {
        val standIns = setOf(GENERIC, NO_SUBJECT)
        NotificationContent.entries.forEach { content ->
            listOf(SENDER, null).forEach { sender ->
                listOf(SUBJECT, null).forEach { subject ->
                    val reveal = reveal(content, sender, subject)
                    val onScreen = listOfNotNull(reveal.title, reveal.text, reveal.bigText)
                    val names = onScreen.any { it !in standIns }
                    val offered = actions(content, sender, subject)
                    val case = "$content sender=$sender subject=$subject shows $onScreen"
                    if (names) {
                        assertEquals("$case but lost its actions", all, offered)
                    } else {
                        assertTrue("$case names nothing yet offers $offered", offered.isEmpty())
                    }
                }
            }
        }
    }

    @Test fun `no position offers the same action twice`() {
        NotificationContent.entries.forEach { content ->
            val offered = actions(content, SENDER, SUBJECT)
            assertEquals("duplicate action at $content", offered.distinct(), offered)
        }
    }

    private companion object {
        const val SENDER = "Jordan Lee"
        const val SUBJECT = "Blood test results"
        const val GENERIC = "New message"
        const val NO_SUBJECT = "(no subject)"
        const val PREVIEW = "see you at 6"
    }
}
