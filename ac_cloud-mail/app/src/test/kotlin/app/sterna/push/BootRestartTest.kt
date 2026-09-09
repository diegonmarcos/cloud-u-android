package app.sterna.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a reboot only the connection Sterna holds itself can be missing (issue #98): the
 */
class BootRestartTest {

    private fun account(transport: Transport, notifications: Boolean = true) =
        AccountPushState(notificationsEnabled = notifications, transport = transport)

    @Test fun `no account configured starts nothing`() {
        assertFalse(BootRestart.needsPushService(emptyList()))
    }

    @Test fun `an account on a connection of ours is restarted`() {
        assertTrue(BootRestart.needsPushService(listOf(account(Transport.EVENT_SOURCE))))
        assertTrue(BootRestart.needsPushService(listOf(account(Transport.IMAP_IDLE))))
    }

    @Test fun `an account served by a UnifiedPush distributor starts nothing`() {
        assertFalse(BootRestart.needsPushService(listOf(account(Transport.UNIFIED_PUSH))))
    }

    @Test fun `an account left to the periodic poll starts nothing`() {
        // Battery-saver delivery and linked sub-accounts both resolve to PERIODIC.
        assertFalse(BootRestart.needsPushService(listOf(account(Transport.PERIODIC))))
    }

    @Test fun `notifications turned off starts nothing even on a direct transport`() {
        assertFalse(
            BootRestart.needsPushService(
                listOf(
                    account(Transport.EVENT_SOURCE, notifications = false),
                    account(Transport.IMAP_IDLE, notifications = false),
                ),
            ),
        )
    }

    @Test fun `one direct account among push-served ones is enough`() {
        assertTrue(
            BootRestart.needsPushService(
                listOf(
                    account(Transport.UNIFIED_PUSH),
                    account(Transport.PERIODIC),
                    account(Transport.EVENT_SOURCE),
                ),
            ),
        )
    }

    @Test fun `a silenced direct account does not drag the service up for the others`() {
        assertFalse(
            BootRestart.needsPushService(
                listOf(
                    account(Transport.UNIFIED_PUSH),
                    account(Transport.EVENT_SOURCE, notifications = false),
                ),
            ),
        )
    }
}
