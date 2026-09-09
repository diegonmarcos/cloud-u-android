package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The three transitions of the reading pane (#103), run as the pure functions they are. Each
 */
class ReadingPaneRuleTest {

    private val m1 = MessageAnchor("m1", "acc-a", "list", 3, null)
    private val m2 = MessageAnchor("m2", "acc-a", "list", 4, null)
    private val empty = ReadingPaneState()

    @Test fun `a tap opens the message in a new session`() {
        val next = ReadingPaneRule.open(ReadingPaneState(anchor = null, session = 4), m1)
        assertEquals("the tapped message must become the anchor", m1, next.anchor)
        assertEquals(
            "a tap must start a new session: without it the pager already on screen keeps its " +
                "initial page and shows the previous message under the new row's highlight",
            5,
            next.session,
        )
    }

    @Test fun `a tap on another message replaces the anchor and moves the session again`() {
        val first = ReadingPaneRule.open(empty, m1)
        val next = ReadingPaneRule.open(first, m2)
        assertEquals(m2, next.anchor)
        assertEquals(first.session + 1, next.session)
    }

    @Test fun `a tap on the message already shown changes nothing`() {
        val open = ReadingPaneRule.open(empty, m1)
        assertSame(
            "re-tapping the row already open must not touch the state: a new session there " +
                "rebuilds the pager and reloads the body the reader is in the middle of",
            open,
            ReadingPaneRule.open(open, m1.copy(index = 9)),
        )
    }

    @Test fun `a tap on the same id under another account is another message`() {
        val open = ReadingPaneRule.open(empty, m1)
        val next = ReadingPaneRule.open(open, m1.copy(accountId = "acc-b"))
        assertEquals("the other account's homonymous message must open (#92)", "acc-b", next.anchor?.accountId)
        assertEquals(open.session + 1, next.session)
    }

    @Test fun `a swipe moves the anchor to the settled message and keeps the session`() {
        val open = ReadingPaneRule.open(empty, m1)
        val next = ReadingPaneRule.follow(open, "m2", "acc-a", 4)
        assertEquals("the row painted current must be the one the pager settled on", "m2", next.anchor?.emailId)
        assertEquals("acc-a", next.anchor?.accountId)
        assertEquals(4, next.anchor?.index)
        assertEquals(
            "a swipe that changes the session rebuilds the pager and reloads the body on every page",
            open.session,
            next.session,
        )
    }

    @Test fun `a swipe into the sibling account's message carries that account`() {
        val open = ReadingPaneRule.open(empty, m1)
        val next = ReadingPaneRule.follow(open, "m1", "acc-b", 4)
        assertEquals(
            "the unified inbox pages over two accounts; settling on acc-b's homonymous message " +
                "must paint acc-b's row current, not acc-a's (#92)",
            "acc-b",
            next.anchor?.accountId,
        )
        assertEquals("m1", next.anchor?.emailId)
        assertEquals(open.session, next.session)
    }

    @Test fun `a swipe keeps the context it pages over`() {
        val thread = MessageAnchor("m1", "acc-a", "thread", 0, "acc-a|t9")
        val open = ReadingPaneRule.open(empty, thread)
        val next = ReadingPaneRule.follow(open, "m2", "acc-a", 1)
        assertEquals("src must survive a swipe, or a restored pane pages over the wrong list", "thread", next.anchor?.src)
        assertEquals("the conversation key must survive a swipe", "acc-a|t9", next.anchor?.thread)
    }

    @Test fun `a swipe with nothing open does nothing`() {
        assertSame(
            "the pager reports its settled page while the pane is being emptied; that report " +
                "must not put a message back in it",
            empty,
            ReadingPaneRule.follow(empty, "m2", "acc-a", 4),
        )
    }

    @Test fun `settling on the message already shown does nothing`() {
        val open = ReadingPaneRule.open(empty, m1)
        assertSame(
            "the pager settles on its initial page at composition; that must not rewrite the anchor",
            open,
            ReadingPaneRule.follow(open, "m1", "acc-a", 3),
        )
    }

    @Test fun `closing empties the pane and keeps the session`() {
        val open = ReadingPaneRule.open(ReadingPaneState(null, 2), m1)
        val next = ReadingPaneRule.close(open)
        assertNull("after Back or an action the pane must show nothing — never the next message", next.anchor)
        assertEquals("closing is not a new reading session", open.session, next.session)
    }

    @Test fun `closing an empty pane gives the same state back`() {
        assertSame(empty, ReadingPaneRule.close(empty))
    }

    // -- what a change of scope does to the pane (#103, volet 3) ---------------------------------

    @Test fun `a change of view empties the pane`() {
        val open = ReadingPaneRule.open(ReadingPaneState(null, 2), m1)
        val next = ReadingPaneRule.onViewChanged(open)
        assertNull(
            "a folder tapped in the drawer must not leave another folder's message on the right",
            next.anchor,
        )
        assertEquals("emptying is not a new reading session", open.session, next.session)
        assertSame("nothing to empty, nothing to change", empty, ReadingPaneRule.onViewChanged(empty))
    }

    // -- what a TAPPED NOTIFICATION does to the pane (#103) ---------------------------------------

    @Test fun `the folder a notification came from keeps that notification's own message`() {
        val open = ReadingPaneRule.open(empty, m1)
        assertSame(
            "the folder a tapped notification asks for lands on the pane a beat before or a beat " +
                "after the message that same notification posted in it. When the pane already " +
                "holds THAT message, the folder must leave it alone — emptying here takes the very " +
                "message the tap asked for off the right and the person meets an empty pane",
            open,
            ReadingPaneRule.onFolderFromNotification(open, emailId = "m1", accountId = "acc-a"),
        )
    }

    @Test fun `the folder a notification came from empties any OTHER message`() {
        val open = ReadingPaneRule.open(empty, m1)
        assertNull(
            "a notification whose message is NOT in the pane (its reader stacked on a composer, " +
                "say) still moves the list to another folder: the previous folder's message must " +
                "go, or it is what the person meets on her way out of the reader",
            ReadingPaneRule.onFolderFromNotification(open, emailId = "m2", accountId = "acc-a").anchor,
        )
    }

    @Test fun `the folder a notification came from empties a homonymous message of another account`() {
        val open = ReadingPaneRule.open(empty, m1)
        assertNull(
            "two accounts of one server list a message under the same id (#92): the pane holding " +
                "acc-a's m1 is not holding acc-b's m1, and keeping it leaves one account's mail " +
                "beside another account's list",
            ReadingPaneRule.onFolderFromNotification(open, emailId = "m1", accountId = "acc-b").anchor,
        )
    }

    @Test fun `the folder a notification came from leaves an empty pane empty`() {
        assertSame(empty, ReadingPaneRule.onFolderFromNotification(empty, emailId = "m1", accountId = "acc-a"))
    }

    @Test fun `a switch to the parked message's own account replays it`() {
        assertEquals(
            "the message a tapped notification parked is put back by the account switch that same " +
                "notification asked for — that is the whole point of parking it",
            m1,
            ReadingPaneRule.replayOnAccountChange(m1, arrivingAccountId = "acc-a"),
        )
    }

    @Test fun `a switch to another account replays nothing`() {
        assertNull(
            "a park left over from a notification for acc-a must NOT be replayed onto acc-b's " +
                "list: that is one account's message sitting beside another account's mail (#92)",
            ReadingPaneRule.replayOnAccountChange(m1, arrivingAccountId = "acc-b"),
        )
    }

    @Test fun `nothing parked replays nothing`() {
        assertNull(
            "an ordinary account switch — the drawer's carousel — has no park to replay",
            ReadingPaneRule.replayOnAccountChange(null, arrivingAccountId = "acc-a"),
        )
        assertNull(
            "not even when the arriving account is unknown",
            ReadingPaneRule.replayOnAccountChange(null, arrivingAccountId = null),
        )
    }

    @Test fun `an anchor restored after process death loses its paging context`() {
        val restored = ReadingPaneRule.restored(MessageAnchor("m1", "acc-a", "list", 80, null))
        assertEquals(
            "a restored anchor must be a lone message (src null, index 0, thread null): the list " +
                "restarts on its first page, so the pager would fall back on the index, open " +
                "another message and mark it read",
            MessageAnchor("m1", "acc-a", null, 0, null),
            restored,
        )
        val thread = ReadingPaneRule.restored(MessageAnchor("m2", "acc-b", "thread", 2, "acc-b|t9"))
        assertNull("the conversation key is paging context too", thread.thread)
        assertEquals("the message itself is kept", "m2", thread.emailId)
        assertEquals("under its own account", "acc-b", thread.accountId)
    }
}
