package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codeberg #56: one arrival must announce itself once. A group where the summary AND its
 */
class GroupAlertTest {

    @Test fun `a single message announces itself`() {
        // No summary is posted for one child, so the child is what the user hears.
        assertFalse(Notifications.summaryShownFor(1))
    }

    @Test fun `nothing left on screen is not a summary either`() {
        assertFalse(Notifications.summaryShownFor(0))
    }

    @Test fun `from two messages up the summary speaks`() {
        assertTrue(Notifications.summaryShownFor(2))
        assertTrue(Notifications.summaryShownFor(7))
    }

    @Test fun `the first message of an empty group stands alone`() {
        val live = Notifications.liveChildIdsAfter(
            active = emptySet(),
            accountId = "acct-a",
            cleared = emptyList(),
            added = listOf("M1"),
        )
        assertEquals(1, live.size)
        assertFalse(Notifications.summaryShownFor(live.size))
    }

    @Test fun `two messages arriving together are announced by the summary`() {
        val live = Notifications.liveChildIdsAfter(emptySet(), "acct-a", emptyList(), listOf("M1", "M2"))
        assertEquals(2, live.size)
        assertTrue(Notifications.summaryShownFor(live.size))
    }

    @Test fun `a message joining one already on screen is announced by the summary`() {
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val live = Notifications.liveChildIdsAfter(active, "acct-a", emptyList(), listOf("M2"))
        assertEquals(2, live.size)
        assertTrue(Notifications.summaryShownFor(live.size))
    }

    @Test fun `a message that arrives as another is cleared stands alone`() {
        // M1 was read on another device: its notification goes as M2's is posted, so M2 is
        // alone once the pass is done and must be the one that makes the noise.
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val live = Notifications.liveChildIdsAfter(active, "acct-a", listOf("M1"), listOf("M2"))
        assertEquals(setOf(Notifications.childId("acct-a", "M2")), live)
        assertFalse(Notifications.summaryShownFor(live.size))
    }

    @Test fun `a notification posted by the previous build is cleared too`() {
        // Pre-update children carry the bare message id; it must not be counted as still live.
        val live = Notifications.liveChildIdsAfter(setOf("M1".hashCode()), "acct-a", listOf("M1"), listOf("M2"))
        assertEquals(1, live.size)
    }

    @Test fun `accounts are counted apart`() {
        // Account B's live notifications never reach A's set, so an arrival alone in A is
        // announced by itself even while B has a group of its own (each group has its summary).
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val liveA = Notifications.liveChildIdsAfter(active, "acct-a", emptyList(), listOf("M2"))
        val liveB = Notifications.liveChildIdsAfter(emptySet(), "acct-b", emptyList(), listOf("M9"))
        assertTrue(Notifications.summaryShownFor(liveA.size))
        assertFalse(Notifications.summaryShownFor(liveB.size))
    }

    @Test fun `re-posting a message already on screen does not conjure a summary`() {
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val live = Notifications.liveChildIdsAfter(active, "acct-a", emptyList(), listOf("M1"))
        assertEquals(1, live.size)
        assertFalse(Notifications.summaryShownFor(live.size))
    }

    // ------------------------------------------------------------------------------------------
    // Codeberg #134, second half: the same rule read as "what may still be on screen afterwards".
    //
    // `NotificationManager.cancel` returns once the system has queued the work, and
    // `getActiveNotifications` reads the list without draining that queue — so a pass that

    @Test fun `the last banner departing leaves nothing to count and nothing to re-post`() {
        // Summary standing over exactly one child, and that message is deleted from another
        // client. This is the reported path: the shade still shows the child, so a decision taken
        // from the shade tears the summary down and re-posts the child that was just cancelled.
        val gone = Notifications.childId("acct-a", "M1")
        val live = Notifications.liveChildIdsAfter(setOf(gone), "acct-a", listOf("M1"), emptyList())
        assertEquals(emptySet<Int>(), live)
        assertFalse(Notifications.summaryShownFor(live.size))
        // The half that is the whole fix: the departing id is NOT in the set, so the filter on it
        // cannot list, count or put that banner back.
        assertFalse(gone in live)
    }

