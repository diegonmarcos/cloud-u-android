package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FRED graph CSV parser on a fixture shaped like the real answer. */
class FredCsvTest {
    private val fixture = "observation_date,DGS1MO,DGS10,DGS30\n" +
        "2026-10-26,4.11,5.00,5.31\n" +
        "2026-10-27,4.10,4.99,5.30\r\n" +
        "2026-10-28,.,5.05,\n" +
        "2026-10-29,,,\n"

    @Test fun parsesEachSeriesAndDropsMissingValues() {
        val m = FredCsv.parse(fixture)
        assertEquals(listOf("DGS1MO", "DGS10", "DGS30"), m.keys.toList())
        assertEquals(listOf(Obs("2026-10-26", 4.11), Obs("2026-10-27", 4.10)), m.getValue("DGS1MO"))
        assertEquals(3, m.getValue("DGS10").size)
        assertEquals(Obs("2026-10-28", 5.05), m.getValue("DGS10").last())
        assertEquals(2, m.getValue("DGS30").size)
    }

    @Test fun aSingleSeriesAnswerAndTheOlderDateHeaderParse() {
        val m = FredCsv.parse("DATE,SOFR\n2026-10-28,3.94\n2026-10-29,3.94\n")
        assertEquals(listOf(Obs("2026-10-28", 3.94), Obs("2026-10-29", 3.94)), m.getValue("SOFR"))
        assertEquals(1, FredCsv.parse("﻿observation_date,X\n2026-01-02,1.5\n").getValue("X").size)
    }

    @Test fun anythingElseParsesToNothing() {
        assertTrue(FredCsv.parse("").isEmpty())
        assertTrue(FredCsv.parse("<html><body>429</body></html>").isEmpty())
        assertTrue(FredCsv.parse("PK\u0003\u0004binary").isEmpty())
        assertTrue(FredCsv.parse("observation_date,X\n2026-01-02,abc\n").getValue("X").isEmpty())
    }
}
