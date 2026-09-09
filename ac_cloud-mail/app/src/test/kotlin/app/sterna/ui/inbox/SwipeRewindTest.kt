package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind "this row's swipe did not take, put it back": is THIS row one the view model
 */
class SwipeRewindTest {

    @Test fun `a row nobody reported stays where it is`() {
        assertFalse(needsSwipeRewind(emptySet(), accountId = "a1", emailId = "M1"))
    }

    @Test fun `the row whose write failed comes back`() {
        assertTrue(needsSwipeRewind(setOf(EmailKey("a1", "M1")), accountId = "a1", emailId = "M1"))
    }

    @Test fun `the homonym in the other account does not move`() {
        // #92, on this path: same id, other account. A bare-id test would answer true here, the
        // sibling row would snap back from a swipe nobody made on it, and the row that actually
        // failed would stay invisible.
        assertFalse(needsSwipeRewind(setOf(EmailKey("a1", "M1")), accountId = "a2", emailId = "M1"))
    }

    @Test fun `another message of the same account does not move`() {
        assertFalse(needsSwipeRewind(setOf(EmailKey("a1", "M1")), accountId = "a1", emailId = "M2"))
    }

    @Test fun `a row with no account matches only the account-less key`() {
        // Email.accountId is nullable (single-account rows loaded before the id is known), and
        // EmailKey carries the null as-is. Null must match null and nothing else.
        assertTrue(needsSwipeRewind(setOf(EmailKey(null, "M1")), accountId = null, emailId = "M1"))
        assertFalse(needsSwipeRewind(setOf(EmailKey(null, "M1")), accountId = "a1", emailId = "M1"))
        assertFalse(needsSwipeRewind(setOf(EmailKey("a1", "M1")), accountId = null, emailId = "M1"))
    }

    /**
     * The pivot, run end to end rather than assumed: the key the view model REPORTS is built by
     */
    @Test fun `the key the view model reports is the key the screen asks with`() {
        val swiped = Email(id = "M1", accountId = "a1", mailboxId = "mbA", subject = "s")
        val homonym = Email(id = "M1", accountId = "a2", mailboxId = "mbA", subject = "s")

        val reported = setOf(swiped.emailKey())

        assertTrue(needsSwipeRewind(reported, swiped.accountId, swiped.id))
        assertFalse(needsSwipeRewind(reported, homonym.accountId, homonym.id))
    }

    @Test fun `one failure among several rewinds its own row only`() {
        val reported = setOf(EmailKey("a1", "M1"), EmailKey("a2", "M9"))
        assertTrue(needsSwipeRewind(reported, accountId = "a1", emailId = "M1"))
        assertTrue(needsSwipeRewind(reported, accountId = "a2", emailId = "M9"))
        assertFalse(needsSwipeRewind(reported, accountId = "a2", emailId = "M1"))
        assertFalse(needsSwipeRewind(reported, accountId = "a1", emailId = "M9"))
    }
}
