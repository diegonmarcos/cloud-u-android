package com.diegonmarcos.cloudcalc.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The status bar's numbers: sum..max, the sample standard deviation, and the confidence intervals of the mean. */
class StatsTest {
    private fun nums(vararg v: Double): List<Value> = v.map { Value.Num(it) }

    @Test fun sumAvgMedianMinMax() {
        val s = Stat.of(nums(4.0, 1.0, 3.0, 2.0))!!
        assertEquals(4, s.n)
        assertEquals(10.0, s.sum, 1e-12); assertEquals(2.5, s.avg, 1e-12)
        assertEquals(2.5, s.median, 1e-12)           // even count: mean of the middle two
        assertEquals(1.0, s.min, 1e-12); assertEquals(4.0, s.max, 1e-12)
        assertEquals(3.0, Stat.of(nums(5.0, 1.0, 3.0))!!.median, 1e-12)   // odd count: the middle one
    }

    @Test fun standardDeviationIsTheSampleOne() {
        // 2,4,4,4,5,5,7,9: population sd is 2, sample sd is sqrt(32/7)
        assertEquals(sqrt(32.0 / 7), Stat.of(nums(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))!!.stdev!!, 1e-12)
        assertEquals(0.0, Stat.of(nums(3.0, 3.0, 3.0))!!.stdev!!, 1e-12)
    }

    @Test fun textBlanksAndBooleansAreIgnoredErrorsAreCountedAndExcluded() {
        val s = Stat.of(listOf(Value.Num(1.0), Value.Str("x"), Value.Blank, Value.Bool(true), Value.Err(Value.DIV0), Value.Num(3.0), Value.Err(Value.REF)))!!
        assertEquals(2, s.n); assertEquals(2, s.errors)
        assertEquals(4.0, s.sum, 1e-12); assertEquals(2.0, s.avg, 1e-12)
    }

    @Test fun noNumberMeansNoStats() {
        assertNull(Stat.of(emptyList()))
        assertNull(Stat.of(listOf(Value.Str("a"), Value.Blank, Value.Err(Value.NAME))))
    }

    @Test fun oneValueHasNoDeviationAndNoInterval() {
        val s = Stat.of(nums(7.0))!!
        assertEquals(1, s.n); assertEquals(7.0, s.sum, 0.0); assertEquals(7.0, s.median, 0.0)
        assertNull(s.stdev); assertTrue(s.ci.isEmpty())
    }

    @Test fun normalQuantilesMatchTheTable() {
        assertEquals(1.959964, Stat.zQuantile(0.975), 1e-5)
        assertEquals(2.575829, Stat.zQuantile(0.995), 1e-5)
        assertEquals(0.974114, Stat.zQuantile(0.835), 1e-5)    // two-sided 67%
        assertEquals(2.241403, Stat.zQuantile(0.98750), 1e-5)  // two-sided 97.5%
        assertEquals(-Stat.zQuantile(0.9), Stat.zQuantile(0.1), 1e-9)
        assertEquals(0.0, Stat.zQuantile(0.5), 1e-9)
    }

    @Test fun studentQuantilesMatchTheTable() {
        assertEquals(12.7062, Stat.tQuantile(0.975, 1), 1e-3)
        assertEquals(2.2281, Stat.tQuantile(0.975, 10), 1e-3)
        assertEquals(2.0452, Stat.tQuantile(0.975, 29), 1e-3)
        assertEquals(3.1693, Stat.tQuantile(0.995, 10), 1e-3)
        assertEquals(-2.2281, Stat.tQuantile(0.025, 10), 1e-3)
    }

    @Test fun smallSamplesUseTAndLargeOnesUseZ() {
        val small = Stat.of(nums(1.0, 2.0, 3.0, 4.0, 5.0))!!      // n=5 -> t, 4 degrees of freedom
        assertEquals("t", small.dist)
        val sem = small.stdev!! / sqrt(5.0)
        val c99 = small.ci.single { it.level == 0.99 }
        assertEquals(3.0 - 4.6041 * sem, c99.low, 1e-3); assertEquals(3.0 + 4.6041 * sem, c99.high, 1e-3)
        assertEquals(listOf(0.67, 0.975, 0.99), small.ci.map { it.level })

        val big = Stat.of((1..40).map { Value.Num(it.toDouble()) })!!  // n=40 -> z
        assertEquals("z", big.dist)
        val semBig = big.stdev!! / sqrt(40.0)
        val c975 = big.ci.single { it.level == 0.975 }
        assertEquals(20.5 - 2.2414 * semBig, c975.low, 1e-3); assertEquals(20.5 + 2.2414 * semBig, c975.high, 1e-3)
    }

