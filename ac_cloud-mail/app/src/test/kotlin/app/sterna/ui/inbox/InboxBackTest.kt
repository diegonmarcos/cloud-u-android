package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list screen's Back rule (Codeberg #86, then #103). Back is global: the two failure modes are
 */
class InboxBackTest {

    @Test fun `search closes instead of leaving the app`() {
        assertEquals(
            InboxBackAction.CLOSE_SEARCH,
            inboxBackAction(selectionActive = false, detailOpen = false, searching = true, atInbox = true),
        )
    }

    @Test fun `search inside a folder closes the search first`() {
        assertEquals(
            InboxBackAction.CLOSE_SEARCH,
            inboxBackAction(selectionActive = false, detailOpen = false, searching = true, atInbox = false),
        )
    }

    @Test fun `once the search is closed a folder still returns to the inbox`() {
        assertEquals(
            InboxBackAction.SHOW_INBOX,
            inboxBackAction(selectionActive = false, detailOpen = false, searching = false, atInbox = false),
        )
    }

    @Test fun `a selection outranks the search`() {
        assertEquals(
            InboxBackAction.CLEAR_SELECTION,
            inboxBackAction(selectionActive = true, detailOpen = false, searching = true, atInbox = true),
        )
    }

    @Test fun `a selection outranks the folder`() {
        assertEquals(
            InboxBackAction.CLEAR_SELECTION,
            inboxBackAction(selectionActive = true, detailOpen = false, searching = false, atInbox = false),
        )
    }

    @Test fun `a plain inbox still leaves the app`() {
        // The list is the start destination: with no mode open, Back must NOT be swallowed,
        // or the user is trapped in the app.
        assertEquals(
            InboxBackAction.LEAVE_APP,
            inboxBackAction(selectionActive = false, detailOpen = false, searching = false, atInbox = true),
        )
    }

    // -- the reading pane beside the list (#103) -------------------------------------------------

    @Test fun `a message in the pane closes before the app is left`() {
        assertEquals(
            "with a message on the right, Back must empty the pane — not leave the app from a " +
                "message the person was reading",
            InboxBackAction.CLOSE_DETAIL,
            inboxBackAction(selectionActive = false, detailOpen = true, searching = false, atInbox = true),
        )
    }

    @Test fun `a message in the pane closes before the search`() {
        assertEquals(
            "the portrait order: the reader is stacked above the search, so a message opened from " +
                "the results goes first and the results are still there on the next press",
            InboxBackAction.CLOSE_DETAIL,
            inboxBackAction(selectionActive = false, detailOpen = true, searching = true, atInbox = true),
        )
    }

    @Test fun `a selection outranks the pane`() {
        assertEquals(
            "the selection bar covers the reader in portrait; it is the last thing turned on",
            InboxBackAction.CLEAR_SELECTION,
            inboxBackAction(selectionActive = true, detailOpen = true, searching = false, atInbox = true),
        )
    }

    @Test fun `the pane closes before a folder returns to the inbox`() {
        assertEquals(
            InboxBackAction.CLOSE_DETAIL,
            inboxBackAction(selectionActive = false, detailOpen = true, searching = false, atInbox = false),
        )
    }

    @Test fun `with the pane empty nothing changes`() {
        val before = mapOf(
            Triple(false, true, true) to InboxBackAction.CLOSE_SEARCH,
            Triple(false, true, false) to InboxBackAction.CLOSE_SEARCH,
            Triple(false, false, false) to InboxBackAction.SHOW_INBOX,
            Triple(true, true, true) to InboxBackAction.CLEAR_SELECTION,
            Triple(true, false, false) to InboxBackAction.CLEAR_SELECTION,
            Triple(false, false, true) to InboxBackAction.LEAVE_APP,
        )
        assertEquals(
            "under 600 dp there is no pane and detailOpen is always false: the six answers of #86 " +
                "must be exactly what they were",
            before,
            before.mapValues { (k, _) ->
                inboxBackAction(selectionActive = k.first, detailOpen = false, searching = k.second, atInbox = k.third)
            },
        )
    }

    @Test fun `exactly one action applies to any state`() {
        // The screen binds one handler per action; overlapping conditions would make Back
        // depend on composition order.
        val seen = mutableListOf<InboxBackAction>()
        listOf(true, false).forEach { selection ->
            listOf(true, false).forEach { detail ->
                listOf(true, false).forEach { searching ->
                    listOf(true, false).forEach { atInbox ->
                        seen += inboxBackAction(
                            selectionActive = selection,
                            detailOpen = detail,
                            searching = searching,
                            atInbox = atInbox,
                        )
                    }
                }
            }
        }
        assertEquals(16, seen.size)
        assertEquals(
            "every one of the five actions must be reachable — a value nothing returns is a " +
                "handler nothing ever enables",
            InboxBackAction.entries.toSet(),
            seen.toSet(),
        )
        assertEquals(5, InboxBackAction.entries.size)
    }
}
