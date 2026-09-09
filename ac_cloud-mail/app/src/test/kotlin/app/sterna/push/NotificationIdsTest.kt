package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification ids must carry the account, not just the message id. JMAP ids are per-account, so
 */
class NotificationIdsTest {

    @Test fun `the same message id in two accounts gets two notification ids`() {
        assertNotEquals(
            Notifications.childId("acct-a", "M42"),
            Notifications.childId("acct-b", "M42"),
        )
    }

    @Test fun `the same account and message always gets the same id`() {
        assertEquals(
            Notifications.childId("acct-a", "M42").toLong(),
            Notifications.childId("acct-a", "M42").toLong(),
        )
    }

    @Test fun `two messages in one account get two notification ids`() {
        assertNotEquals(
            Notifications.childId("acct-a", "M42"),
            Notifications.childId("acct-a", "M43"),
        )
    }

    @Test fun `the account and id boundary is not ambiguous`() {
        // "a" + "bc" must not collide with "ab" + "c".
        assertNotEquals(Notifications.childId("a", "bc"), Notifications.childId("ab", "c"))
    }

    @Test fun `a live notification is matched by the qualified id`() {
        val active = setOf(Notifications.childId("acct-a", "M42"))
        assertTrue(Notifications.isChildActive(active, "acct-a", "M42"))
        assertFalse(Notifications.isChildActive(active, "acct-a", "M43"))
    }

    @Test fun `another account's notification is not matched`() {
        val active = setOf(Notifications.childId("acct-b", "M42"))
        assertFalse(Notifications.isChildActive(active, "acct-a", "M42"))
    }

    @Test fun `a notification posted by the previous build is still matched`() {
        // Pre-update notifications on screen carry the bare id; they must stay dismissable.
        val active = setOf("M42".hashCode())
        assertTrue(Notifications.isChildActive(active, "acct-a", "M42"))
    }
}
