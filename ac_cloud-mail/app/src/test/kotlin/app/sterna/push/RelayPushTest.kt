package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An IMAP account could never obtain a UnifiedPush address: [UnifiedPushManager.ensureRegistered]
 */
class RelayPushTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val url = "https://up.example.org/UP?token=abc"
    private val other = "https://up.example.org/UP?token=zzz"

    private fun state(
        status: UpStatus,
        endpoint: String? = null,
        subscriptionId: String? = null,
        since: Long = now,
        relayRequested: Boolean = false,
        lastDelivery: Long = 0L,
    ) = UpAccountState(
        deviceClientId = "dev-1",
        endpoint = endpoint,
        subscriptionId = subscriptionId,
        status = status,
        statusSinceMillis = since,
        relayRequested = relayRequested,
        lastDeliveryMillis = lastDelivery,
    )

    // ── shouldRegister: the relay gate ──────────────────────────────────────────────────

    @Test fun `an IMAP account nobody asked a relay for never registers, whatever its status`() {
        UpStatus.entries.forEach { status ->
            assertFalse(
                "IMAP without relayRequested must not register in $status",
                RelayPush.shouldRegister(
                    protocol = MailProtocol.IMAP,
                    relayRequested = false,
                    status = status,
                    statusSinceMillis = 0L,
                    now = now,
                ),
            )
        }
    }

    @Test fun `an IMAP account whose owner asked for an address registers once`() {
        assertTrue(
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.NONE, 0L, now),
        )
    }

    @Test fun `a published IMAP relay does not register again`() {
        assertFalse(
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.PUBLISHED, now - minute, now),
        )
    }

    @Test fun `an armed IMAP relay does not register again`() {
        assertFalse(
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.ACTIVE, now - minute, now),
        )
    }

    // ── shouldRegister: the three JMAP windows, unchanged ───────────────────────────────

    @Test fun `a JMAP account that never registered registers`() {
        assertTrue(RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.NONE, 0L, now))
    }

    @Test fun `a failed JMAP account waits out its cooldown`() {
        assertFalse(
            "14 minutes is inside the 15-minute cooldown",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.FAILED, now - 14 * minute, now),
        )
        assertTrue(
            "16 minutes is past it",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.FAILED, now - 16 * minute, now),
        )
    }

    @Test fun `a pending JMAP registration is only retried once it went stale`() {
        assertFalse(
            "1 minute in, the registration is still in flight",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.REGISTERING, now - minute, now),
        )
        assertTrue(
            "3 minutes in, it is stale",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.REGISTERING, now - 3 * minute, now),
        )
    }

    @Test fun `a JMAP account ignores relayRequested entirely`() {
        assertEquals(
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.NONE, 0L, now),
            RelayPush.shouldRegister(MailProtocol.JMAP, true, UpStatus.NONE, 0L, now),
        )
        assertEquals(
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.ACTIVE, now - minute, now),
            RelayPush.shouldRegister(MailProtocol.JMAP, true, UpStatus.ACTIVE, now - minute, now),
        )
    }

    // ── endpointOutcome ─────────────────────────────────────────────────────────────────

    // The mounting carries a subscriptionId ON PURPOSE. Without one the `assertNull` below is
    // satisfied by the input rather than by the rule, and dropping `subscriptionId = null` from the
    // published copy() stays green — which is exactly what it did.
    @Test fun `an IMAP endpoint is published locally and talks to no server`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(
                UpStatus.REGISTERING,
                since = now - minute,
                subscriptionId = "S-stale",
                relayRequested = true,
            ),
            url,
            now,
        )
        assertTrue("expected Relay, got $outcome", outcome is EndpointOutcome.Relay)
        val published = (outcome as EndpointOutcome.Relay).state
        assertEquals(url, published.endpoint)
        assertEquals(UpStatus.PUBLISHED, published.status)
        assertEquals(now, published.statusSinceMillis)
        assertNull("a relay account has no PushSubscription to hold", published.subscriptionId)
        assertTrue("the relay stays requested", published.relayRequested)
        assertEquals("dev-1", published.deviceClientId)
    }

    @Test fun `redelivering the same address does not knock an armed relay back to published`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(UpStatus.ACTIVE, endpoint = url, since = now - 3 * minute, relayRequested = true),
                url,
                now,
            ),
        )
    }

    @Test fun `redelivering the same address to a published relay changes nothing`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(UpStatus.PUBLISHED, endpoint = url, since = now - 3 * minute, relayRequested = true),
                url,
                now,
            ),
        )
    }

    @Test fun `a rotated relay address is published, not ignored`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(UpStatus.ACTIVE, endpoint = other, since = now - 3 * minute, relayRequested = true),
            url,
            now,
        )
        assertTrue("expected Relay, got $outcome", outcome is EndpointOutcome.Relay)
        assertEquals(url, (outcome as EndpointOutcome.Relay).state.endpoint)
        assertEquals(UpStatus.PUBLISHED, outcome.state.status)
    }

    @Test fun `an endpoint that lands after the address was withdrawn is dropped`() {
        // The withdrawal writes NONE and relayRequested = false; a delivery already in flight then
        // arrives with a fresh URL. Publishing it would hand back the address the user just removed.
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(UpStatus.NONE, endpoint = null, since = now, relayRequested = false),
                url,
                now,
            ),
        )
    }

    @Test fun `a JMAP endpoint goes to the subscription path`() {
        assertEquals(
            EndpointOutcome.Subscribe,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.REGISTERING, since = now - minute),
                url,
                now,
            ),
        )
    }

    @Test fun `a JMAP endpoint redelivered onto a live subscription is ignored`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.ACTIVE, endpoint = url, subscriptionId = "S1", since = now - 3 * minute),
                url,
                now,
            ),
        )
    }

    @Test fun `a JMAP endpoint whose verification window lapsed is recreated`() {
        assertEquals(
            EndpointOutcome.Subscribe,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - 3 * minute),
                url,
                now,
            ),
        )
        assertEquals(
            "a fresh VERIFYING is still in flight",
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - minute),
                url,
                now,
            ),
        )
    }

    // ── endpointOutcome: a rotation drops the old address's delivery stamp ───────────────

    @Test fun `a rotated relay address forgets the wake the old address had earned`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(
                UpStatus.ACTIVE,
                endpoint = other,
                since = now - 20 * minute,
                relayRequested = true,
                lastDelivery = now - 5 * minute,
            ),
            url,
            now,
        )
        assertTrue("expected Relay, got $outcome", outcome is EndpointOutcome.Relay)
        val published = (outcome as EndpointOutcome.Relay).state
        assertEquals(url, published.endpoint)
        assertEquals(UpStatus.PUBLISHED, published.status)
        assertEquals(now, published.statusSinceMillis)
        assertEquals(
            "the old address's last delivery is not this address's",
            0L,
            published.lastDeliveryMillis,
        )
    }

    @Test fun `publishing an address arms nothing by itself`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(UpStatus.REGISTERING, since = now - minute, relayRequested = true),
            url,
            now,
        )
        val published = (outcome as EndpointOutcome.Relay).state
        assertEquals("an address nobody posted to is PUBLISHED, not ACTIVE", UpStatus.PUBLISHED, published.status)
        assertEquals("nothing was ever delivered through it", 0L, published.lastDeliveryMillis)
    }

    @Test fun `redelivering the same address to a relay that has been posted to is ignored`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(
                    UpStatus.ACTIVE,
                    endpoint = url,
                    since = now - 20 * minute,
                    relayRequested = true,
                    lastDelivery = now - 5 * minute,
                ),
                url,
                now,
            ),
        )
    }

    // ── onDelivery: only a real delivery arms a relay ───────────────────────────────────

    @Test fun `the first payload on a published relay arms it and is stamped`() {
        val update = RelayPush.onDelivery(
            state(UpStatus.PUBLISHED, endpoint = url, since = now - 20 * minute, relayRequested = true),
            now,
        )
        assertNotNull("a first delivery must be persisted", update)
        update!!
        assertTrue("PUBLISHED → ACTIVE is the transport change", update.armed)
        assertEquals(UpStatus.ACTIVE, update.state.status)
        assertEquals(now, update.state.lastDeliveryMillis)
        assertEquals("the status changed, so its timestamp moves", now, update.state.statusSinceMillis)
        assertEquals(url, update.state.endpoint)
        assertTrue(update.state.relayRequested)
        assertNull("still no PushSubscription behind a relay", update.state.subscriptionId)
    }

    @Test fun `a later payload restamps an armed relay without announcing a transport change`() {
        val update = RelayPush.onDelivery(
            state(
                UpStatus.ACTIVE,
                endpoint = url,
                since = now - 20 * minute,
                relayRequested = true,
                lastDelivery = now - 5 * minute,
            ),
            now,
        )
        assertNotNull(update)
        update!!
        assertFalse("already armed: rebuilding every connection here would be a per-message storm", update.armed)
        assertEquals(UpStatus.ACTIVE, update.state.status)
        assertEquals(now, update.state.lastDeliveryMillis)
        assertEquals(
            "the status did not change, so its timestamp must not move",
            now - 20 * minute,
            update.state.statusSinceMillis,
        )
    }

    @Test fun `a payload that would change nothing is not written back`() {
        assertNull(
            RelayPush.onDelivery(
                state(
                    UpStatus.ACTIVE,
                    endpoint = url,
                    since = now - 20 * minute,
                    relayRequested = true,
                    lastDelivery = now,
                ),
                now,
            ),
        )
    }

    @Test fun `a JMAP payload never arms anything - only its server verification does`() {
        assertNull(
            RelayPush.onDelivery(
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - minute),
                now,
            ),
        )
        assertNull(
            RelayPush.onDelivery(
                state(UpStatus.ACTIVE, endpoint = url, subscriptionId = "S1", since = now - minute),
                now,
            ),
        )
    }

    @Test fun `an account holding a subscription is not a relay even if a relay was once asked for`() {
        assertNull(
            RelayPush.onDelivery(
                state(
                    UpStatus.VERIFYING,
                    endpoint = url,
                    subscriptionId = "S1",
                    since = now - minute,
                    relayRequested = true,
                ),
                now,
            ),
        )
    }

    @Test fun `a payload on a relay with no published address arms nothing`() {
        listOf(UpStatus.NONE, UpStatus.REGISTERING, UpStatus.FAILED).forEach { status ->
            assertNull(
                "a delivery must not arm a relay in $status",
                RelayPush.onDelivery(state(status, since = now - minute, relayRequested = true), now),
            )
        }
    }

    // ── mayUsePush: the one question the manager asks before it raises any UI ────────────

    @Test fun `only a JMAP account, or one whose owner asked, may touch push at all`() {
        assertTrue("JMAP always may", RelayPush.mayUsePush(MailProtocol.JMAP, false))
        assertTrue(RelayPush.mayUsePush(MailProtocol.JMAP, true))
        assertTrue("an IMAP account that asked for an address may", RelayPush.mayUsePush(MailProtocol.IMAP, true))
        assertFalse(
            "an IMAP account nobody asked a relay for must leave before the distributor picker",
            RelayPush.mayUsePush(MailProtocol.IMAP, false),
        )
    }

    // ── onRelayRequested: a tap is not an automatic loop ─────────────────────────────────

    @Test fun `asking for an address in person clears the failure that would have muzzled it`() {
        // Remove address → unregister → onUnregistered writes FAILED, stamped now. Tapping
        // "Get an address" a second later used to land inside the 15-minute cooldown and do
        // nothing at all, in silence.
        val justFailed = state(UpStatus.FAILED, endpoint = url, since = now, relayRequested = true)
        assertFalse(
            "the automatic path is still held back — that is what the cooldown is for",
            RelayPush.shouldRegister(
                MailProtocol.IMAP, true, justFailed.status, justFailed.statusSinceMillis, now,
            ),
        )
        val asked = RelayPush.onRelayRequested(justFailed, now)
        assertEquals(UpStatus.NONE, asked.status)
        assertEquals(now, asked.statusSinceMillis)
        assertTrue(asked.relayRequested)
        assertTrue(
            "after an explicit request the very next register goes out",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, asked.status, asked.statusSinceMillis, now),
        )
    }

    @Test fun `asking for an address on an account that never had one marks it requested`() {
        val asked = RelayPush.onRelayRequested(state(UpStatus.NONE, since = 0L), now)
        assertTrue(asked.relayRequested)
        assertEquals(UpStatus.NONE, asked.status)
        assertEquals(now, asked.statusSinceMillis)
        assertEquals("dev-1", asked.deviceClientId)
    }

    @Test fun `the cooldown still holds every path that is not a tap`() {
        assertFalse(
            "a FAILED relay one minute in must not re-register by itself",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.FAILED, now - minute, now),
        )
        assertTrue(
            "and it may again once the 15 minutes are up",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.FAILED, now - 16 * minute, now),
        )
    }

    // ── publishedAddress: a dead address is not an address ───────────────────────────────

    @Test fun `a published relay shows its address, and an armed one keeps showing it`() {
        assertEquals(
            url,
            RelayPush.publishedAddress(
                state(UpStatus.PUBLISHED, endpoint = url, relayRequested = true),
            ),
        )
        assertEquals(
            url,
            RelayPush.publishedAddress(
                state(UpStatus.ACTIVE, endpoint = url, relayRequested = true, lastDelivery = now),
            ),
        )
    }

    @Test fun `an address the distributor no longer serves is not shown`() {
        // markFailed and onUnregistered both KEEP the endpoint field; showing it would put a dead
        // address on screen as a live one, ready to be pasted into a relay that posts into a void.
        listOf(UpStatus.NONE, UpStatus.REGISTERING, UpStatus.VERIFYING, UpStatus.FAILED).forEach { status ->
            assertNull(
                "an endpoint in $status is not an address to show",
                RelayPush.publishedAddress(state(status, endpoint = url, relayRequested = true)),
            )
        }
    }

    @Test fun `an address nobody asked for is not shown either`() {
        assertNull(
            "the subsystem enrols a distributor silently, so an endpoint can exist unasked-for",
            RelayPush.publishedAddress(state(UpStatus.PUBLISHED, endpoint = url, relayRequested = false)),
        )
    }

    @Test fun `an account with no record at all has no address`() {
        assertNull(RelayPush.publishedAddress(null))
        assertNull(RelayPush.publishedAddress(state(UpStatus.PUBLISHED, relayRequested = true)))
    }
}
