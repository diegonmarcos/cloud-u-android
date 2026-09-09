package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PaneOrders.emailOpen], run on the stacks `MainNavHost` can hand it — the routes are the REAL
 */
class PaneOrdersTest {

    @Test fun `a cold start with an empty stack shows in the pane`() {
        assertEquals(
            "an empty stack is the composition that receives a cold-start tap, before the NavHost " +
                "composed its start destination — which is the list; the anchor is held until it exists",
            EmailOpenOrder.ShowInPane,
            PaneOrders.emailOpen(twoPanes = true, stack = emptyList()),
        )
    }

    @Test fun `the graph entry alone shows in the pane`() {
        assertEquals(
            EmailOpenOrder.ShowInPane,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH)),
        )
    }

    @Test fun `the list on screen shows in the pane`() {
        assertEquals(
            "the list is on screen and the pane is beside it: this is the one case the pane is for",
            EmailOpenOrder.ShowInPane,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH, INBOX)),
        )
    }

    @Test fun `a composer on top navigates`() {
        assertEquals(
            "⛔ the message must not be parked under a live draft: posted in the pane it would sit " +
                "under the composer, invisible, and wait for the person on a list she never asked " +
                "to open it on (#112). The reader stacks over the composer instead.",
            EmailOpenOrder.Navigate,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH, INBOX, COMPOSE)),
        )
    }

    @Test fun `a search on top navigates`() {
        assertEquals(
            EmailOpenOrder.Navigate,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH, INBOX, SEARCH)),
        )
    }

    @Test fun `a full-screen reader on top navigates`() {
        assertEquals(
            EmailOpenOrder.Navigate,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH, INBOX, MESSAGE)),
        )
    }

    @Test fun `the settings on top navigate`() {
        assertEquals(
            EmailOpenOrder.Navigate,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH, INBOX, SETTINGS)),
        )
    }

    @Test fun `one pane navigates whatever the stack`() {
        assertEquals(
            "one pane: the narrow list has nowhere to show an anchor, the reader must be a route",
            EmailOpenOrder.Navigate,
            PaneOrders.emailOpen(twoPanes = false, stack = listOf(GRAPH, INBOX)),
        )
        assertEquals(EmailOpenOrder.Navigate, PaneOrders.emailOpen(twoPanes = false, stack = emptyList()))
    }

    @Test fun `an unknown entry above the list navigates`() {
        assertEquals(
            "a null above the list is a destination the rule cannot name; the pane is not posted " +
                "under something unnamed",
            EmailOpenOrder.Navigate,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH, INBOX, null)),
        )
    }

    @Test fun `a filled-in url decides as its declared route does`() {
        assertEquals(
            "the verdict is taken on each route's name, before the first '/' or '?'",
            EmailOpenOrder.Navigate,
            PaneOrders.emailOpen(twoPanes = true, stack = listOf(GRAPH, INBOX, "message/m-42?accountId=acc-1")),
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
        const val SETTINGS = "settings?accountId={accountId}"
    }
}
