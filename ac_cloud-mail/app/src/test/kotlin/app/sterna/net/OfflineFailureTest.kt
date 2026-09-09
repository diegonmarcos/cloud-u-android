package app.sterna.net

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the attachment failure's wording: is this failure the network, and does the
 * offline sentence get shown instead of the exception text?
 */
class OfflineFailureTest {

    // ---- is this failure the transport? ---------------------------------------------------

    @Test fun `an unresolved host is a network failure`() {
        assertTrue(isNetworkFailure(UnknownHostException("Unable to resolve host \"mail.example.org\"")))
    }

    @Test fun `a refused or unroutable connection is a network failure`() {
        assertTrue(isNetworkFailure(ConnectException("Connection refused")))
        assertTrue(isNetworkFailure(NoRouteToHostException("No route to host")))
        assertTrue(isNetworkFailure(SocketException("Software caused connection abort")))
    }

    @Test fun `a timeout is a network failure`() {
        assertTrue(isNetworkFailure(SocketTimeoutException("timeout")))
    }

    @Test fun `a wrapped transport failure is still a network failure`() {
        // The JMAP layer reports its own exception with the transport one as the cause.
        val wrapped = IOException("Upload failed", UnknownHostException("Unable to resolve host"))
        assertTrue(isNetworkFailure(wrapped))
    }

    @Test fun `an unreadable file is not a network failure`() {
        // Reading the picked file shares the try with the upload: this must keep its own message,
        // which is why the rule is narrower than "any IOException".
        assertFalse(isNetworkFailure(FileNotFoundException("/storage/emulated/0/gone.pdf")))
    }

    @Test fun `a rejected certificate is not a network failure`() {
        assertFalse(isNetworkFailure(SSLHandshakeException("Trust anchor for certification path not found")))
    }

    @Test fun `a server refusal is not a network failure`() {
        assertFalse(isNetworkFailure(IllegalStateException("Upload failed: HTTP 413 Payload Too Large")))
    }

    @Test fun `a looping cause chain terminates`() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertFalse(isNetworkFailure(first))
    }

    // ---- which message follows ------------------------------------------------------------

    @Test fun `offline plus a transport failure gets the offline sentence`() {
        assertTrue(isOfflineFailure(UnknownHostException("Unable to resolve host"), online = false))
    }

    @Test fun `a transport failure while connected keeps its technical message`() {
        // Server down, or a VPN killswitch eating the traffic: claiming "you're offline" would lie.
        assertFalse(isOfflineFailure(UnknownHostException("Unable to resolve host"), online = true))
    }

    @Test fun `a non-network failure while offline keeps its technical message`() {
        assertFalse(isOfflineFailure(FileNotFoundException("/storage/emulated/0/gone.pdf"), online = false))
    }
}
