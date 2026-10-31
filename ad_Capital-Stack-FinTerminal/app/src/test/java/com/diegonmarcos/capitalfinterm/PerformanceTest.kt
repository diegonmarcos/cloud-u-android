package com.diegonmarcos.capitalfinterm

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Known fixtures for the shared performance function: CAGR maths, period bases, edge cases and missing history. */
class PerformanceTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun cagrIsAnnualisedOverMoreThanOneYear() {
        // 100 -> 133.1 over 3 years is exactly 10% a year; simple return would be 33.1%.
        assertEquals(0.10, Performance.cagr(100.0, 133.1, 3.0)!!, 1e-12)
        // doubling over 10 years: 2^(1/10) - 1
        assertEquals(0.0717734625, Performance.cagr(50.0, 100.0, 10.0)!!, 1e-9)
    }

    @Test fun oneYearOrLessIsSimpleReturn() {
        assertEquals(0.331, Performance.cagr(100.0, 133.1, 1.0)!!, 1e-12)
        assertEquals(-0.25, Performance.simple(80.0, 60.0)!!, 1e-12)
    }

    @Test fun badInputsAreMissing() {
        assertNull(Performance.simple(null, 1.0))
        assertNull(Performance.simple(0.0, 1.0))
        assertNull(Performance.simple(1.0, -1.0))
        assertNull(Performance.cagr(100.0, 110.0, 0.0))
        assertEquals("—", Performance.format(null))
        assertEquals("+10.00%", Performance.format(0.1))
        assertEquals("-2.50%", Performance.format(-0.025))
    }

    @Test fun periodBasesUseTheRightClose() {
        // as-of Wednesday 2026-10-28; Friday before is the WTD base, Sep 30 the MTD base, Dec 31 the YTD base.
        val s = listOf(
            d("2016-10-28") to 10.0, d("2023-10-27") to 40.0, d("2025-10-28") to 50.0, d("2025-12-31") to 80.0,
            d("2026-07-28") to 90.0, d("2026-09-28") to 95.0, d("2026-09-30") to 96.0,
            d("2026-10-23") to 99.0, d("2026-10-26") to 98.0, d("2026-10-28") to 100.0,
        )
        val r = Performance.row(s.reversed())
        assertEquals(100.0 / 99.0 - 1, r[0]!!, 1e-12) // WTD
        assertEquals(100.0 / 96.0 - 1, r[1]!!, 1e-12) // MTD
        assertEquals(100.0 / 80.0 - 1, r[2]!!, 1e-12) // YTD
        assertEquals(100.0 / 95.0 - 1, r[3]!!, 1e-12) // 1M: on or before Sep 28
        assertEquals(100.0 / 90.0 - 1, r[4]!!, 1e-12) // 3M
        assertEquals(100.0 / 50.0 - 1, r[5]!!, 1e-12) // 1Y simple
        assertEquals(Math.pow(100.0 / 40.0, 1.0 / 3) - 1, r[6]!!, 1e-12) // 3Y CAGR
        assertEquals(Math.pow(100.0 / 10.0, 0.1) - 1, r[7]!!, 1e-12) // 10Y CAGR
    }

    @Test fun shortHistoryLeavesLongColumnsMissing() {
        val r = Performance.row(listOf(d("2026-06-01") to 50.0, d("2026-10-28") to 100.0))
        assertNull(r[2]) // no close before Jan 1
        assertEquals(1.0, r[4]!!, 1e-12) // 3M base falls back to the first close on or before
        assertNull(r[5]); assertNull(r[6]); assertNull(r[7])
        assertEquals(Performance.COLUMNS.size, r.size)
    }

    @Test fun emptyOrNonFiniteSeriesIsAllMissing() {
        assertEquals(List(8) { null }, Performance.row(emptyList()))
        assertEquals(List(8) { null }, Performance.row(listOf(d("2026-10-28") to Double.NaN)))
    }
}
