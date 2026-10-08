package com.diegonmarcos.cloudcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Rates freshness, the favourites' order and the matrix cell text. */
class FxTest {
    private fun ms(y: Int, m: Int, d: Int, h: Int) = LocalDate.of(y, m, d).atTime(h, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()

    @Test fun `the expected ECB date is the latest publication that has happened`() {
        // Wed 2026-10-07: before 16:00 CET Tuesday's file, after it Wednesday's.
        assertEquals(LocalDate.of(2026, 10, 6), Fx.expectedDate(ms(2026, 10, 7, 10)))
        assertEquals(LocalDate.of(2026, 10, 7), Fx.expectedDate(ms(2026, 10, 7, 17)))
        // The weekend and Monday morning still wait for Friday's file.
        assertEquals(LocalDate.of(2026, 10, 2), Fx.expectedDate(ms(2026, 10, 4, 12)))
        assertEquals(LocalDate.of(2026, 10, 2), Fx.expectedDate(ms(2026, 10, 5, 9)))
    }

    @Test fun `rates are stale only when older than that publication, or unknown`() {
        assertTrue(Fx.isStale(day(2026, 10, 6), ms(2026, 10, 7, 17)))
        assertFalse(Fx.isStale(day(2026, 10, 7), ms(2026, 10, 7, 17)))
        assertFalse(Fx.isStale(day(2026, 10, 6), ms(2026, 10, 7, 10)))
        assertTrue(Fx.isStale(0L, ms(2026, 10, 7, 10)))
    }

    @Test fun `a refresh that found nothing newer is not repeated within the hour`() {
        Fx.lastAttemptMs = 0L
        val now = ms(2026, 10, 7, 17)
        assertTrue(Fx.shouldRefresh(day(2026, 10, 6), now))
        Fx.lastAttemptMs = now
        assertFalse(Fx.shouldRefresh(day(2026, 10, 6), now + 1000))
        assertTrue(Fx.shouldRefresh(day(2026, 10, 6), now + Fx.RETRY_MS))
        Fx.lastAttemptMs = 0L
    }

    @Test fun `favourites are pinned first in their declared order, only when present`() {
        val (fav, rest) = Fx.pinned(listOf("AUD", "BRL", "CNY", "EUR", "USD"), listOf("USD", "EUR", "BRL", "GBP", "JPY", "CNY"))
        assertEquals(listOf("USD", "EUR", "BRL", "CNY"), fav)
        assertEquals(listOf("AUD"), rest)
    }

    private val perEur = mapOf("EUR" to 1.0, "USD" to 1.2, "BRL" to 6.0, "GBP" to 0.85, "JPY" to 170.0, "CNY" to 8.5)
    private val codes = listOf("USD", "EUR", "BRL", "GBP", "JPY", "CNY")

    @Test fun `the matrix fills every cell from one EUR table, the diagonal is 1, a to b times b to a is 1`() {
        val m = Fx.matrix(codes, perEur)
        assertEquals(36, m.size)
        codes.forEach { a -> codes.forEach { b ->
            val ab = m[a to b]!!; val ba = m[b to a]!!
            assertEquals("$a->$b x $b->$a", 1.0, ab * ba, 1e-9)
            if (a == b) assertEquals(1.0, ab, 0.0)
        } }
        assertEquals(1.0 / 1.2, m["USD" to "EUR"]!!, 1e-12)
        assertEquals(170.0 / 1.2, m["USD" to "JPY"]!!, 1e-9)
        // Triangular consistency: BRL->JPY equals BRL->USD then USD->JPY.
        assertEquals(m["BRL" to "JPY"]!!, m["BRL" to "USD"]!! * m["USD" to "JPY"]!!, 1e-9)
    }

    @Test fun `a currency missing from the table leaves only its own row and column empty`() {
        val m = Fx.matrix(codes, perEur - "BRL")
        assertEquals(null, m["BRL" to "USD"]); assertEquals(null, m["USD" to "BRL"])
        assertEquals(1.0, m["BRL" to "BRL"]!!, 0.0)
        assertEquals(170.0 / 1.2, m["USD" to "JPY"]!!, 1e-9)
    }

    @Test fun `numbers are cut to at most four decimals with trailing zeros stripped, never rounded`() {
        assertEquals("1.4117", Fx.trunc(1.2 / 0.85))
        assertEquals("0.2", Fx.trunc(0.2))
        assertEquals("0.2", Fx.trunc(1.2 / 6.0))
        assertEquals("170", Fx.trunc(170.0))
        assertEquals("0.8333", Fx.trunc(1.0 / 1.2))
        assertEquals("0", Fx.trunc(0.00004))
        assertEquals("-1.5", Fx.trunc(-1.5))
        assertEquals("1.1634 USD", Fx.truncateNumbers("1.16349 USD"))
        assertEquals("100.5 EUR", Fx.truncateNumbers("100.5000 EUR"))
        assertEquals("2 JPY", Fx.truncateNumbers("2.00001 JPY"))
        assertEquals("-0.9999", Fx.truncateNumbers("-0.99999"))
        assertEquals("1234 JPY", Fx.truncateNumbers("1234 JPY"))
        assertEquals(1.1634, Fx.numberIn("≈ 1.1634 USD")!!, 0.0)
        assertEquals(1200.5, Fx.numberIn("1,200.5 BRL")!!, 0.0)
    }
}
