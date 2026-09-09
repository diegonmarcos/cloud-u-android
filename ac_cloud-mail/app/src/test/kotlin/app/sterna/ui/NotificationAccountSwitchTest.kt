package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule behind "opening a notification switches to the message's account" (issue #31
 * follow-up): which account becomes current, and the cases where nothing must move.
 */
class NotificationAccountSwitchTest {

    private val known = listOf(JORDAN, ALEX)

    private fun resolve(
        notification: String?,
        current: String = JORDAN,
        accounts: Collection<String> = known,
        unified: Boolean = false,
    ) = NotificationAccountSwitch.resolve(notification, current, accounts, unified)

    @Test fun `a notification from another account switches to it`() {
        assertEquals(ALEX, resolve(notification = ALEX, current = JORDAN))
    }

    @Test fun `a notification from the current account changes nothing`() {
        assertNull(resolve(notification = JORDAN, current = JORDAN))
    }

    @Test fun `an account that no longer exists is not switched to`() {
        assertNull(resolve(notification = "revoked-share", current = JORDAN))
    }

    @Test fun `a notification without an account changes nothing`() {
        assertNull(resolve(notification = null, current = JORDAN))
        assertNull(resolve(notification = "", current = JORDAN))
    }

    @Test fun `the unified inbox is left alone`() {
        assertNull(resolve(notification = ALEX, current = JORDAN, unified = true))
    }

    @Test fun `the unified inbox is left alone even for the current account`() {
        assertNull(resolve(notification = JORDAN, current = JORDAN, unified = true))
    }

    @Test fun `a single-account setup never switches`() {
        assertNull(resolve(notification = JORDAN, current = JORDAN, accounts = listOf(JORDAN)))
    }

    private companion object {
        const val JORDAN = "acc-jordan"
        const val ALEX = "acc-alex"
    }
}
