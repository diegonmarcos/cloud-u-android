package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.MailNotificationText.WidgetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one row of the latest-mail home-screen widget is allowed to say. A widget is drawn by
 */
class MailNotificationWidgetRowTest {

    private fun row(content: NotificationContent, appLockEnabled: Boolean = false) =
        MailNotificationText.widgetRow(content, appLockEnabled, SENDER, SUBJECT, GENERIC)

    // -- the four positions, pinned ---------------------------------------------------------------

    @Test fun `what a widget row may say, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to WidgetRow("Jordan Lee", "Blood test results", namesSender = true),
            NotificationContent.SENDER_AND_SUBJECT to
                WidgetRow("Jordan Lee", "Blood test results", namesSender = true),
            NotificationContent.SENDER_ONLY to WidgetRow("Jordan Lee", "New message", namesSender = true),
            NotificationContent.NONE to WidgetRow("New message", null, namesSender = false),
        )
        assertEquals(
            "a position was added to the setting without deciding what a widget row may say there",
            NotificationContent.entries.toSet(),
            expected.keys,
        )
        assertEquals(expected, NotificationContent.entries.associateWith { row(it) })
    }

    @Test fun `body preview shows the sender and the subject, and no body`() {
        val row = row(NotificationContent.BODY_PREVIEW)
        assertEquals("Jordan Lee", row.primary)
        assertEquals("Blood test results", row.secondary)
    }

    @Test fun `sender and subject shows both`() {
        val row = row(NotificationContent.SENDER_AND_SUBJECT)
        assertEquals("Jordan Lee", row.primary)
        assertEquals("Blood test results", row.secondary)
    }

    @Test fun `sender only keeps the name and puts the stand-in under it`() {
        val row = row(NotificationContent.SENDER_ONLY)
        assertEquals("Jordan Lee", row.primary)
        assertEquals("New message", row.secondary)
    }

    @Test fun `neither shows the stand-in alone, with no second line at all`() {
        val row = row(NotificationContent.NONE)
        assertEquals("New message", row.primary)
        assertNull(row.secondary)
    }

    /**
     * The two talkative positions render the SAME row, deliberately. The preview column is
     */
    @Test fun `body preview and sender and subject render the same row`() {
        assertEquals(row(NotificationContent.SENDER_AND_SUBJECT), row(NotificationContent.BODY_PREVIEW))
    }

    /**
     * A widget row never says more than the collapsed notification for the same mail: the pair
     * (primary, secondary) is the pair (title, text) of [MailNotificationText.resolve].
     */
    @Test fun `a widget row says exactly what the collapsed notification says`() {
        NotificationContent.entries.forEach { content ->
            val reveal = MailNotificationText.resolve(content, SENDER, SUBJECT, GENERIC, PREVIEW)
            val row = row(content)
            assertEquals("the primary line drifted from the notification title at $content", reveal.title, row.primary)
            assertEquals("the secondary line drifted from the collapsed line at $content", reveal.text, row.secondary)
        }
    }

    // -- the app lock ------------------------------------------------------------------------------

    /**
     * Read on the lock being ENABLED (AccountStore.appLockEnabled()), not on AppLock.locked, which
     */
    @Test fun `the app lock renders what NONE renders, at every position`() {
        NotificationContent.entries.forEach { content ->
            assertEquals(
                "the lock did not fold $content onto NONE",
                WidgetRow("New message", null, namesSender = false),
                row(content, appLockEnabled = true),
            )
        }
    }

    @Test fun `the app lock changes nothing about NONE itself`() {
        assertEquals(row(NotificationContent.NONE), row(NotificationContent.NONE, appLockEnabled = true))
    }

    // -- does the row NAME anybody ------------------------------------------------------------------

    /**
     * THE THIRD FIELD, AND WHY IT IS NOT A CONVENIENCE. A monogram badge is the first letter of a
     */
    @Test fun `whether a row names its sender, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to true,
            NotificationContent.SENDER_AND_SUBJECT to true,
            NotificationContent.SENDER_ONLY to true,
            NotificationContent.NONE to false,
        )
        assertEquals(
            "a position was added to the setting without deciding whether a widget row NAMES its " +
                "sender there. The answer governs the monogram badge, which is a letter of " +
                "somebody's name — defaulting it to true puts an initial on a home screen that " +
                "was asked to identify nobody.",
            NotificationContent.entries.toSet(),
            expected.keys,
        )
        assertEquals(expected, NotificationContent.entries.associateWith { row(it).namesSender })
    }

    /**
     * AND THE LOCK TAKES IT AWAY FROM THE MOST TALKATIVE POSITION. SECURITY.md promises a locked
     */
    @Test fun `under the lock the loudest position names nobody`() {
        assertEquals(
            "with the app lock enabled, BODY_PREVIEW must report that the row names NOBODY — the " +
                "badge is drawn from that answer, and an initial on a locked home screen is a " +
                "letter of the sender's name sitting where SECURITY.md promises no name at all.",
            false,
            row(NotificationContent.BODY_PREVIEW, appLockEnabled = true).namesSender,
        )
    }

    @Test fun `the lock takes the name away at every position`() {
        assertEquals(
            "the lock folds every position onto NONE, and NONE names nobody.",
            NotificationContent.entries.associateWith { false },
            NotificationContent.entries.associateWith { row(it, appLockEnabled = true).namesSender },
        )
    }

    // -- nothing identifying escapes a position that hides it --------------------------------------

    @Test fun `sender only lets the subject through neither line`() {
        val row = row(NotificationContent.SENDER_ONLY)
        assertTrue("the subject reached the primary line: ${row.primary}", "Blood test results" !in row.primary)
        assertTrue(
            "the subject reached the secondary line: ${row.secondary}",
            "Blood test results" !in (row.secondary ?: ""),
        )
    }

    @Test fun `neither lets the sender or the subject through either line`() {
        val row = row(NotificationContent.NONE)
        assertTrue("the sender reached the primary line: ${row.primary}", "Jordan Lee" !in row.primary)
        assertTrue("the subject reached the primary line: ${row.primary}", "Blood test results" !in row.primary)
        assertTrue("the sender reached the secondary line: ${row.secondary}", "Jordan Lee" !in (row.secondary ?: ""))
        assertTrue(
            "the subject reached the secondary line: ${row.secondary}",
            "Blood test results" !in (row.secondary ?: ""),
        )
    }

    @Test fun `under the lock, neither the sender nor the subject reaches either line, at any position`() {
        NotificationContent.entries.forEach { content ->
            val row = row(content, appLockEnabled = true)
            assertTrue("the sender survived the lock at $content: ${row.primary}", "Jordan Lee" !in row.primary)
            assertTrue(
                "the subject survived the lock at $content: ${row.primary}",
                "Blood test results" !in row.primary,
            )
            val secondary = row.secondary ?: ""
            assertTrue("the sender survived the lock at $content: $secondary", "Jordan Lee" !in secondary)
            assertTrue("the subject survived the lock at $content: $secondary", "Blood test results" !in secondary)
        }
    }

    // -- the signature itself, because prose was guarding it --------------------------------------

    /**
     * THE ABSENCE OF A `preview` PARAMETER IS THE GUARD, and until this test it was written down
     */
    @Test fun `the widget rule takes five arguments and no preview, and none of them has a default`() {
        val overloads = MailNotificationText::class.java.methods
            .filter { it.name.startsWith("widgetRow") }
            .map { m -> m.name + m.parameterTypes.joinToString(", ", "(", ")") { it.simpleName } }
            .sorted()
        assertEquals(
            "widgetRow must take exactly (content, appLockEnabled, sender, subject, generic) and " +
                "nothing else. A sixth parameter — a preview above all, but a body under any name — " +
                "is a body reaching a home screen, which no position of this setting ever asked " +
                "for; and a default value on any of the five compiles to a widgetRow$DOLLAR" +
                "default overload, which is the app lock failing open for a caller who omits it.",
            listOf("widgetRow(NotificationContent, boolean, String, String, String)"),
            overloads,
        )
    }

    private companion object {
        const val DOLLAR = "$"
        const val SENDER = "Jordan Lee"
        const val SUBJECT = "Blood test results"
        const val GENERIC = "New message"
        const val PREVIEW = "Your results are in, please call the surgery"
    }
}
