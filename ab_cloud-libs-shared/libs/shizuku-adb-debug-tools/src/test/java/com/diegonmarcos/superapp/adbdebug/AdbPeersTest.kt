package com.diegonmarcos.superapp.adbdebug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two apps, one phone: what they may share (nothing but the pairing window) and how each sees the other. */
class AdbPeersTest {

    private val sa = AdbPeers.FLEET[0]
    private val cs = AdbPeers.FLEET[1]
    private val now = 10_000_000L

    @Test fun `the fleet is the SuperApp then Cloud Store, each with its own authority`() {
        assertEquals(listOf("SuperApp", "Cloud Store"), AdbPeers.FLEET.map { it.label })
        assertEquals("com.diegonmarcos.superapp.adbstatus", sa.authority)
        assertEquals("com.diegonmarcos.cloudstore.adbstatus", cs.authority)
        assertEquals("one authority per app", 2, AdbPeers.FLEET.map { it.authority }.toSet().size)
    }

    @Test fun `every connection state has a link and a dot, and an unknown link is unreachable`() {
        for (c in ConnState.values()) assertEquals(c.name, AdbPeers.linkOf(c).name)
        assertEquals(PeerLink.UNREACHABLE, AdbPeers.parseLink(null))
        assertEquals(PeerLink.UNREACHABLE, AdbPeers.parseLink("nonsense"))
        assertEquals(PeerLink.UP, AdbPeers.parseLink("UP"))
        assertEquals(Dot.OK, AdbPeers.dot(PeerLink.UP))
        assertEquals(Dot.WARN, AdbPeers.dot(PeerLink.DEGRADED))
        assertEquals(Dot.BAD, AdbPeers.dot(PeerLink.DOWN))
        assertEquals(Dot.UNKNOWN, AdbPeers.dot(PeerLink.UNREACHABLE))
    }

    @Test fun `an app that holds the pairing window blocks the other, never itself`() {
        val statuses = listOf(PeerStatus(sa, PeerLink.DOWN, pairing = true, pairingSince = now - 1_000), PeerStatus(cs, PeerLink.UP))
        assertEquals(sa, AdbPeers.pairingHeldElsewhere(cs.applicationId, statuses, now)?.peer)
        assertNull("the holder is not refused by its own claim", AdbPeers.pairingHeldElsewhere(sa.applicationId, statuses, now))
    }

    @Test fun `a stale or future claim is not a held window`() {
        val old = PeerStatus(sa, PeerLink.DOWN, true, now - AdbPeers.PAIRING_CLAIM_MS - 1)
        val future = PeerStatus(sa, PeerLink.DOWN, true, now + 5_000)
        val edge = PeerStatus(sa, PeerLink.DOWN, true, now - AdbPeers.PAIRING_CLAIM_MS)
        assertFalse(AdbPeers.holdsPairing(old, now))
        assertFalse(AdbPeers.holdsPairing(future, now))
        assertTrue(AdbPeers.holdsPairing(edge, now))
        assertFalse("no claim, no hold", AdbPeers.holdsPairing(PeerStatus(sa, PeerLink.UP), now))
        assertNull(AdbPeers.pairingHeldElsewhere(cs.applicationId, listOf(old), now))
    }

    @Test fun `an unreachable peer never blocks pairing`() {
        assertNull(AdbPeers.pairingHeldElsewhere(sa.applicationId, listOf(PeerStatus(cs, PeerLink.UNREACHABLE)), now))
    }

    @Test fun `the refusal names who holds the window`() {
        val why = AdbPeers.pairingRefusal(PeerStatus(cs, PeerLink.UP, true, now))
        assertTrue(why, why.startsWith("Cloud Store is pairing"))
        assertNotNull(AdbPeers.title(PeerLink.UNREACHABLE))
    }

    @Test fun `the claim is taken and released`() {
        PairingClaim.release()
        assertFalse(PairingClaim.held)
        PairingClaim.take(42L)
        assertTrue(PairingClaim.held); assertEquals(42L, PairingClaim.since)
        PairingClaim.release()
        assertFalse(PairingClaim.held)
    }
}
