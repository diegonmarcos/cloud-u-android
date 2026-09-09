package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MessageAnchor] is the quintuplet of the `message/…` route, and on a narrow window it must
 */
class MessageAnchorTest {

    private val identity: (String) -> String = { it }

    @Test fun `a list tap builds the route the inbox built by hand`() {
        assertEquals(
            "src=list must give the exact route the inbox destination used to navigate to",
            "message/m1?accountId=acc-a&index=7&src=list",
            MessageAnchor("m1", "acc-a", "list", 7, null).toRoute(identity),
        )
    }

    @Test fun `a search tap builds the route the inbox built by hand`() {
        assertEquals(
            "src=search must give the exact route the inbox destination used to navigate to",
            "message/m1?accountId=acc-a&index=2&src=search",
            MessageAnchor("m1", "acc-a", "search", 2, null).toRoute(identity),
        )
    }

    @Test fun `a conversation tap builds the route the inbox built by hand`() {
        assertEquals(
            "src=thread must carry the account-qualified conversation key, as the inbox did (#92)",
            "message/m1?accountId=acc-a&index=1&src=thread&thread=acc-a|t9",
            MessageAnchor("m1", "acc-a", "thread", 1, "acc-a|t9").toRoute(identity),
        )
    }

    @Test fun `a lone message builds the route the search screen built by hand`() {
        assertEquals(
            "no src must give the two-argument route: a lone message has no context to page over",
            "message/m1?accountId=acc-a",
            MessageAnchor("m1", "acc-a", null, 0, null).toRoute(identity),
        )
    }

    @Test fun `the encoder is applied to every free-text argument`() {
        assertEquals(
            "emailId, accountId and thread are user-controlled text and must go through the encoder",
            "message/[m 1]?accountId=[a]&index=0&src=thread&thread=[a|t]",
            MessageAnchor("m 1", "a", "thread", 0, "a|t").toRoute { "[$it]" },
        )
    }

    @Test fun `a route read back gives the route written, for the four sources`() {
        for (anchor in listOf(
            MessageAnchor("m1", "acc-a", "list", 7, null),
            MessageAnchor("m1", "acc-a", "search", 2, null),
            MessageAnchor("m1", "acc-a", "thread", 1, "acc-a|t9"),
            MessageAnchor("m1", "acc-a", null, 0, null),
        )) {
            val read = MessageAnchor.fromRoute(anchor.emailId, anchor.accountId, anchor.index, anchor.src, anchor.thread)
            assertEquals(
                "an anchor restored from the route's arguments must build the same route again ($anchor)",
                anchor.toRoute(identity),
                read.toRoute(identity),
            )
        }
    }

    @Test fun `a blank account or thread in the route is none`() {
        val read = MessageAnchor.fromRoute("m1", "", 0, null, "")
        assertNull("accountId=\"\" is how the route says 'no account'; it must not become a real key", read.accountId)
        assertNull("thread=\"\" is how the route says 'no conversation'", read.thread)
    }

    @Test fun `the same id under another account is not this message`() {
        assertFalse(
            "two accounts of one server list a message under the same id (#92): the other " +
                "account's row must not be painted current",
            MessageAnchor("m1", "acc-a", "list", 0, null).matches("acc-b", "m1"),
        )
    }

    @Test fun `the same id under the same account is this message`() {
        assertTrue(MessageAnchor("m1", "acc-a", "list", 0, null).matches("acc-a", "m1"))
    }

    @Test fun `another id under the same account is not this message`() {
        assertFalse(MessageAnchor("m1", "acc-a", "list", 0, null).matches("acc-a", "m2"))
    }

    @Test fun `no account on either side still matches`() {
        assertTrue(
            "a single-account list carries no account id on its rows nor in its anchor",
            MessageAnchor("m1", null, "list", 0, null).matches(null, "m1"),
        )
    }
}
