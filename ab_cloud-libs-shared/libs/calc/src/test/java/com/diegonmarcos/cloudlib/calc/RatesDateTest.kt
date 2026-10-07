package com.diegonmarcos.cloudlib.calc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The date shown is the fetched file's own date, and a replaced file moves it. */
class RatesDateTest {
    private fun ecb(day: String) = """<gesmes:Envelope><Cube><Cube time='$day'><Cube currency='USD' rate='1.17'/></Cube></Cube></gesmes:Envelope>"""

    @Test fun `the ECB file's own date is read`() {
        assertEquals(1_791_244_800L, RatesDate.parseEcb(ecb("2026-10-06")))
        assertEquals(0L, RatesDate.parseEcb("<nothing/>"))
    }

    @Test fun `a fetch that replaces the file moves the date, a missing file falls back`() {
        val f = File.createTempFile("eurofxref", ".xml").apply { writeText(ecb("2026-10-05")); deleteOnExit() }
        val src = listOf("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml" to f.path)
        val before = RatesDate.of(src, 7L)
        f.writeText(ecb("2026-10-06"))
        val after = RatesDate.of(src, 7L)
        assertEquals(86_400L, after - before)
        assertEquals(7L, RatesDate.of(listOf(src[0].first to "/nonexistent/x.xml"), 7L))
        assertEquals(7L, RatesDate.of(listOf("https://example.org/r.xml" to f.path), 7L))
    }
}
