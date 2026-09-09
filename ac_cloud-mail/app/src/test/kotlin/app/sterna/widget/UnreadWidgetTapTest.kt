package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tap's destination, RUN — not read as source, not recomputed here. Every expectation below is
 */
class UnreadWidgetTapTest {

    private fun account(id: String) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = "inbox-$id",
    )

    @Test fun `two accounts open the unified inbox`() {
        assertEquals(
            "from two accounts up the drawer posts an All inboxes entry, so the widget asks for " +
                "it — that is the promise made on #112.",
            WidgetTapTarget.UnifiedInbox,
            UnreadWidgetTap.of(listOf(account("a"), account("b"))),
        )
    }

    @Test fun `three accounts open the unified inbox too`() {
        assertEquals(
            "the arm is 'more than one account', not 'exactly two'.",
            WidgetTapTarget.UnifiedInbox,
            UnreadWidgetTap.of(listOf(account("a"), account("b"), account("c"))),
        )
    }

    /**
     * The arm the whole file exists for. Asserted on the VALUE returned, not on the absence of
     */
    @Test fun `a single account opens the app as it is, never the unified inbox`() {
        assertEquals(
            "with one account the drawer offers no All inboxes entry (accounts.size > 1), so the " +
                "widget must not ask for it. Opening the app plainly already lands on that " +
                "account's inbox, which is what the number counted.",
            WidgetTapTarget.AppAsIs,
            UnreadWidgetTap.of(listOf(account("a"))),
        )
    }

    /**
     * No account configured: the app routes to welcome/connect, no list is composed and nothing
     * would consume a request for the unified view. The tap opens the app and asks for nothing.
     */
    @Test fun `no account at all opens the app as it is`() {
        assertEquals(
            "with nothing configured there is no list to select anything on — the app shows its " +
                "own sign-in route, and a pending unified request would be applied later to a " +
                "first account that has no second one to be unified with.",
            WidgetTapTarget.AppAsIs,
            UnreadWidgetTap.of(emptyList()),
        )
    }
}
