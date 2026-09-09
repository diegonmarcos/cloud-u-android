package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified refresh doubles as the connectivity probe (#65/#92): a VPN killswitch is invisible to
 */
class UnifiedRefreshResultTest {
    private fun meta(id: String) = AccountInboxMeta(id, id, "inbox-$id", "Inbox", 0)

    @Test fun `all accounts failed and none synced is a connectivity failure`() {
        val result = UnifiedRefreshResult(metas = emptyList(), failures = listOf(RuntimeException("net")))
        assertTrue(result.isConnectivityFailure)
    }

    @Test fun `at least one account synced is not a connectivity failure`() {
        val result = UnifiedRefreshResult(
            metas = listOf(meta("a")),
            failures = listOf(RuntimeException("b is down")),
        )
        assertFalse(result.isConnectivityFailure)
    }

    @Test fun `every account synced is not a connectivity failure`() {
        val result = UnifiedRefreshResult(metas = listOf(meta("a"), meta("b")), failures = emptyList())
        assertFalse(result.isConnectivityFailure)
    }

    @Test fun `nothing attempted is not a connectivity failure`() {
        val result = UnifiedRefreshResult(metas = emptyList(), failures = emptyList())
        assertFalse(result.isConnectivityFailure)
    }
}
