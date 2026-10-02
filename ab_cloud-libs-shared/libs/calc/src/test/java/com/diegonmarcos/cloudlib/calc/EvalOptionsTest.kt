package com.diegonmarcos.cloudlib.calc

import org.junit.Assert.assertEquals
import org.junit.Test

/** The option parser is the engine's trust boundary: every value a client sends lands in range. */
class EvalOptionsTest {
    @Test fun `absent or blank options are the defaults`() {
        assertEquals(EvalOptions(), EvalOptions.parse(null))
        assertEquals(EvalOptions(), EvalOptions.parse(""))
        assertEquals(EvalOptions(), EvalOptions.parse("{}"))
    }

    @Test fun `declared bases pass, any other base falls back to decimal`() {
        val o = EvalOptions.parse("""{"in_base":16,"out_base":2}""")
        assertEquals(16, o.inBase)
        assertEquals(2, o.outBase)
        assertEquals(10, EvalOptions.parse("""{"in_base":7}""").inBase)
        assertEquals(10, EvalOptions.parse("""{"out_base":36}""").outBase)
    }

    @Test fun `precision, angle and timeout are clamped`() {
        assertEquals(1, EvalOptions.parse("""{"precision":0}""").precision)
        assertEquals(EvalOptions.MAX_PRECISION, EvalOptions.parse("""{"precision":5000}""").precision)
        assertEquals(3, EvalOptions.parse("""{"angle":9}""").angle)
        assertEquals(0, EvalOptions.parse("""{"angle":-4}""").angle)
        assertEquals(2, EvalOptions.parse("""{"approx":7}""").approx)
        assertEquals(0, EvalOptions.parse("""{"approx":-1}""").approx)
        assertEquals(EvalOptions.MAX_TIMEOUT_MS, EvalOptions.parse("""{"timeout_ms":999999}""").timeoutMs)
        assertEquals(100, EvalOptions.parse("""{"timeout_ms":1}""").timeoutMs)
    }

    @Test fun `flags are read`() {
        val o = EvalOptions.parse("""{"approx":2,"mixed_units":false,"unicode":true,"angle":2}""")
        assertEquals(2, o.approx)
        assertEquals(false, o.mixedUnits)
        assertEquals(true, o.unicode)
        assertEquals(2, o.angle)
    }
}