    @Test fun theBoundaryIsThirty() {
        assertEquals("t", Stat.of((1..29).map { Value.Num(it.toDouble()) })!!.dist)
        assertEquals("z", Stat.of((1..30).map { Value.Num(it.toDouble()) })!!.dist)
    }

    @Test fun widerConfidenceMeansWiderInterval() {
        val ci = Stat.of(nums(1.0, 4.0, 2.0, 8.0))!!.ci
        assertTrue((ci[0].high - ci[0].low) < (ci[1].high - ci[1].low))
        assertTrue((ci[1].high - ci[1].low) < (ci[2].high - ci[2].low))
        ci.forEach { assertEquals(3.75, (it.low + it.high) / 2, 1e-9) }
    }

    @Test fun ofAreaReadsOnlyTheSelectionAndKeepsTheCellFormat() {
        val w = Workbook { 45000.0 }
        w.set("Sheet1", 0, 0, "10"); w.set("Sheet1", 0, 1, "20"); w.set("Sheet1", 0, 2, "text")
        w.set("Sheet1", 0, 3, "=1/0"); w.set("Sheet1", 1, 0, "99")
        w.setFormat("Sheet1", Area(0, 0, 0, 1), Format.currency("$", 2))
        val s = Stat.ofArea(w, "Sheet1", Area(0, 0, 0, 3))!!
        assertEquals(2, s.n); assertEquals(1, s.errors); assertEquals(30.0, s.sum, 1e-12)
        assertEquals(Format.Kind.CURRENCY, s.format.kind)
        assertNull(Stat.ofArea(w, "Sheet1", Area(5, 5, 6, 6)))
        assertNotNull(Stat.ofArea(w, "Sheet1", Area(0, 0, A1.MAX_COLS - 1, A1.MAX_ROWS - 1)))   // a whole-sheet selection is cheap
    }

    // ── status bar: Count, order, 2-decimal display ──────────────────────────────────────────

    @Test fun countIsNumericCellsOnlyIgnoringTextAndEmpties() {
        val s = Stat.of(listOf(Value.Num(1.0), Value.Str("abc"), Value.Blank, Value.Str(""), Value.Num(-2.5), Value.Bool(false), Value.Err(Value.DIV0), Value.Num(0.0)))!!
        assertEquals(3, s.n)
        val w = Workbook { 45000.0 }
        w.set("Sheet1", 0, 0, "5"); w.set("Sheet1", 0, 1, "hello"); w.set("Sheet1", 0, 3, "7")   // A3 left empty
        assertEquals(2, Stat.ofArea(w, "Sheet1", Area(0, 0, 0, 3))!!.n)
    }

    @Test fun countComesImmediatelyBeforeStdev() {
        val ids = Stat.barIds(Stat.of(nums(1.0, 2.0, 3.0))!!)
        val i = ids.indexOf("count")
        assertTrue(i >= 0)
        assertEquals("stdev", ids[i + 1])
        assertEquals(listOf("sum", "avg", "median", "count", "stdev", "min", "max"), ids.take(7))
        assertTrue("count" in Stat.barIds(Stat.of(nums(9.0))!!))   // one value: no StDev, Count still shown
    }

    @Test fun statsDisplayRoundsToTwoDecimals() {
        assertEquals("3.14", Fmt.stat(3.14159))
        assertEquals("2.01", Fmt.stat(2.005))          // HALF_UP on the decimal form
        assertEquals("1.5", Fmt.stat(1.50))
        assertEquals("4", Fmt.stat(4.0))
        assertEquals("0", Fmt.stat(-0.001))
        assertEquals("1.5", Fmt.stat(1.5, Format.number(2)))
        assertEquals("$1,234.5", Fmt.stat(1234.5, Format.currency("$", 2)))
        assertEquals("12.35%", Fmt.stat(0.123456, Format.percent(4)))
    }

    @Test fun statsDisplayHandlesLargeAndNegativeNumbers() {
        assertEquals("-2.67", Fmt.stat(-2.6666))
        assertEquals("-1,234,567.89", Fmt.stat(-1234567.891, Format.number(2)))
        assertEquals("123456789012.35", Fmt.stat(123456789012.345))
        assertEquals("1000000000000000", Fmt.stat(1e15))
        val s = Stat.of(nums(-1.0, -2.0, -4.0))!!
        assertEquals("-2.33", Fmt.stat(s.avg)); assertEquals(-7.0 / 3, s.avg, 1e-12)   // the value itself is not rounded
    }
}
