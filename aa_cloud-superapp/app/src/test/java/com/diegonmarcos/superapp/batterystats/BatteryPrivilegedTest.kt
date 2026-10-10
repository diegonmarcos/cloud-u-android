package com.diegonmarcos.superapp.batterystats

import com.diegonmarcos.superapp.battery.BatteryPrivileged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The privileged channel's answer for the stats page: gauge sysfs + per-app power, or nothing. */
class BatteryPrivilegedTest {

    private val out = """
        charge_full=4321000
        charge_full_design=4500000
        cycle_count=318
        --batterystats--
          Estimated power use (mAh):
            Capacity: 4500, Computed drain: 1200, actual drain: 1100-1300
            Global
            UID u0a221: 85.3 ( cpu=50.5 screen=30.0 )
            Uid 1000: 40.1 ( cpu=40.1 )
            UID u10a5: 2.0
            UID u0a221: 4.7 ( wifi=4.7 )
    """.trimIndent()

    @Test fun `sysfs capacity, design and cycles are read, microamp-hours folded to mAh`() {
        val f = BatteryPrivileged.parse(out)!!
        assertEquals(4321, f.fullMah)
        assertEquals(4500, f.designMah)
        assertEquals(318, f.cycles)
        assertEquals(4500, f.statsCapacityMah)
    }

    @Test fun `per-app power is per uid, summed, heaviest first`() {
        val apps = BatteryPrivileged.parse(out)!!.apps
        assertEquals(listOf(10221, 1000, 1010005), apps.map { it.uid })
        assertEquals(90.0, apps.first().mAh, 1e-9)
    }

    @Test fun `no channel answer is no facts, empty nodes are nulls`() {
        assertNull(BatteryPrivileged.parse(null))
        assertNull(BatteryPrivileged.parse(""))
        val f = BatteryPrivileged.parse("charge_full=\ncharge_full_design=\ncycle_count=\n--batterystats--\n")!!
        assertNull(f.fullMah); assertNull(f.designMah); assertNull(f.cycles)
        assertEquals(emptyList<BatteryPrivileged.App>(), f.apps)
        assertEquals(3000, BatteryPrivileged.mah("3000"))
    }
}