    @Test fun `two banners read in the same pass leave the summary with no children at all`() {
        // The mirror symptom: every child goes, and the "3 new messages" header must not be left
        // standing alone on the bar.
        val active = setOf(Notifications.childId("acct-a", "M1"), Notifications.childId("acct-a", "M2"))
        val live = Notifications.liveChildIdsAfter(active, "acct-a", listOf("M1", "M2"), emptyList())
        assertEquals(emptySet<Int>(), live)
        assertFalse(Notifications.summaryShownFor(live.size))
    }

    @Test fun `a message posted in this very pass is expected even before the shade shows it`() {
        // `notify` is asynchronous exactly like `cancel`, so a banner posted moments ago may not
        // be in the read either. It is in the EXPECTED set, which is what keeps the count at two
        // and stops the summary being torn down over a banner nobody would then put back.
        val standing = Notifications.childId("acct-a", "M1")
        val fresh = Notifications.childId("acct-a", "M2")
        val live = Notifications.liveChildIdsAfter(setOf(standing), "acct-a", emptyList(), listOf("M2"))
        assertEquals(setOf(standing, fresh), live)
        assertTrue(Notifications.summaryShownFor(live.size))
    }

    @Test fun `clearing one account's message leaves another account's banner expected`() {
        // #92: the id shapes are per account, so subtracting this account's message may never
        // take a neighbouring account's banner out of what is expected to remain.
        val mine = Notifications.childId("acct-a", "M1")
        val theirs = Notifications.childId("acct-b", "M1")
        val live = Notifications.liveChildIdsAfter(setOf(mine, theirs), "acct-a", listOf("M1"), emptyList())
        assertEquals(setOf(theirs), live)
    }

    // ------------------------------------------------------------------------------------------
    // [Notifications.childIdsOf] — "which ids on the shade ARE this message", executed here on

    @Test fun `a message is both of its id shapes, the current one and the pre-#92 one`() {
        assertEquals(
            setOf(Notifications.childId("acct-a", "M1"), "M1".hashCode()),
            Notifications.childIdsOf("acct-a", listOf("M1")),
        )
    }

    @Test fun `only the current shape carries the account`() {
        // #92: two accounts on the same server routinely hand us the same message id, so the
        // current shape must differ between them — and the legacy shape, which cannot, is the
        // one they share. Naming an account's id under another account would cancel, or fail to
        // put back, a banner belonging to somebody else's mailbox.
        val mine = Notifications.childIdsOf("acct-a", listOf("M1"))
        val theirs = Notifications.childIdsOf("acct-b", listOf("M1"))
        assertTrue(Notifications.childId("acct-a", "M1") in mine)
        assertFalse(Notifications.childId("acct-a", "M1") in theirs)
        assertEquals(setOf("M1".hashCode()), mine intersect theirs)
    }

    @Test fun `every message named brings both of its shapes`() {
        assertEquals(
            setOf(
                Notifications.childId("acct-a", "M1"),
                "M1".hashCode(),
                Notifications.childId("acct-a", "M2"),
                "M2".hashCode(),
            ),
            Notifications.childIdsOf("acct-a", listOf("M1", "M2")),
        )
    }

    @Test fun `cancelling nothing names no id at all`() {
        // The commonest call by far — a summary refresh that took no banner down. It must remove
        // NOTHING from the shade's answer: every live child stays listed, counted and re-postable.
        assertEquals(emptySet<Int>(), Notifications.childIdsOf("acct-a", emptyList()))
    }

    @Test fun `the subtraction of the expected set is that very same rule`() {
        // `liveChildIdsAfter` clears through `childIdsOf`, so the two can never disagree about
        // what an id of a banner is: what is named as cancelled is exactly what stops being
        // expected.
        val active = setOf(Notifications.childId("acct-a", "M1"), "M2".hashCode())
        val live = Notifications.liveChildIdsAfter(active, "acct-a", listOf("M1", "M2"), emptyList())
        assertEquals(active - Notifications.childIdsOf("acct-a", listOf("M1", "M2")), live)
        assertEquals(emptySet<Int>(), live)
    }

    @Test fun `the pre-account id shape leaves the expected set with its message`() {
        // A banner posted by a build older than #92 carries the bare message id. It must drop out
        // of the expected set too, or the filter would keep re-posting it for ever.
        val legacy = "M1".hashCode()
        val other = Notifications.childId("acct-a", "M2")
        val live = Notifications.liveChildIdsAfter(setOf(legacy, other), "acct-a", listOf("M1"), emptyList())
        assertEquals(setOf(other), live)
        assertFalse(Notifications.summaryShownFor(live.size))
    }

