package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/** Convert ▸ Network / Data over THIS repository's declared units: exact rates, sizes and transfer times. */
class DataUnitsTest {
    private val mode = Declarations.parseModes(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("modes").toString())
        .first { it.kind == "data_converter" }
    private val rate = mode.unitSets.first { it.category == "Data rate" }
    private val size = mode.unitSets.first { it.category == "Data size" }

    private fun conv(set: Declarations.UnitSet, x: String, from: String, to: String): String =
        DataUnits.plain(DataUnits.convert(BigDecimal(x), set.unit(from)!!, set.unit(to)!!))
    private fun r(x: String, from: String, to: String) = conv(rate, x, from, to)
    private fun s(x: String, from: String, to: String) = conv(size, x, from, to)
    private fun time(x: String, su: String, y: String, ru: String) =
        DataUnits.seconds(BigDecimal(x), size.unit(su)!!, BigDecimal(y), rate.unit(ru)!!)!!

    @Test fun `the declaration holds every unit asked for, opens on Mbps to MB per s, and pins its favourites`() {
        assertEquals(listOf("bit/s", "kbit/s", "Mbit/s", "Gbit/s", "Tbit/s", "Kibit/s", "Mibit/s", "Gibit/s",
            "B/s", "kB/s", "MB/s", "GB/s", "TB/s", "KiB/s", "MiB/s", "GiB/s"), rate.units.map { it.name })
        assertEquals(listOf("bit", "kbit", "Mbit", "Gbit", "B", "kB", "MB", "GB", "TB", "PB", "KiB", "MiB", "GiB", "TiB", "PiB"), size.units.map { it.name })
        assertEquals("Mbit/s" to "MB/s", rate.from to rate.to)
        assertEquals(mapOf("value" to "100", "category" to "Data rate", "from" to "Mbit/s", "to" to "MB/s"), mode.defaults)
        assertEquals("Mbit/s (Mbps)", rate.unit("Mbit/s")!!.label)
        assertEquals("MB/s (megabytes/s)", rate.unit("MB/s")!!.label)
        mode.unitSets.forEach { set ->
            set.units.forEach { u -> assertTrue("${u.name}: label shows the symbol", u.label.startsWith(u.name + " (")) }
            assertEquals(set.units.size, set.units.map { it.name }.toSet().size)
        }
        val (fav, _) = Fx.pinned(rate.units.map { it.name }, mode.favourites)
        assertEquals(listOf("Mbit/s", "MB/s", "Gbit/s", "MiB/s"), fav)
        assertEquals(listOf("GB", "GiB", "MB", "TB"), Fx.pinned(size.units.map { it.name }, mode.favourites).first)
        assertTrue(mode.favourites.all { f -> mode.unitSets.any { it.unit(f) != null } })
    }

    @Test fun `every factor is its prefix exactly - SI times 1000, IEC times 1024, a byte 8 bits`() {
        fun f(set: Declarations.UnitSet, n: String) = set.unit(n)!!.factor
        val k = BigDecimal(1000); val ki = BigDecimal(1024); val eight = BigDecimal(8)
        listOf("", "k", "M", "G", "T").forEachIndexed { i, p ->
            assertEquals(0, f(rate, "${p}bit/s").compareTo(k.pow(i)))
            assertEquals(0, f(rate, "${p}B/s").compareTo(eight * k.pow(i)))
        }
        listOf("Ki", "Mi", "Gi").forEachIndexed { i, p ->
            assertEquals(0, f(rate, "${p}bit/s").compareTo(ki.pow(i + 1)))
            assertEquals(0, f(rate, "${p}B/s").compareTo(eight * ki.pow(i + 1)))
        }
        listOf("k", "M", "G").forEachIndexed { i, p -> assertEquals(0, f(size, "${p}bit").compareTo(k.pow(i + 1))) }
        listOf("", "k", "M", "G", "T", "P").forEachIndexed { i, p -> assertEquals(0, f(size, "${p}B").compareTo(eight * k.pow(i))) }
        listOf("Ki", "Mi", "Gi", "Ti", "Pi").forEachIndexed { i, p -> assertEquals(0, f(size, "${p}B").compareTo(eight * ki.pow(i + 1))) }
    }

    @Test fun `data rates convert exactly, bits against bytes`() {
        assertEquals("12.5", r("100", "Mbit/s", "MB/s"))
        assertEquals("11.920928955078125", r("100", "Mbit/s", "MiB/s"))
        assertEquals("800", r("100", "MB/s", "Mbit/s"))
        assertEquals("125", r("1", "Gbit/s", "MB/s"))
        assertEquals("1000", r("1", "Gbit/s", "Mbit/s"))
        assertEquals("1000000000", r("1", "Gbit/s", "bit/s"))
        assertEquals("1", r("8", "bit/s", "B/s"))
        assertEquals("0.125", r("1", "kbit/s", "kB/s"))
        assertEquals("1.25E-13", r("1", "bit/s", "TB/s"))
    }

    @Test fun `SI and IEC are told apart`() {
        assertEquals("1.024", r("1", "Kibit/s", "kbit/s"))
        assertEquals("1.048576", r("1", "MiB/s", "MB/s"))
        assertEquals("0.95367431640625", r("1", "MB/s", "MiB/s"))
        assertEquals("1.073741824", s("1", "GiB", "GB"))
        assertEquals("0.931322574615478515625", s("1", "GB", "GiB"))
        assertEquals("1024", s("1", "TiB", "GiB"))
        assertEquals("1000", s("1", "TB", "GB"))
        assertEquals("1.125899906842624", s("1", "PiB", "PB"))
    }

