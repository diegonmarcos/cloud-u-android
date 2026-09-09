package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate's answer, RUN — the half of [UnreadWidgetPresence] that needs no Android.
 */
class UnreadWidgetPresenceTest {

    /** No cell placed — the answer for the large majority of installs, and the expensive one to
     *  get wrong. */
    @Test fun `no cell placed means no widget`() {
        assertEquals(
            "an empty id array is the widget manager saying nobody placed this widget. Answering " +
                "true here subscribes two global Room aggregates for the life of every process on " +
                "earth, which is exactly what the gate refuses.",
            false,
            anyWidgetPlaced(intArrayOf()),
        )
    }

    /** One cell is enough: the gate asks whether ANY exists, never how many. */
    @Test fun `one cell placed means a widget`() {
        assertEquals(
            "a single id is a placed cell. Answering false here leaves the gate shut for the life " +
                "of the process: the count is never collected and the cell sits on a stale number " +
                "while the drawer beside it decrements.",
            true,
            anyWidgetPlaced(intArrayOf(7)),
        )
    }

    @Test fun `several cells are still just a widget`() {
        assertEquals(true, anyWidgetPlaced(intArrayOf(7, 9, 11)))
    }

    /**
     * The callbacks' end. `onEnabled` fires for the FIRST cell and `onDisabled` for the LAST one
     */
    @Test fun `the system's callbacks move the live answer, both ways`() {
        UnreadWidgetPresence.set(true)
        assertEquals(
            "onEnabled must open the gate on the running process: the cell was placed long after " +
                "the seed was read, and nothing else will tell the app about it.",
            true,
            UnreadWidgetPresence.live.value,
        )

        UnreadWidgetPresence.set(false)
        assertEquals(
            "onDisabled must close it again. Left open, the app goes on counting unread mail for " +
                "a widget the user removed, for as long as the process lives.",
            false,
            UnreadWidgetPresence.live.value,
        )
    }
}
