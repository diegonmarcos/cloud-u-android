package app.sterna.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PaneConversion], run on the stacks `MainNavHost` can hand it after a recreation — the routes
 */
class PaneConversionTest {

    // -- portrait -> wide: the restored full-screen reader moves into the pane --------------------

    @Test fun `a reader restored directly over the list converts`() {
        assertTrue(
            "rotating a phone that was reading a message must put that message in the pane: left " +
                "unconverted the reader stays full screen over a list that is now two panes wide, " +
                "and the split only appears once the person presses Back",
            PaneConversion.readerToPane(twoPanes = true, stack = listOf(GRAPH, INBOX, MESSAGE)),
        )
    }

    @Test fun `a filled-in url converts as its declared route does`() {
        assertTrue(
            "the restored stack carries the route with its arguments filled in; the verdict is " +
                "taken on each route's name, before the first '/' or '?', or no real rotation " +
                "ever converts",
            PaneConversion.readerToPane(
                twoPanes = true,
                stack = listOf(GRAPH, INBOX, "message/m-42?accountId=acc-1&index=3&src=list"),
            ),
        )
    }

    @Test fun `a reader opened from Search does not convert`() {
        assertFalse(
            "the reader opened from Search stays full screen at every width: the pane belongs to " +
                "the list, and converting would pop the reader onto search results the person " +
                "would then have to leave twice",
            PaneConversion.readerToPane(twoPanes = true, stack = listOf(GRAPH, INBOX, SEARCH, MESSAGE)),
        )
    }

    @Test fun `a reader above the composer does not convert`() {
        assertFalse(
            "converting here pops the reader onto a LIVE DRAFT the person never asked to come " +
                "back to, and parks the message in a pane hidden under that draft (#112)",
            PaneConversion.readerToPane(twoPanes = true, stack = listOf(GRAPH, INBOX, COMPOSE, MESSAGE)),
        )
    }

    @Test fun `the list alone converts nothing`() {
        assertFalse(
            "no reader is open: a conversion here would pop the list itself and empty the stack",
            PaneConversion.readerToPane(twoPanes = true, stack = listOf(GRAPH, INBOX)),
        )
    }

    @Test fun `a reader above another reader does not convert`() {
        assertFalse(
            "only the reader directly over the list converts: popping the top one would leave the " +
                "one underneath full screen AND put a third message in the pane behind it",
            PaneConversion.readerToPane(twoPanes = true, stack = listOf(GRAPH, INBOX, MESSAGE, MESSAGE)),
        )
    }

    @Test fun `an empty stack converts nothing`() {
        assertFalse(
            "the composition before the NavHost has composed its start destination has nothing to " +
                "pop and no reader to move",
            PaneConversion.readerToPane(twoPanes = true, stack = emptyList()),
        )
    }

    @Test fun `one pane never converts a reader`() {
        assertFalse(
            "one pane: popping the restored reader would leave a message the narrow list cannot " +
                "show — the message the person was reading, gone on a rotation",
            PaneConversion.readerToPane(twoPanes = false, stack = listOf(GRAPH, INBOX, MESSAGE)),
        )
    }

    // -- wide -> narrow: the anchor goes back to being a route -----------------------------------

    @Test fun `an anchor on a narrow window becomes a route`() {
        assertTrue(
            "rotating a tablet that was reading in the pane must reopen that message full screen: " +
                "left as an anchor it is shown by nothing at all, and the message disappears",
            PaneConversion.paneToReader(twoPanes = false, hasAnchor = true),
        )
    }

    @Test fun `an anchor on a wide window stays in the pane`() {
        assertFalse(
            "two panes: the anchor IS the reader, navigating would stack a full-screen copy over it",
            PaneConversion.paneToReader(twoPanes = true, hasAnchor = true),
        )
    }

    @Test fun `an empty pane on a narrow window navigates nowhere`() {
        assertFalse(
            "nothing was open: a navigation here opens a reader on no message at all",
            PaneConversion.paneToReader(twoPanes = false, hasAnchor = false),
        )
    }

    private companion object {
        /** The NavGraph's own entry, first in `currentBackStack`; the graph has no route, so null. */
        val GRAPH: String? = null

        // Copied verbatim from SternaApp.kt's NavHost — the `composable(route = …)` declarations.
        const val INBOX = "inbox"
        const val MESSAGE = "message/{emailId}?accountId={accountId}&index={index}&src={src}&thread={thread}"
        const val COMPOSE = "compose?replyTo={replyTo}&mode={mode}&accountId={accountId}&restore={restore}" +
            "&to={to}&cc={cc}&bcc={bcc}&subject={subject}&body={body}&draftId={draftId}&outboxId={outboxId}"
        const val SEARCH = "search?q={q}&from={from}"
    }
}
