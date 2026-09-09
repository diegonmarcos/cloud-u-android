package app.sterna.ui.inbox

import app.sterna.R
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What a failed row action (swipe delete/archive, and the Undo that puts it back) SAYS.
 */
class ActionFailureMessageTest {

    /** The reported case: airplane mode, swipe to delete, the resolver's raw English string. */
    @Test fun `offline plus an unresolved host gets the offline sentence`() {
        val t = UnknownHostException(
            "Unable to resolve host \"mail.example.com\": No address associated with hostname",
        )
        assertEquals(R.string.status_action_offline, actionFailureMessage(t, online = false))
    }

    @Test fun `offline plus a refused connection or a timeout gets the offline sentence`() {
        assertEquals(
            R.string.status_action_offline,
            actionFailureMessage(ConnectException("Connection refused"), online = false),
        )
        assertEquals(
            R.string.status_action_offline,
            actionFailureMessage(SocketTimeoutException("timeout"), online = false),
        )
    }

    @Test fun `offline plus a transport failure wrapped by the JMAP layer still gets the offline sentence`() {
        val wrapped = IOException("Email/set failed", UnknownHostException("Unable to resolve host"))
        assertEquals(R.string.status_action_offline, actionFailureMessage(wrapped, online = false))
    }

    /** Server down, or a killswitch eating the traffic on a live link: "you're offline" would lie. */
    @Test fun `online plus a transport failure gets the generic sentence`() {
        val t = UnknownHostException("Unable to resolve host \"mail.example.com\"")
        assertEquals(R.string.status_action_failed, actionFailureMessage(t, online = true))
    }

    @Test fun `offline plus a failure that is not the transport gets the generic sentence`() {
        // A 403, an expired password, a rejected certificate, a missing local file: none of them
        // become an outage just because the radio happens to be off.
        assertEquals(
            R.string.status_action_failed,
            actionFailureMessage(IllegalStateException("Email/set refused: HTTP 403"), online = false),
        )
        assertEquals(
            R.string.status_action_failed,
            actionFailureMessage(SSLHandshakeException("Trust anchor not found"), online = false),
        )
        assertEquals(
            R.string.status_action_failed,
            actionFailureMessage(FileNotFoundException("/data/gone"), online = false),
        )
    }

    @Test fun `online plus a failure that is not the transport gets the generic sentence`() {
        assertEquals(
            R.string.status_action_failed,
            actionFailureMessage(IllegalStateException("Email/set refused: HTTP 403"), online = true),
        )
    }

    /**
     * The vacuity guard: were the two arms the same resource, every assertion above would hold
     * whatever the branch decided, and the whole file would prove nothing.
     */
    @Test fun `the two sentences are two different resources`() {
        assertNotEquals(R.string.status_action_failed, R.string.status_action_offline)
    }
}
