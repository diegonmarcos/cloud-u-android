package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.MeshDownModel.Effect
import com.diegonmarcos.superapp.network.MeshDownModel.PeerReading
import com.diegonmarcos.superapp.network.MeshDownModel.Reading
import com.diegonmarcos.superapp.network.MeshDownModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** #1120 When the mesh counts as down, the debounce, the auto-clear and what the alert says. */
class MeshDownModelTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun reading(wanted: Boolean = true, up: Boolean = false, engine: Boolean = true, hs: List<Long?> = listOf(null, null)) =
        Reading(wanted, engine, up, "wg-mesh", listOf("gcp-proxy", "oci-analytics").zip(hs).map { PeerReading(it.first, it.second) }, 300L)

    @Test fun `down means wanted and (tunnel down, or up with every peer stale)`() {
        assertTrue(reading(up = false).down)
        assertTrue(reading(up = true, hs = listOf(null, 900L)).down)
        assertFalse("one fresh peer is enough", reading(up = true, hs = listOf(10L, 900L)).down)
        assertFalse("a deliberate Disconnect is not an outage", reading(wanted = false, up = false).down)
        assertFalse("no engine is not an outage the alert can fix", reading(engine = false).down)
    }

    @Test fun `a brief blip never alerts - back up inside the debounce`() {
        var s = State()
        val (s1, e1) = MeshDownModel.step(s, reading(), 1_000L); s = s1
        assertEquals(Effect.NONE, e1)
        val (s2, e2) = MeshDownModel.step(s, reading(), 1_000L + MeshDownModel.DEBOUNCE_MS - 1); s = s2
        assertEquals(Effect.NONE, e2)
        val (s3, e3) = MeshDownModel.step(s, reading(up = true, hs = listOf(5L, 5L)), 40_000L)
        assertEquals(Effect.NONE, e3); assertEquals(State(), s3)
    }

    @Test fun `still down after the debounce - raised once, then quiet`() {
        val (a, ea) = MeshDownModel.step(State(), reading(), 1_000L); assertEquals(Effect.NONE, ea)
        val (b, eb) = MeshDownModel.step(a, reading(), 1_000L + MeshDownModel.DEBOUNCE_MS); assertEquals(Effect.RAISE, eb)
        assertEquals(1_000L, b.downSinceMs); assertTrue(b.raised)
        val (c, ec) = MeshDownModel.step(b, reading(), 1_000L + 10 * MeshDownModel.DEBOUNCE_MS); assertEquals(Effect.NONE, ec)
        assertTrue(c.raised); assertEquals("the since-time is kept", 1_000L, c.downSinceMs)
    }

    @Test fun `recovery clears the alert by itself, and only when one was raised`() {
        val raised = State(1_000L, true)
        val (s, e) = MeshDownModel.step(raised, reading(up = true, hs = listOf(5L, 5L)), 200_000L)
        assertEquals(Effect.CLEAR, e); assertEquals(State(), s)
        assertEquals(Effect.NONE, MeshDownModel.step(State(), reading(up = true, hs = listOf(5L, 5L)), 1L).second)
    }

    @Test fun `tapping Disconnect while an alert is up clears it`() {
        assertEquals(Effect.CLEAR, MeshDownModel.step(State(1_000L, true), reading(wanted = false), 5_000L).second)
    }

    @Test fun `the alert names the hub, the peers and since when`() {
        val r = reading(up = false)
        assertEquals("Mesh down - gcp-proxy unreachable", MeshDownModel.title(r))
        // 1_700_000_000_000 ms = 2023-11-14 22:13:20 UTC
        val text = MeshDownModel.text(r, 1_700_000_000_000L, utc)
        assertTrue(text, text.contains("wg-mesh") && text.contains("22:13") && text.contains("gcp-proxy, oci-analytics") && text.contains("Reconnect"))
    }

    @Test fun `tunnel up but no peer answers - the text says handshakes, not the tunnel`() {
        val r = reading(up = true, hs = listOf(null, 900L))
        assertEquals("Mesh down - no peer answers", MeshDownModel.title(r))
        assertTrue(MeshDownModel.text(r, 0L, utc).contains("no handshake from gcp-proxy, oci-analytics"))
    }
}