    @Test fun `data sizes convert exactly, bits against bytes`() {
        assertEquals("8", s("1", "B", "bit"))
        assertEquals("8000", s("1", "GB", "Mbit"))
        assertEquals("0.125", s("1", "Gbit", "GB"))
        assertEquals("8192", s("1", "KiB", "bit"))
        assertEquals("4000", s("4", "GB", "MB"))
    }

    @Test fun `large values keep every digit`() {
        // 2^64 - 1 bytes, in bits and in PiB: no float, no four-decimal cut.
        assertEquals("147573952589676412920", s("18446744073709551615", "B", "bit"))
        assertEquals("16383.99999999999999911182158029987476766109466552734375", s("18446744073709551615", "B", "PiB"))
        assertEquals("123456789012345678.9", s("123456789012345678.9", "GB", "GB"))
        assertEquals("1.234567890123456789E+41", s("123456789012345678900000000", "PB", "B"))
        // A round trip changes nothing.
        val x = "987654321987654321.123456789"
        assertEquals(x, DataUnits.plain(DataUnits.convert(DataUnits.convert(BigDecimal(x), size.unit("TiB")!!, size.unit("kB")!!), size.unit("kB")!!, size.unit("TiB")!!)))
    }

    @Test fun `typed numbers parse with separators and a decimal comma, and anything else is not one`() {
        assertEquals(0, DataUnits.parse(" 1 000 000 ")!!.compareTo(BigDecimal(1_000_000)))
        assertEquals(0, DataUnits.parse("1,000,000.5")!!.compareTo(BigDecimal("1000000.5")))
        assertEquals(0, DataUnits.parse("12,5")!!.compareTo(BigDecimal("12.5")))
        assertEquals(0, DataUnits.parse("1e9")!!.compareTo(BigDecimal("1000000000")))
        assertEquals(0, DataUnits.parse("-3")!!.compareTo(BigDecimal(-3)))
        assertNull(DataUnits.parse(""))
        assertNull(DataUnits.parse("abc"))
        assertNull(DataUnits.parse("1,2,3"))
        assertNull(DataUnits.parse("2+2"))
    }

    @Test fun `transfer time reads in d h min s`() {
        assertEquals(0, time("4", "GB", "100", "Mbit/s").compareTo(BigDecimal(320)))
        assertEquals("5 min 20 s", DataUnits.duration(time("4", "GB", "100", "Mbit/s")))
        assertEquals("320 s", DataUnits.secondsLine(time("4", "GB", "100", "Mbit/s")))
        assertEquals("8 ms", DataUnits.duration(time("1", "MB", "1", "Gbit/s")))
        assertEquals("12.5 s", DataUnits.duration(time("100", "MB", "64", "Mbit/s")))
        assertEquals("", DataUnits.secondsLine(time("100", "MB", "64", "Mbit/s")))
        assertEquals("1 h", DataUnits.duration(BigDecimal(3600)))
        assertEquals("1 h 5 s", DataUnits.duration(BigDecimal(3605)))
        assertEquals("1 d 2 h 3 min 4 s", DataUnits.duration(BigDecimal(93784)))
        assertEquals("1 min", DataUnits.duration(BigDecimal("59.999")))
        assertEquals("1 s", DataUnits.duration(BigDecimal("0.9999999")))
        assertEquals("0.064 ms", DataUnits.duration(BigDecimal("0.000064")))
        assertEquals("< 0.001 ms", DataUnits.duration(BigDecimal("1e-9")))
        assertEquals("0 s", DataUnits.duration(BigDecimal.ZERO))
        // A ratio that does not terminate: 1 GB at 3 Mbit/s = 2666.66... s, to the second and to the millisecond.
        assertEquals("44 min 27 s", DataUnits.duration(time("1", "GB", "3", "Mbit/s")))
        assertEquals("2666.667 s", DataUnits.secondsLine(time("1", "GB", "3", "Mbit/s")))
        // Large: 1 PiB at 1 kbit/s, every day counted.
        assertEquals("104249991 d 8 h 59 min 1 s", DataUnits.duration(time("1", "PiB", "1", "kbit/s")))
    }

    @Test fun `a transfer needs a rate above zero and a size not below it`() {
        assertNull(DataUnits.seconds(BigDecimal.ONE, size.unit("GB")!!, BigDecimal.ZERO, rate.unit("Mbit/s")!!))
        assertNull(DataUnits.seconds(BigDecimal.ONE, size.unit("GB")!!, BigDecimal(-1), rate.unit("Mbit/s")!!))
        assertNull(DataUnits.seconds(BigDecimal(-1), size.unit("GB")!!, BigDecimal.ONE, rate.unit("Mbit/s")!!))
    }

    @Test fun `the transfer chip names the declared size and rate sets and its defaults are in them`() {
        val t = mode.transfer!!
        assertTrue(t.category in mode.categories)
        assertEquals(size.category, t.size); assertEquals(rate.category, t.rate)
        assertTrue(size.unit(t.sizeUnit) != null && rate.unit(t.rateUnit) != null)
        assertEquals("4 GB at 100 Mbit/s", "${t.sizeValue} ${t.sizeUnit} at ${t.rateValue} ${t.rateUnit}")
    }
}
