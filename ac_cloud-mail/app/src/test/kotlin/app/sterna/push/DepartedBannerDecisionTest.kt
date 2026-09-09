package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Codeberg #134: "when deleting an email with a different client, e.g. Thunderbird-Desktop, the
 */
class DepartedBannerDecisionTest {

    private val account = "acct-a"

    @Test fun `a message deleted from another client loses the banner it still has`() {
        val active = setOf(Notifications.childId(account, "M1"), Notifications.childId(account, "M2"))
        assertEquals(
            listOf("M1"),
            Notifications.departuresToCancel(active, account, departedIds = listOf("M1"), stillHeld = listOf("M2")),
        )
    }

    @Test fun `a departure with nothing on screen costs nothing`() {
        assertEquals(
            emptyList<String>(),
            Notifications.departuresToCancel(emptySet(), account, listOf("M1"), stillHeld = emptyList()),
        )
    }

    @Test fun `a banner posted by a build older than the account qualification is cancellable too`() {
        // Pre-#92 banners carry the bare id; without this a notification posted by an older build
        // could never be taken down again.
        assertEquals(
            listOf("M1"),
            Notifications.departuresToCancel(setOf("M1".hashCode()), account, listOf("M1"), emptyList()),
        )
    }

    @Test fun `another account's banner is never taken down`() {
        // Two accounts on one Stalwart share message ids (#92): a departure in A must not cancel B.
        val active = setOf(Notifications.childId("acct-b", "M1"))
        assertEquals(
            emptyList<String>(),
            Notifications.departuresToCancel(active, account, listOf("M1"), emptyList()),
        )
    }

    @Test fun `an id the folder still holds keeps its banner`() {
        // Whatever the delta said, a message the folder's post-sync content still contains is
        // there. Deleting less is the safe error; the banner stays.
        val active = setOf(Notifications.childId(account, "M1"))
        assertEquals(
            emptyList<String>(),
            Notifications.departuresToCancel(active, account, listOf("M1"), stillHeld = listOf("M1")),
        )
    }

    @Test fun `a pass that names nothing cancels nothing`() {
        // IMAP, a failed pass, an offline refresh: an empty departure list is not "everything is
        // gone". Only a NAMED id is ever cancelled.
        val active = setOf(Notifications.childId(account, "M1"), Notifications.childId(account, "M2"))
        assertEquals(
            emptyList<String>(),
            Notifications.departuresToCancel(active, account, departedIds = emptyList(), stillHeld = emptyList()),
        )
    }

    @Test fun `an id both the delta and the ghost sweep name is cancelled once`() {
        val active = setOf(Notifications.childId(account, "M1"))
        assertEquals(
            listOf("M1"),
            Notifications.departuresToCancel(active, account, listOf("M1", "M1"), emptyList()),
        )
    }

    @Test fun `the summary count drops the departures with the banners`() {
        // The group summary is rebuilt from what is left AFTER the pass: a departure has to be
        // counted out, or the account is credited with a message that is gone and the "who
        // announces this batch" rule (#56) reads the wrong number.
        val active = setOf(Notifications.childId(account, "M1"), Notifications.childId(account, "M2"))
        val departed = Notifications.departuresToCancel(active, account, listOf("M1"), stillHeld = listOf("M2"))
        val live = Notifications.liveChildIdsAfter(active, account, cleared = departed, added = emptyList())
        assertEquals(setOf(Notifications.childId(account, "M2")), live)
        assertFalse(Notifications.summaryShownFor(live.size))
    }
}
