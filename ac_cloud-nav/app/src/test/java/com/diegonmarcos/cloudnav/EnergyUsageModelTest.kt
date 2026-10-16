package com.diegonmarcos.cloudnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Battery Usage page's pure parts: the draining-device lines and the duration words. */
class EnergyUsageModelTest {
    @Test fun noSamplesMeansCollecting() {
        assertNull(EnergyUsageModel.drainLines(emptyMap()))
        assertNull(EnergyUsageModel.drainLines(mapOf("samples" to 0, "states" to emptyMap<String, Any>())))
    }

    @Test fun drainLinesAreBaselineThenStatesByMarginalDraw() {
        val attr: Map<String, Any> = mapOf(
            "samples" to 40, "baseline_idle_ma" to 120,
            "states" to mapOf(
                "screen_on" to mapOf("marginal_ma" to 210, "samples" to 12),
                "gps_on" to mapOf("marginal_ma" to 330, "samples" to 5),
                "wifi_off" to mapOf("marginal_ma" to -20, "samples" to 3),
                "never_seen" to mapOf("marginal_ma" to 999, "samples" to 0),
            ),
        )
        assertEquals(
            listOf(
                EnergyLine("Idle baseline", "120 mA"),
                EnergyLine("gps on", "+330 mA   (5)"),
                EnergyLine("screen on", "+210 mA   (12)"),
                EnergyLine("wifi off", "-20 mA   (3)"),
            ),
            EnergyUsageModel.drainLines(attr),
        )
    }

    @Test fun words() {
        assertEquals("45s", EnergyUsageModel.fmtDur(45_000)); assertEquals("5m", EnergyUsageModel.fmtDur(300_000)); assertEquals("2h 3m", EnergyUsageModel.fmtDur(123 * 60_000L))
        assertEquals("just now", EnergyUsageModel.fmtAgo(1_000, now = 30_000)); assertEquals("10m ago", EnergyUsageModel.fmtAgo(0, now = 600_000)); assertEquals("1h 5m ago", EnergyUsageModel.fmtAgo(0, now = 65 * 60_000L))
        assertEquals("512 B", EnergyUsageModel.sizeStr(512)); assertEquals("1.0 KB", EnergyUsageModel.sizeStr(1024)); assertEquals("2.0 MB", EnergyUsageModel.sizeStr(2L shl 20))
    }
}