    // ------------------------------------------------------------------------------------------
    // [Notifications.teardownRepairable] — "may this teardown be undone?", executed here.
    //
    // Cancelling the summary cascades EVERY live child of the group away
    // (REASON_GROUP_SUMMARY_CANCELED), and the only repair is to copy back the notification

    @Test fun `the banner this pass has just posted forbids the teardown`() {
        // The reported path. Two messages cleared from another client, one arriving: the arrival
        // is all that is expected, and the shade — read before the post has landed — shows none of
        // it. Demolishing here takes the fresh banner with it and the user never hears of that
        // message again.
        val expected = Notifications.liveChildIdsAfter(
            active = setOf(Notifications.childId("acct-a", "M1"), Notifications.childId("acct-a", "M2")),
            accountId = "acct-a",
            cleared = listOf("M1", "M2"),
            added = listOf("M3"),
        )
        assertEquals(setOf(Notifications.childId("acct-a", "M3")), expected)
        assertFalse(Notifications.teardownRepairable(expected, shownChildIds = emptySet()))
    }

    @Test fun `a summary over nothing at all may still come down`() {
        // The mirror, and it must stay allowed: every child gone, nothing expected, nothing on
        // screen. Refusing here would leave a "3 new messages" header standing alone on the bar
        // with no way to ever remove it.
        assertTrue(Notifications.teardownRepairable(emptySet(), emptySet()))
    }

    @Test fun `what the shade already shows can be put back, so it may be taken down`() {
        // The ordinary teardown: the account is down to one banner, that banner IS on screen, and
        // the copy the shade handed over is what goes back up under its own id.
        val lone = Notifications.childId("acct-a", "M1")
        assertTrue(Notifications.teardownRepairable(setOf(lone), setOf(lone)))
    }

    @Test fun `a banner the caller never heard of does not block the teardown`() {
        // A snooze wake-up posted into this same group, or a message that arrived during the
        // seconds the diff pass spends on its server round trip: the caller expects nothing, the
        // shade shows it, and it is re-postable like any other. Reading the guard as "the two sets
        // are equal" would freeze the summary up for good over mail the caller cannot name.
        val snoozed = Notifications.childId("acct-a", "M9")
        assertTrue(Notifications.teardownRepairable(expectedChildIds = emptySet(), shownChildIds = setOf(snoozed)))
        // …and the reverse of that same pair is exactly what must be refused.
        assertFalse(Notifications.teardownRepairable(expectedChildIds = setOf(snoozed), shownChildIds = emptySet()))
    }

    @Test fun `an expected banner wearing the pre-#92 id counts as shown when it is on screen`() {
        // A banner posted by a build older than #92 carries the bare message id, and that is the
        // id the shade reports for it. If the guard could not see it, the summary of an account
        // updated mid-batch could never be rebuilt again.
        val legacy = "M1".hashCode()
        val expected = Notifications.liveChildIdsAfter(
            active = setOf(legacy),
            accountId = "acct-a",
            cleared = emptyList(),
            added = emptyList(),
        )
        assertEquals(setOf(legacy), expected)
        assertTrue(Notifications.teardownRepairable(expected, shownChildIds = setOf(legacy)))
        // The same message under its current shape is a DIFFERENT banner: it is not on screen, so
        // it may not be demolished on the strength of the old one.
        assertFalse(
            Notifications.teardownRepairable(
                expectedChildIds = setOf(Notifications.childId("acct-a", "M1")),
                shownChildIds = setOf(legacy),
            ),
        )
    }

    @Test fun `another account's banners do not make this account's teardown repairable`() {
        // #92: two accounts on one server. B having plenty on screen says nothing about A's fresh
        // arrival, which is still un-repostable — and the summary being torn down is A's.
        val mine = Notifications.childId("acct-a", "M1")
        val theirs = setOf(Notifications.childId("acct-b", "M1"), Notifications.childId("acct-b", "M2"))
        assertFalse(Notifications.teardownRepairable(expectedChildIds = setOf(mine), shownChildIds = theirs))
    }

    @Test fun `one missing id out of several is enough to hold the summary up`() {
        // Half a repair is not one: the banner nobody can put back is unread mail gone for good,
        // whatever else the shade is showing.
        val standing = Notifications.childId("acct-a", "M1")
        val fresh = Notifications.childId("acct-a", "M2")
        assertFalse(Notifications.teardownRepairable(setOf(standing, fresh), setOf(standing)))
    }
}
