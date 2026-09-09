package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the list says about a refresh that did not land — the decision, executed. */
class RefreshNoticeTest {

    @Test fun aDeviceWithNoNetworkIsToldItIsOffline() {
        // #65's nominal case, with a reporter behind it: this must not change.
        assertEquals(RefreshNotice.OFFLINE, refreshNotice(offline = true, error = null))
    }

    @Test fun aFailedRefreshOnAConnectedDeviceIsNotAnOutage() {
        // The lie this exists to stop. The server refused the request; the phone is on Wi-Fi
        // and reaching it fine. Saying "You're offline" blames the network for the app's problem
        // and leaves the reader retrying for ever.
        assertEquals(
            RefreshNotice.ERROR,
            refreshNotice(offline = false, error = "JMAP method error: requestTooLarge"),
        )
    }

    @Test fun aRejectedPasswordIsNotAnOutageEither() {
        // Every refresh failure went through the same `||`: 401, 403, 500, a TLS failure, a
        // missing folder, any IMAP error. An expired password read as "You're offline".
        assertEquals(
            RefreshNotice.ERROR,
            refreshNotice(offline = false, error = "Email/query failed: HTTP 401 Unauthorized"),
        )
    }

    @Test fun anErrorDuringARealOutageStillReadsAsAnOutage() {
        // During an outage the refresh fails too. The outage is the useful thing to say, and the
        // device has confirmed it.
        assertEquals(
            RefreshNotice.OFFLINE,
            refreshNotice(offline = true, error = "Unable to resolve host"),
        )
    }

    @Test fun aRefreshThatLandedSaysNothing() {
        assertEquals(RefreshNotice.NONE, refreshNotice(offline = false, error = null))
    }
}
