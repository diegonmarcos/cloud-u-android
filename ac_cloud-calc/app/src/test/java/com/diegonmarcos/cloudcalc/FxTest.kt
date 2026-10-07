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

    @Test fun `a matrix cell is the number to four digits with no unit`() {
        assertEquals("1.163", Fx.cell("1.16342 USD"))
        assertEquals("170.2", Fx.cell("170.2345 JPY"))
        assertEquals("0.006", Fx.cell("0.00600000 EUR"))
        assertEquals("1200", Fx.cell("1,200.0 BRL"))
    }
}
