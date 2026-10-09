package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ControlStatus.Action
import com.diegonmarcos.superapp.adbdebug.ControlStatus.Channel
import com.diegonmarcos.superapp.adbdebug.ControlStatus.ChannelStatus
import com.diegonmarcos.superapp.adbdebug.ControlStatus.Tri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlStatusTest {

    private fun r(c: Int?, e: Int?, a: Int?) = ControlStatus.Reading(c, e, a)

    @Test fun playProtectStatusMapping() {
        assertEquals(Tri.UNKNOWN, ControlStatus.playProtect(r(null, null, null)))
        assertEquals(Tri.OFF, ControlStatus.playProtect(r(-1, 0, 0)))
        assertEquals(Tri.OFF, ControlStatus.playProtect(r(null, 0, null)))
        assertEquals(Tri.ON, ControlStatus.playProtect(r(null, 1, null)))
        assertEquals(Tri.ON, ControlStatus.playProtect(r(-1, 0, 1)))   // one re-armed key is ON
        assertEquals(Tri.ON, ControlStatus.playProtect(r(1, 0, 0)))
    }

    @Test fun settingsGetOutputParsing() {
        assertNull(ControlStatus.parseSetting("null\n"))
        assertNull(ControlStatus.parseSetting(null))
        assertEquals(0, ControlStatus.parseSetting("0\n"))
        assertEquals(-1, ControlStatus.parseSetting(" -1 "))
    }

    private class Fake(var paired: Boolean, var answer: () -> Pair<String, String?>?) : ControlStatus.Backend {
        var calls = 0
        override fun paired() = paired
        override fun id(): Pair<String, String?>? { calls++; return answer() }
    }
    private val shell = "uid=2000(shell) gid=2000(shell) groups=2000(shell)"

    @Test fun channelUp() {
        val p = ControlStatus.Probe(Fake(true) { "embedded-adb" to shell })
        assertEquals(ChannelStatus(Channel.UP, "embedded-adb"), p.get())
    }

    @Test fun channelDownWhenPairedButNothingAnswers() {
        assertEquals(Channel.DOWN, ControlStatus.Probe(Fake(true) { null }).get().state)
        // connected but not shell-level (or empty output) is not "up"
        assertEquals(Channel.DOWN, ControlStatus.Probe(Fake(true) { "embedded-adb" to "uid=10200(app)" }).get().state)
        assertEquals(Channel.DOWN, ControlStatus.Probe(Fake(true) { "embedded-adb" to null }).get().state)
    }

    @Test fun channelNotPaired() {
        assertEquals(Channel.NOT_PAIRED, ControlStatus.Probe(Fake(false) { null }).get().state)
    }

    @Test fun probeTimesOutInsteadOfHanging() {
        val p = ControlStatus.Probe(Fake(true) { Thread.sleep(5_000); "embedded-adb" to shell }, timeoutMs = 150)
        val t0 = System.nanoTime()
        assertEquals(Channel.DOWN, p.get().state)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun probeIsCachedThenExpires() {
        var now = 0L
        val f = Fake(true) { "embedded-adb" to shell }
        val p = ControlStatus.Probe(f, ttlMs = 5_000, clock = { now })
        p.get(); now = 4_000; p.get()
        assertEquals(1, f.calls)
        now = 6_000; p.get()
        assertEquals(2, f.calls)
        p.invalidate(); assertNull(p.peek()); p.get()
        assertEquals(3, f.calls)
        p.get(force = true)
        assertEquals(4, f.calls)
    }

    @Test fun tapActions() {
        assertEquals(Action.OPEN_SETTINGS, ControlStatus.channelAction(ChannelStatus(Channel.UP, "x"), true))
        assertEquals(Action.RECONNECT, ControlStatus.channelAction(ChannelStatus(Channel.DOWN), true))
        assertEquals(Action.OPEN_SETTINGS, ControlStatus.channelAction(ChannelStatus(Channel.DOWN), false))
        assertEquals(Action.PAIR, ControlStatus.channelAction(ChannelStatus(Channel.NOT_PAIRED), true))
    }
}
