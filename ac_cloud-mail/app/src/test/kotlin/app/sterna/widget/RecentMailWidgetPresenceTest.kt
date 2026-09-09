package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The two widgets' presence gates, RUN side by side — the half of them that needs no Android.
 */
class RecentMailWidgetPresenceTest {

    /** Two objects, two flows. Sameness here is the defect itself, before any value is read. */
    @Test fun `the two widgets do not share one state`() {
        assertNotSame(
            "the latest-messages gate must be its own StateFlow. Shared, one widget's callbacks " +
                "answer for the other's.",
            UnreadWidgetPresence.live,
            RecentMailWidgetPresence.live,
        )
    }

    @Test fun `the latest-messages callbacks move its own answer, both ways`() {
        RecentMailWidgetPresence.set(true)
        assertEquals(true, RecentMailWidgetPresence.live.value)
        RecentMailWidgetPresence.set(false)
        assertEquals(false, RecentMailWidgetPresence.live.value)
    }

    /**
     * The one that matters: the last latest-messages cell going must leave the COUNTER's gate
     * exactly as it was. A counter cell is still on the home screen.
     */
    @Test fun `removing the last latest-messages cell leaves the counter's gate open`() {
        UnreadWidgetPresence.set(true)
        RecentMailWidgetPresence.set(true)

        RecentMailWidgetPresence.set(false)

        assertEquals(
            "the counter still has a cell placed. Its gate closing here drops the unread " +
                "subscription and the number stops following the mail, silently.",
            true,
            UnreadWidgetPresence.live.value,
        )
        assertEquals(false, RecentMailWidgetPresence.live.value)
        UnreadWidgetPresence.set(false)
    }

    /** And the other direction: the counter's last cell going says nothing about this widget. */
    @Test fun `removing the last counter cell leaves the latest-messages gate open`() {
        UnreadWidgetPresence.set(true)
        RecentMailWidgetPresence.set(true)

        UnreadWidgetPresence.set(false)

        assertEquals(true, RecentMailWidgetPresence.live.value)
        RecentMailWidgetPresence.set(false)
    }
}
