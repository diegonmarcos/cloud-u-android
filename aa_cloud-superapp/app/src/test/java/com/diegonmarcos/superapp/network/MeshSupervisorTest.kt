package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.MeshSupervisor.Net
import com.diegonmarcos.superapp.network.MeshSupervisor.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1120 The reconnect rules, one test per state: connected by default, reconnect until Disconnect, Disconnect sticky. */
class MeshSupervisorTest {
    private val now = 10_000_000L
    private fun input(
        policy: MeshIntent.Policy = MeshIntent.Policy(),
        engine: Boolean = true, up: Boolean = false, key: Boolean = true, consent: Boolean = false,
        net: Net = Net.WIFI, last: Long = 0L, failures: Int = 0,
    ) = MeshSupervisor.Input(policy, engine, up, key, consent, net, now, last, failures)

    @Test fun `the default intent is connected, every start-up condition on`() {
        val p = MeshIntent.Policy()
        assertTrue(p.wanted); assertTrue(p.onBoot); assertTrue(p.onWifi); assertTrue(p.onMobile)
    }

    @Test fun `default state with the tunnel down - it connects`() {
        val d = MeshSupervisor.decide(input())
        assertTrue(d.connect); assertEquals(Why.RECONNECT, d.why)
    }

    @Test fun `connected - nothing to do`() {
        val d = MeshSupervisor.decide(input(up = true)); assertFalse(d.connect); assertEquals(Why.UP, d.why)
    }

    @Test fun `Disconnect is sticky - down, on any network, after any failure count, it never reconnects`() {
        val off = MeshIntent.Policy(wanted = false)
        for (net in Net.values()) for (failures in listOf(0, 1, 9)) {
            val d = MeshSupervisor.decide(input(policy = off, net = net, failures = failures))
            assertFalse("$net/$failures", d.connect); assertEquals(Why.WANTED_OFF, d.why)
        }
    }

    @Test fun `network change - a new network while wanted and down connects at once`() {
        for (net in listOf(Net.WIFI, Net.MOBILE, Net.OTHER)) assertTrue(net.name, MeshSupervisor.decide(input(net = net)).connect)
    }

    @Test fun `no network - waits for one, no attempt`() {
        val d = MeshSupervisor.decide(input(net = Net.NONE)); assertFalse(d.connect); assertEquals(Why.NO_NETWORK, d.why)
    }

    @Test fun `Wi-Fi switched off in Start-up - waits on Wi-Fi, still connects on mobile data`() {
        val p = MeshIntent.Policy(onWifi = false)
        assertEquals(Why.NET_NOT_ALLOWED, MeshSupervisor.decide(input(policy = p, net = Net.WIFI)).why)
        assertTrue(MeshSupervisor.decide(input(policy = p, net = Net.MOBILE)).connect)
    }

    @Test fun `mobile data switched off in Start-up - waits on mobile, still connects on Wi-Fi`() {
        val p = MeshIntent.Policy(onMobile = false)
        assertEquals(Why.NET_NOT_ALLOWED, MeshSupervisor.decide(input(policy = p, net = Net.MOBILE)).why)
        assertTrue(MeshSupervisor.decide(input(policy = p, net = Net.WIFI)).connect)
    }

    @Test fun `no engine, no key and no VPN consent each say why instead of trying`() {
        assertEquals(Why.NO_ENGINE, MeshSupervisor.decide(input(engine = false)).why)
        assertEquals(Why.NO_KEY, MeshSupervisor.decide(input(key = false)).why)
        val c = MeshSupervisor.decide(input(consent = true)); assertFalse(c.connect); assertEquals(Why.NEEDS_CONSENT, c.why)
    }

    @Test fun `a failed attempt backs off 5s 10s 20s and so on, capped at 5 minutes, and retries when the wait is over`() {
        assertEquals(listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L), (0..4).map { MeshSupervisor.backoffMs(it) })
        assertEquals(MeshSupervisor.MAX_MS, MeshSupervisor.backoffMs(30))
        val wait = MeshSupervisor.decide(input(last = now - 1_000, failures = 2))
        assertFalse(wait.connect); assertEquals(Why.BACKOFF, wait.why); assertEquals(now - 1_000 + 10_000, wait.retryAtMs)
        assertTrue("the wait is over", MeshSupervisor.decide(input(last = now - 11_000, failures = 2)).connect)
    }

    @Test fun `reboot - the mesh comes back, unless Connect on boot is off or the owner had disconnected`() {
        assertTrue(MeshSupervisor.wantedAfterBoot(MeshIntent.Policy()))
        assertFalse("Connect on boot off: a reboot is a Disconnect", MeshSupervisor.wantedAfterBoot(MeshIntent.Policy(onBoot = false)))
        assertFalse("a Disconnect survives a reboot", MeshSupervisor.wantedAfterBoot(MeshIntent.Policy(wanted = false)))
    }

    @Test fun `every reason has a sentence`() {
        for (w in Why.values()) assertTrue(w.name, MeshSupervisor.explain(w).isNotBlank())
    }
}
