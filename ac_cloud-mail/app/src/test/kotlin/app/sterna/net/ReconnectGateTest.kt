package app.sterna.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connectivity rule (#65), driven the way the framework drives it: a transport callback for
 */
class ReconnectGateTest {

    /** A gate plus the two things the screen reacts to, so a scenario reads as a story. */
    private class Live(link: Boolean) {
        val gate = ReconnectGate(link)
        var resyncs = 0
            private set

        fun transportUp(handle: Long) {
            if (gate.onTransportAvailable(handle)) resyncs++
        }

        fun transportGone(handle: Long) = gate.onTransportLost(handle)

        fun routeUp(handle: Long) {
            if (gate.onRouteAvailable(handle)) resyncs++
        }

        fun routeGone(handle: Long) = gate.onRouteLost(handle)

        fun blocked(blocked: Boolean) {
            if (gate.onRouteBlocked(blocked)) resyncs++
        }

        fun refreshWorked() = gate.onRequestSucceeded()

        fun refreshDiedOnTheTransport() = gate.onRequestFailed()

        val online: Boolean get() = gate.online.value
    }

    /** A phone that was fully connected when the app started: Wi-Fi up, no VPN. */
    private fun connected(): Live = Live(link = true).apply {
        // What the framework replays at registration for the already-connected networks.
        transportUp(WIFI)
        routeUp(WIFI)
    }

    /** A phone behind an always-on VPN: Wi-Fi underneath, the tunnel as this app's route. */
    private fun tunnelled(): Live = Live(link = true).apply {
        transportUp(WIFI)
        routeUp(TUNNEL)
    }

    // --- The replayed callbacks at registration ---

    @Test fun `the callbacks replayed for an already-connected phone are not a reconnect`() {
        val live = connected()
        assertEquals(0, live.resyncs)
        assertTrue(live.online)
    }

    @Test fun `a phone that comes up with no connectivity resyncs once it has some`() {
        val live = Live(link = false)
        assertFalse(live.online)
        live.transportUp(WIFI)
        assertFalse("a transport without a route is not a link", live.online)
        live.routeUp(WIFI)
        assertTrue(live.online)
        assertEquals(1, live.resyncs)
    }

    // --- Case 1: a plain network drops ---

    @Test fun `plain Wi-Fi off shows offline without waiting for a failed refresh`() {
        val live = connected()
        live.transportGone(WIFI)
        live.routeGone(WIFI)
        assertFalse(live.online)
        assertEquals("nothing to resync while it is down", 0, live.resyncs)
    }

    @Test fun `Wi-Fi off while the app was idle still goes offline`() {
        // The framework never replayed onAvailable, so the transport set is empty: the loss alone
        // has to be enough.
        val live = Live(link = true)
        live.transportGone(WIFI)
        assertFalse(live.online)
    }

    @Test fun `a Wi-Fi to mobile handover is neither an outage nor a reconnect`() {
        val live = connected()
        live.transportUp(MOBILE) // announced before the old one is reported lost
        live.routeUp(MOBILE)
        live.transportGone(WIFI)
        live.routeGone(WIFI) // the stale half of the handover
        assertTrue(live.online)
        assertEquals(0, live.resyncs)
    }

    @Test fun `losing one of two live transports is not going offline`() {
        val live = connected()
        live.transportUp(MOBILE)
        live.transportGone(WIFI)
        assertTrue(live.online)
        assertEquals(0, live.resyncs)
    }

    // --- Case 2: a plain network comes back ---

    @Test fun `plain Wi-Fi back asks for exactly one resync`() {
        val live = connected()
        live.transportGone(WIFI)
        live.routeGone(WIFI)
        live.transportUp(WIFI)
        live.routeUp(WIFI)
        assertEquals(1, live.resyncs)
        assertTrue(live.online)
    }

    @Test fun `the churn of a link coming back crosses the edge once`() {
        val live = connected()
        live.transportGone(WIFI)
        live.routeGone(WIFI)
        // A single reconnection emits a run of callbacks; only one of them is an edge.
        live.transportUp(WIFI)
        live.routeUp(WIFI)
        live.transportUp(WIFI)
        live.blocked(false)
        live.routeUp(WIFI)
        assertEquals(1, live.resyncs)
    }

    @Test fun `flapping asks once per genuine round trip`() {
        val live = connected()
        repeat(3) {
            live.transportGone(WIFI)
            live.routeGone(WIFI)
            live.transportUp(WIFI)
            live.routeUp(WIFI)
        }
        assertEquals(3, live.resyncs)
    }

    // --- Case 3: the tunnel drops (the reporter's phone) ---

    @Test fun `a lockdown killswitch blocking the app is offline though the Wi-Fi is up`() {
        val live = tunnelled()
        // WireGuard off with "block connections without VPN": the transport underneath never moves.
        live.routeGone(TUNNEL)
        live.blocked(true)
        assertFalse(live.online)
        assertEquals(0, live.resyncs)
    }

    @Test fun `a killswitch the framework cannot see is caught by the failed refresh`() {
        val live = tunnelled()
        // The VPN app drops the traffic itself: route and transport both still look healthy.
        assertTrue(live.online)
        live.refreshDiedOnTheTransport()
        assertFalse("what the request lived beats what the framework claims", live.online)
        assertEquals(0, live.resyncs)
    }

    @Test fun `a refresh failing is never a reason to resync`() {
        val live = connected()
        repeat(5) { live.refreshDiedOnTheTransport() }
        assertEquals(0, live.resyncs)
    }

    // --- Case 4: the tunnel comes back ---

    @Test fun `the tunnel coming back resyncs even though the transports never moved`() {
        val live = tunnelled()
        live.routeGone(TUNNEL)
        live.blocked(true)
        live.refreshDiedOnTheTransport()

        live.blocked(false)
        live.routeUp(TUNNEL_AGAIN)
        assertEquals("this is the reported symptom: it used to be zero", 1, live.resyncs)
    }

    @Test fun `the banner keeps saying offline until a refresh actually comes back`() {
        val live = tunnelled()
        live.routeGone(TUNNEL)
        live.blocked(true)
        live.refreshDiedOnTheTransport()
        assertFalse(live.online)

        // The route is back but the tunnel is still handshaking: the first attempts fail.
        live.blocked(false)
        live.routeUp(TUNNEL_AGAIN)
        assertFalse("no 'online' flicker on a link we have not used yet", live.online)
        live.refreshDiedOnTheTransport()
        assertFalse(live.online)
        live.refreshWorked()
        assertTrue(live.online)
    }

    @Test fun `a successful refresh is not itself a resync trigger`() {
        val live = tunnelled()
        live.refreshDiedOnTheTransport()
        live.refreshWorked()
        assertEquals(0, live.resyncs)
    }

    @Test fun `the tunnel coming back without a killswitch does not double up`() {
        // No lockdown: losing the tunnel falls back to Wi-Fi, so the app is never offline and
        // there is nothing to resync.
        val live = tunnelled()
        live.routeUp(WIFI) // handover to the plain route
        live.routeGone(TUNNEL)
        assertTrue(live.online)
        live.routeUp(TUNNEL_AGAIN)
        assertTrue(live.online)
        assertEquals(0, live.resyncs)
    }

    // --- Case 5: the tunnel the framework never saw drop, and never sees come back ---
    //
    // The reporter's configuration. A VPN that blackholes traffic itself (or a lockdown whose
    // blocked flag never reaches us) leaves the link "up" throughout, so the rebuild produces no
    // edge. Before this, the app sat behind a correct offline banner and never tried again: the
    // sync the empty state promises never ran, and neither did the fetch the notifications need.

    @Test fun `a rebuilt tunnel is a reason to try again though the link never went down`() {
        val live = tunnelled()
        live.refreshDiedOnTheTransport() // the killswitch the framework cannot see
        assertFalse(live.online)

        // WireGuard reconnects: a *new* network, the Wi-Fi underneath never moved, no edge.
        live.routeUp(TUNNEL_AGAIN)
        assertEquals("this used to be zero, and nothing ever retried", 1, live.resyncs)
        assertFalse("asking for an attempt is not claiming to be online", live.online)
    }

    @Test fun `only the attempt coming back clears the banner the rebuilt tunnel triggered`() {
        val live = tunnelled()
        live.refreshDiedOnTheTransport()
        live.routeUp(TUNNEL_AGAIN)
        assertEquals(1, live.resyncs)
        assertFalse(live.online)
        live.refreshDiedOnTheTransport() // still handshaking
        assertFalse(live.online)
        live.refreshWorked()
        assertTrue(live.online)
    }

    @Test fun `a new transport under a dead tunnel is a reason to try again`() {
        // Wi-Fi to mobile while the tunnel is blackholing: the tunnel will rebuild over the new
        // transport, and its arrival is the earliest hint there is.
        val live = tunnelled()
        live.refreshDiedOnTheTransport()
        live.transportUp(MOBILE)
        assertEquals(1, live.resyncs)
    }

    @Test fun `the callbacks replayed at registration are not the plumbing moving`() {
        // The view model does `refresh(); connectivity.start()`, and the gate is seeded from the
        // synchronous connectivity read — the latches are up, but no network identity is known yet.
        val live = Live(link = true)
        live.refreshDiedOnTheTransport()
        live.transportUp(WIFI)
        live.routeUp(TUNNEL)
        assertEquals("first sight of a network is not a rebuild", 0, live.resyncs)
        assertFalse(live.online)
    }

    @Test fun `a tunnel rebuilt after that cold start is still a reason to try again`() {
        // The counterpart: ignoring first sight must not cost us the case the rule exists for.
        val live = Live(link = true)
        live.refreshDiedOnTheTransport()
        live.transportUp(WIFI)
        live.routeUp(TUNNEL)
        assertEquals(0, live.resyncs)
        live.routeUp(TUNNEL_AGAIN)
        assertEquals(1, live.resyncs)
    }

    @Test fun `a re-announced network is not the plumbing moving`() {
        // The bound that stops this becoming a poll: the framework re-runs its callbacks freely.
        val live = tunnelled()
        live.refreshDiedOnTheTransport()
        repeat(5) {
            live.routeUp(TUNNEL)
            live.transportUp(WIFI)
            live.blocked(false)
        }
        assertEquals(0, live.resyncs)
    }

    @Test fun `a tunnel rebuilt over and over asks once per rebuild`() {
        val live = tunnelled()
        live.refreshDiedOnTheTransport()
        repeat(3) { live.routeUp(TUNNEL_AGAIN) }
        assertEquals(1, live.resyncs)
    }

    @Test fun `nothing extra is asked for while the requests are coming back`() {
        // The healthy case is untouched: no attempt is bought with anybody's battery.
        val live = tunnelled()
        live.refreshWorked()
        live.routeUp(TUNNEL_AGAIN)
        live.transportUp(MOBILE)
        assertEquals(0, live.resyncs)
        assertTrue(live.online)
    }

    @Test fun `a link that is down is not tried, however much the plumbing churns`() {
        // Under a lockdown killswitch there is no route at all: trying would be pure waste.
        val live = tunnelled()
        live.routeGone(TUNNEL)
        live.blocked(true)
        live.refreshDiedOnTheTransport()
        live.transportUp(MOBILE)
        assertEquals(0, live.resyncs)
        assertFalse(live.online)
    }

    // --- The tunnel outliving a real outage (the connectionless-tunnel guard) ---

    @Test fun `airplane mode under a tunnel that never notices is still offline`() {
        val live = tunnelled()
        // WireGuard's network survives the cycle: no route callback ever fires.
        live.transportGone(WIFI)
        assertFalse(live.online)
        live.transportUp(WIFI)
        assertTrue(live.online)
        assertEquals(1, live.resyncs)
    }

    // --- The published state, in general ---

    @Test fun `online reflects the seed`() {
        assertTrue(Live(link = true).online)
        assertFalse(Live(link = false).online)
    }

    @Test fun `a proven link is still shown offline once the phone says it is gone`() {
        val live = connected()
        live.refreshWorked()
        live.transportGone(WIFI)
        assertFalse(live.online)
    }

    @Test fun `a tunnel that hides from every callback is only cleared by a request`() {
        // The honest limit of the rule, now that a rebuilt tunnel *does* trigger an attempt: a VPN
        // that comes back on the very same network object leaves nothing at all to observe, so the
        // banner stays until a refresh (the user's pull, the next reconnect, the next push wake)
        // comes back — and no timer is burned pretending otherwise.
        val live = tunnelled()
        live.refreshDiedOnTheTransport()
        assertFalse(live.online)
        assertEquals(0, live.resyncs)
        live.refreshWorked()
        assertTrue(live.online)
    }

    // --- The reconnect refresh schedule, and which failure the banner is allowed to show ---

    @Test fun `the first attempt only waits for the settle`() {
        assertEquals(1_500L, ReconnectRefresh.delayBeforeMs(0))
    }

    @Test fun `the gaps between retries widen`() {
        val gaps = (1 until ReconnectRefresh.MAX_TRIES).map { ReconnectRefresh.delayBeforeMs(it) }
        assertEquals(gaps.sorted(), gaps)
        assertTrue(gaps.first() < gaps.last())
    }

    @Test fun `the schedule covers a tunnel that takes twenty seconds to come back up`() {
        val total = (0 until ReconnectRefresh.MAX_TRIES).sumOf { ReconnectRefresh.delayBeforeMs(it) }
        assertTrue("only $total ms of cover", total >= 20_000L)
    }

    @Test fun `the settle is long enough to swallow a burst of callbacks`() {
        // A handover or a tunnel rebuild emits its run of callbacks inside a second.
        assertTrue(ReconnectRefresh.delayBeforeMs(0) >= 1_000L)
    }

    @Test fun `the gap is capped, never unbounded`() {
        val last = ReconnectRefresh.delayBeforeMs(ReconnectRefresh.MAX_TRIES - 1)
        assertEquals(last, ReconnectRefresh.delayBeforeMs(99))
        assertTrue(last <= 12_000L)
    }

    @Test fun `every attempt waits, so the sequence can never spin`() {
        (0..99).forEach { assertTrue(ReconnectRefresh.delayBeforeMs(it) > 0L) }
    }

    @Test fun `a failure with an attempt still to come is not shown`() {
        // The network is back: the banner describes the present, not a refresh we are retrying.
        (0 until ReconnectRefresh.MAX_TRIES - 1).forEach { attempt ->
            assertTrue(ReconnectRefresh.retrying(attempt))
            assertNull(ReconnectRefresh.errorAfterAttempt(attempt, "Connection reset"))
        }
    }

    @Test fun `the last failure is reported honestly`() {
        val last = ReconnectRefresh.MAX_TRIES - 1
        assertFalse(ReconnectRefresh.retrying(last))
        assertEquals("500 Internal Server Error", ReconnectRefresh.errorAfterAttempt(last, "500 Internal Server Error"))
    }

    @Test fun `a successful last attempt leaves no error behind`() {
        assertNull(ReconnectRefresh.errorAfterAttempt(ReconnectRefresh.MAX_TRIES - 1, null))
    }

    @Test fun `more than one attempt is made`() {
        assertTrue(ReconnectRefresh.MAX_TRIES > 1)
    }

    private companion object {
        const val WIFI = 1L
        const val MOBILE = 2L
        const val TUNNEL = 3L
        const val TUNNEL_AGAIN = 4L
    }
}
