package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The back-stack half of a widget tap, RUN — not read as source, not recomputed here. Every
 */
class WidgetTapNavigationTest {

    /**
     * The defect (#112): `selectUnified()` moves the LIST, which is a destination underneath, so a
     */
    @Test fun `a message on screen is popped, so the unified list is what gets shown`() {
        assertEquals(
            "with the reader on top of the list and nothing else in between, the widget's tap " +
                "must unwind back to the list — otherwise selectUnified() switches a list nobody " +
                "can see and the promise made on #112 is only kept on the way back.",
            WidgetTapReturn.BackToList,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX, MESSAGE)),
        )
    }

    /** Paging from one message into another (a thread, a search hit) stacks readers. All poppable. */
    @Test fun `a pile of readers is still only readers`() {
        assertEquals(
            "every entry above the list is a reader, so popping back to the list destroys nothing " +
                "but readers.",
            WidgetTapReturn.BackToList,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX, MESSAGE, MESSAGE)),
        )
    }

    /**
     * THE TEST THIS FILE EXISTS FOR — a data-loss rule, not an ergonomic one.
     */
    @Test fun `the composer is NEVER popped, because that would take a live draft off the screen`() {
        assertEquals(
            "⛔ the composer must be left strictly alone. Unwinding it to reveal a counter risks " +
                "the draft being written, and a lost draft cannot be recovered — the selection " +
                "made underneath is enough, it shows when the user leaves on their own.",
            WidgetTapReturn.StayPut,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX, COMPOSE)),
        )
    }

    /**
     * THE SAME DRAFT, LOST THROUGH THE BACK DOOR — the composer BURIED under a reader.
     */
    @Test fun `a composer BURIED under a reader is not popped either, draft and all`() {
        assertEquals(
            "⛔ popBackStack(\"inbox\") drops EVERY entry above the list, not just the reader on " +
                "top. With a composer underneath, unwinding to show a counter destroys the draft " +
                "being written. The tap must leave this stack alone; the list underneath still " +
                "carries the switch.",
            WidgetTapReturn.StayPut,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX, COMPOSE, MESSAGE)),
        )
    }

    /**
     * Search under a reader — no data lost, but a screenful of work is: the `search` entry and its
     * ViewModel go with the pop, and the user comes back to an empty search instead of results.
     */
    @Test fun `search under a reader is not popped, so the results survive`() {
        assertEquals(
            "search opens a message, so the reader sits on top of `search`. Popping to the list " +
                "destroys the search entry and its ViewModel; the user's results are gone because " +
                "a counter was touched elsewhere.",
            WidgetTapReturn.StayPut,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX, SEARCH, MESSAGE)),
        )
    }

    @Test fun `the list itself has nothing to unwind`() {
        assertEquals(
            "on the list there is no reader to pop; selectUnified() alone already shows the " +
                "unified view, and a popBackStack here would be a no-op at best.",
            WidgetTapReturn.StayPut,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX)),
        )
    }

    /**
     * Everything else the graph declares, sitting alone on the list. Asserted route by route rather
     */
    @Test fun `every other declared route is left where it is`() {
        val others = mapOf(
            "search" to SEARCH,
            "settings" to SETTINGS,
            "scheduled" to "scheduled",
            "snoozed" to "snoozed",
            "outbox" to "outbox",
            "bysender" to "bysender",
        )
        assertEquals(
            "the widget only ever unwinds readers. Any other screen the user is on stays, and " +
                "the list underneath carries the switch.",
            others.mapValues { WidgetTapReturn.StayPut },
            others.mapValues { (_, route) -> WidgetTapNavigation.from(listOf(GRAPH, INBOX, route)) },
        )
    }

    /**
     * No entries at all — the composition that receives a COLD-start tap, before the NavHost has
     */
    @Test fun `an empty stack is left alone`() {
        assertEquals(
            "an empty back stack means the graph has not composed yet; there is nothing to pop.",
            WidgetTapReturn.StayPut,
            WidgetTapNavigation.from(emptyList()),
        )
    }

    /**
     * The graph's entry alone, which is what the list holds between `setGraph` and the start
     * destination being composed. There is no list entry to pop back to.
     */
    @Test fun `the graph entry on its own is not a list`() {
        assertEquals(
            "the NavGraph's own entry carries a null route here and is not a destination the user " +
                "is on. Without the list in the stack there is nothing to unwind to.",
            WidgetTapReturn.StayPut,
            WidgetTapNavigation.from(listOf(GRAPH)),
        )
    }

    /**
     * An entry the rule does not recognise, ABOVE the list, must not be popped past. A null is
     */
    @Test fun `an unknown entry above the list stops the unwind`() {
        assertEquals(
            "an entry above the list whose route the rule cannot name is not a reader, and " +
                "popping past it would destroy a destination nobody described.",
            WidgetTapReturn.StayPut,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX, null, MESSAGE)),
        )
    }

    /**
     * The declared form is what the caller reads, but a filled-in URL must not flip the verdict:
     * the rule looks at each route's own name, not at its arguments.
     */
    @Test fun `a filled-in message url decides the same way as the declared route`() {
        assertEquals(
            "the verdict is taken on each route's name, before the first '/' or '?' — arguments, " +
                "declared or filled, must not change it.",
            WidgetTapReturn.BackToList,
            WidgetTapNavigation.from(listOf(GRAPH, INBOX, "message/m-42?accountId=acc-1&index=3&src=list")),
        )
    }

    private companion object {
        /**
         * The NavGraph's own entry, first in `currentBackStack`. `SternaApp` calls
         */
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
