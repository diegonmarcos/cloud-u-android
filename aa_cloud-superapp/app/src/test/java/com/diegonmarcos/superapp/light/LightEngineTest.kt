package com.diegonmarcos.superapp.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1155 Light tool: tile toggle, default light, timer auto-off, strobe cap, persistence, errors. */
class LightEngineTest {
    private class MemKv(val m: MutableMap<String, Any> = mutableMapOf()) : LightKv {
        override fun int(key: String, def: Int) = m[key] as? Int ?: def
        override fun bool(key: String, def: Boolean) = m[key] as? Boolean ?: def
        override fun float(key: String, def: Float) = m[key] as? Float ?: def
        override fun str(key: String, def: String) = m[key] as? String ?: def
        override fun put(values: Map<String, Any>) { m.putAll(values) }
    }
    private class FakeTorch(override val maxLevel: Int = 1, var result: TorchResult = TorchResult.Ok) : Torch {
        var on = false; var level = 0; val edgesOn = ArrayList<Long>(); var clock: FakeClock? = null
        override fun set(on: Boolean, level: Int): TorchResult {
            if (result != TorchResult.Ok) return result
            if (on && !this.on) edgesOn += clock?.t ?: 0L
            this.on = on; if (on) this.level = level; return TorchResult.Ok
        }
    }
    private class FakeClock : LightClock {
        var t = 0L; private var seq = 0
        private class Job(val at: Long, val id: Int, val r: Runnable)
        private val jobs = ArrayList<Job>()
        override fun now() = t
        override fun postDelayed(ms: Long, r: Runnable): Any { val j = Job(t + ms, seq++, r); jobs += j; return j }
        override fun cancel(token: Any) { jobs.remove(token) }
        fun advance(ms: Long) {
            val end = t + ms
            while (true) {
                val j = jobs.filter { it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.id })) ?: break
                jobs.remove(j); t = j.at; j.r.run()
            }
            t = end
        }
    }
    private fun rig(maxLevel: Int = 1, kv: MemKv = MemKv()): Triple<LightEngine, FakeTorch, FakeClock> {
        val c = FakeClock(); val t = FakeTorch(maxLevel).also { it.clock = c }
        return Triple(LightEngine(t, c, kv), t, c)
    }

    @Test fun `tile tap with the default light toggles the rear torch on then off`() {
        val (e, t, _) = rig()
        e.quickTap(); assertTrue(e.state.rearOn); assertTrue(t.on)
        e.quickTap(); assertFalse(e.state.rearOn); assertFalse(t.on)
    }

    @Test fun `default is rear and both maximum`() {
        val (e, _, _) = rig()
        assertEquals(LightTarget.REAR, e.settings.defaultTarget)
        assertEquals(100, e.settings.rearPercent); assertEquals(100, e.settings.frontPercent)
    }

    @Test fun `default light decides what the quick tap turns on`() {
        for ((target, rear, front) in listOf(Triple(LightTarget.REAR, true, false), Triple(LightTarget.FRONT, false, true), Triple(LightTarget.BOTH, true, true))) {
            val (e, t, _) = rig(); e.update { it.copy(defaultTarget = target) }
            e.quickTap()
            assertEquals("$target rear", rear, e.state.rearOn); assertEquals("$target front", front, e.state.frontOn); assertEquals(rear, t.on)
            e.quickTap()
            assertFalse(e.state.anyOn); assertFalse(t.on)
        }
    }

    @Test fun `quick tap rule flips only the default lights`() {
        assertEquals(Pair(false, true), QuickTap.next(true, true, LightTarget.REAR))
        assertEquals(Pair(true, true), QuickTap.next(true, false, LightTarget.FRONT))
        assertEquals(Pair(false, false), QuickTap.next(true, false, LightTarget.BOTH))
    }

    @Test fun `timer auto-off switches everything off when it ends`() {
        val (e, t, c) = rig(); e.update { it.copy(timerOn = true, timerMinutes = 2, defaultTarget = LightTarget.BOTH) }
        e.quickTap(); assertTrue(e.state.timerRunning)
        c.advance(119_000); assertTrue(t.on); assertTrue(e.state.frontOn)
        c.advance(1_500)
        assertFalse(t.on); assertFalse(e.state.anyOn); assertFalse(e.state.timerRunning)
    }

    @Test fun `timer off means the light stays on`() {
        val (e, t, c) = rig(); e.quickTap(); c.advance(3_600_000); assertTrue(t.on)
    }

    @Test fun `leaving the page releases the torch unless a timer runs or keep-on is set`() {
        val (e, t, _) = rig(); e.setRear(true); e.setFront(true); e.onLeave()
        assertFalse(t.on); assertFalse(e.state.frontOn)
        val (e2, t2, _) = rig(); e2.update { it.copy(timerOn = true) }; e2.setRear(true); e2.setFront(true); e2.onLeave()
        assertTrue("timer keeps torch", t2.on); assertFalse("front never survives", e2.state.frontOn)
        val (e3, t3, _) = rig(); e3.update { it.copy(keepOn = true) }; e3.setRear(true); e3.onLeave()
        assertTrue("keep-on keeps torch", t3.on)
        e3.offAll(); assertFalse(t3.on)
    }

    @Test fun `strobe is capped at 3 Hz whatever is asked or stored`() {
        assertEquals(3f, LightLimits.clampHz(50f), 0f); assertEquals(LightLimits.MIN_STROBE_HZ, LightLimits.clampHz(0f), 0f)
        assertTrue(LightLimits.DEFAULT_STROBE_HZ <= 3f)
        val kv = MemKv(mutableMapOf("strobe_hz" to 40f)); assertEquals(3f, LightPrefs.load(kv).strobeHz, 0f)
        val (e, t, c) = rig(kv = kv); e.update { it.copy(mode = LightMode.FLASHING, strobeHz = 99f) }
        assertEquals(3f, e.settings.strobeHz, 0f)
        e.setRear(true); c.advance(10_000)
        assertTrue("flashes in 10 s: ${t.edgesOn.size}", t.edgesOn.size in 25..31)   // at most 3 per second
        assertTrue(t.edgesOn.zipWithNext().all { (a, b) -> b - a >= 333 })
    }

    @Test fun `spotlight is steady and flashing stops with the light`() {
        val (e, t, c) = rig(); e.setRear(true); c.advance(5_000); assertEquals(1, t.edgesOn.size)
        e.update { it.copy(mode = LightMode.FLASHING) }; c.advance(2_000); assertTrue(t.edgesOn.size > 1)
        e.offAll(); val n = t.edgesOn.size; c.advance(5_000); assertEquals(n, t.edgesOn.size); assertFalse(t.on)
    }

    @Test fun `rear levels map where supported and are on-off only otherwise`() {
        assertEquals(1, LightLimits.rearLevel(100, 1)); assertEquals(5, LightLimits.rearLevel(100, 5))
        assertEquals(1, LightLimits.rearLevel(1, 5)); assertEquals(3, LightLimits.rearLevel(50, 5))
        val (e, t, _) = rig(maxLevel = 8); e.update { it.copy(rearPercent = 50) }; e.setRear(true); assertEquals(4, t.level)
        assertEquals(8, e.rearMaxLevel)
    }

    @Test fun `camera in use or torch unavailable is reported and leaves a clean state`() {
        val (e, t, _) = rig(); t.result = TorchResult.InUse; e.quickTap()
        assertFalse(e.state.rearOn); assertEquals(LightEngine.MSG_IN_USE, e.state.error)
        t.result = TorchResult.Unavailable("x"); e.quickTap(); assertEquals(LightEngine.MSG_UNAVAILABLE, e.state.error)
        t.result = TorchResult.Ok; e.quickTap(); assertNull(e.state.error); assertTrue(t.on)
        e.onTorchUnavailable(); assertFalse(e.state.rearOn); assertNotNull(e.state.error)
    }

    @Test fun `settings persist across a new engine`() {
        val kv = MemKv(); val (e, _, _) = rig(kv = kv)
        e.update { it.copy(defaultTarget = LightTarget.BOTH, mode = LightMode.FLASHING, strobeHz = 1.5f, rearPercent = 40,
            frontPercent = 70, timerOn = true, timerMinutes = 12, keepOn = true) }
        val (e2, _, _) = rig(kv = kv)
        assertEquals(e.settings, e2.settings); assertEquals(12, e2.settings.timerMinutes); assertEquals(LightTarget.BOTH, e2.settings.defaultTarget)
        assertEquals(LightSettings(), LightPrefs.load(MemKv(mutableMapOf("default_target" to "garbage", "mode" to "x"))))
    }
}
