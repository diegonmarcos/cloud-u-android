package com.diegonmarcos.cloudcalc.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The formula engine through a workbook: every function, references, ranges, cross-sheet, errors. */
class FormulaTest {
    private fun wb(clock: () -> Double = { 45000.5 }) = Workbook(clock)
    private fun Workbook.f(formula: String): String {
        set("Sheet1", 25, 99, formula)
        return display("Sheet1", 25, 99)
    }

    @Test fun arithmeticAndPrecedence() {
        val w = wb()
        assertEquals("7", w.f("=1+2*3"))
        assertEquals("9", w.f("=(1+2)*3"))
        assertEquals("8", w.f("=2^3"))
        assertEquals("64", w.f("=2^3^2"))
        assertEquals("4", w.f("=-2^2"))
        assertEquals("0.5", w.f("=2^-1"))
        assertEquals("0.5", w.f("=50%"))
        assertEquals("2.5", w.f("=10/4"))
        assertEquals("5", w.f("=10-3-2"))
        assertEquals("2", w.f("=--2"))
        assertEquals("3", w.f("= 1 + 2 "))
    }

    @Test fun textAndComparison() {
        val w = wb()
        assertEquals("ab", w.f("=\"a\"&\"b\""))
        assertEquals("x1", w.f("=\"x\"&1"))
        assertEquals("a\"b", w.f("=\"a\"\"b\""))
        assertEquals("TRUE", w.f("=1<2"))
        assertEquals("FALSE", w.f("=1=2"))
        assertEquals("TRUE", w.f("=1<>2"))
        assertEquals("TRUE", w.f("=2>=2"))
        assertEquals("TRUE", w.f("=\"A\"=\"a\""))
        assertEquals("TRUE", w.f("=\"a\">1"))
    }

    @Test fun referencesAbsoluteAndRelative() {
        val w = wb()
        w.set("Sheet1", 0, 0, "5")
        w.set("Sheet1", 1, 0, "=A1*2")
        w.set("Sheet1", 2, 0, "=\$A\$1+B1")
        assertEquals("10", w.display("Sheet1", 1, 0))
        assertEquals("15", w.display("Sheet1", 2, 0))
        w.set("Sheet1", 0, 1, "=a1+1")
        assertEquals("6", w.display("Sheet1", 0, 1))
    }

    @Test fun sumAverageMinMaxCountOverRanges() {
        val w = wb()
        for (i in 0..4) w.set("Sheet1", 0, i, (i + 1).toString())
        w.set("Sheet1", 0, 5, "text")
        assertEquals("15", w.f("=SUM(A1:A6)"))
        assertEquals("21", w.f("=SUM(A1:A5,6)"))
        assertEquals("3", w.f("=AVERAGE(A1:A5)"))
        assertEquals("1", w.f("=MIN(A1:A6)"))
        assertEquals("5", w.f("=MAX(A1:A6)"))
        assertEquals("5", w.f("=COUNT(A1:A6)"))
        assertEquals("6", w.f("=COUNTA(A1:A6)"))
        assertEquals("0", w.f("=MIN(C1:C3)"))
        assertEquals("#DIV/0!", w.f("=AVERAGE(C1:C3)"))
        assertEquals("0", w.f("=SUM(C1:C3)"))
        assertEquals("6", w.f("=SUM(\"1\",2,TRUE,2)"))
    }

    @Test fun logicFunctions() {
        val w = wb()
        assertEquals("yes", w.f("=IF(1<2,\"yes\",\"no\")"))
        assertEquals("no", w.f("=IF(1>2,\"yes\",\"no\")"))
        assertEquals("FALSE", w.f("=IF(1>2,\"yes\")"))
        assertEquals("TRUE", w.f("=AND(1<2,2<3)"))
        assertEquals("FALSE", w.f("=AND(1<2,2>3)"))
        assertEquals("TRUE", w.f("=OR(1>2,2<3)"))
        assertEquals("FALSE", w.f("=OR(1>2,2>3)"))
        assertEquals("FALSE", w.f("=NOT(TRUE)"))
        assertEquals("TRUE", w.f("=NOT(0)"))
        assertEquals("2", w.f("=IF(TRUE;2;3)"))
    }

    @Test fun mathFunctions() {
        val w = wb()
        assertEquals("3.14", w.f("=ROUND(3.14159,2)"))
        assertEquals("3", w.f("=ROUND(2.5,0)"))
        assertEquals("-3", w.f("=ROUND(-2.5,0)"))
        assertEquals("1200", w.f("=ROUND(1234,-2)"))
        assertEquals("3", w.f("=ABS(-3)"))
        assertEquals("4", w.f("=SQRT(16)"))
        assertEquals("#VALUE!", w.f("=SQRT(-1)"))
        assertEquals("8", w.f("=POWER(2,3)"))
        assertEquals("1", w.f("=MOD(10,3)"))
        assertEquals("2", w.f("=MOD(-10,3)"))
        assertEquals("#DIV/0!", w.f("=MOD(5,0)"))
    }

    @Test fun textFunctions() {
        val w = wb()
        assertEquals("abc", w.f("=CONCAT(\"a\",\"b\",\"c\")"))
        assertEquals("a1", w.f("=CONCATENATE(\"a\",1)"))
        assertEquals("5", w.f("=LEN(\"hello\")"))
        assertEquals("ABC", w.f("=UPPER(\"abc\")"))
        assertEquals("abc", w.f("=LOWER(\"ABC\")"))
        w.set("Sheet1", 0, 0, "x")
        w.set("Sheet1", 0, 1, "y")
        assertEquals("xy", w.f("=CONCAT(A1:A2)"))
    }

    @Test fun dateFunctions() {
        val w = wb { 45000.5 }
        assertEquals("45000", w.f("=TODAY()"))
        assertEquals("45000.5", w.f("=NOW()"))
        w.set("Sheet1", 0, 0, "=TODAY()")
        w.setFormat("Sheet1", Area(0, 0, 0, 0), Format.DATE)
        assertEquals("2023-03-15", w.display("Sheet1", 0, 0))
    }

    @Test fun errors() {
        val w = wb()
        assertEquals("#DIV/0!", w.f("=1/0"))
        assertEquals("#NAME?", w.f("=NOPE(1)"))
        assertEquals("#NAME?", w.f("=foo"))
        assertEquals("#VALUE!", w.f("=\"a\"+1"))
        assertEquals("#VALUE!", w.f("=1+"))
        assertEquals("#VALUE!", w.f("=A1:B2"))
        assertEquals("#REF!", w.f("=Missing!A1"))
        assertEquals("#REF!", w.f("=#REF!+1"))
        assertEquals("#VALUE!", w.f("=ABS()"))
        w.set("Sheet1", 0, 0, "=1/0")
        assertEquals("#DIV/0!", w.f("=A1+1"))
        assertEquals("#DIV/0!", w.f("=SUM(A1:A2)"))
    }

    @Test fun circularReferences() {
        val w = wb()
        w.set("Sheet1", 0, 0, "=A1")
        assertEquals("#CIRC!", w.display("Sheet1", 0, 0))
        w.set("Sheet1", 1, 0, "=C1")
        w.set("Sheet1", 2, 0, "=B1+1")
        assertEquals("#CIRC!", w.display("Sheet1", 1, 0))
        assertEquals("#CIRC!", w.display("Sheet1", 2, 0))
        w.set("Sheet1", 2, 0, "7")
        assertEquals("7", w.display("Sheet1", 1, 0))
    }

    @Test fun recalculationFollowsTheDependencyChain() {
        val w = wb()
        w.set("Sheet1", 0, 0, "1")
        w.set("Sheet1", 1, 0, "=A1+1")
        w.set("Sheet1", 2, 0, "=B1+1")
        w.set("Sheet1", 3, 0, "=SUM(A1:C1)")
        assertEquals("6", w.display("Sheet1", 3, 0))
        w.set("Sheet1", 0, 0, "10")
        assertEquals("12", w.display("Sheet1", 2, 0))
        assertEquals("33", w.display("Sheet1", 3, 0))
        w.set("Sheet1", 0, 0, "")
        assertEquals("3", w.display("Sheet1", 3, 0))
    }

    @Test fun crossSheetReferences() {
        val w = wb()
        w.addSheet("Data")
        w.addSheet("My Sheet")
        w.set("Data", 0, 0, "4")
        w.set("Data", 0, 1, "6")
        w.set("My Sheet", 0, 0, "100")
        w.set("Sheet1", 0, 0, "=Data!A1*2")
        w.set("Sheet1", 1, 0, "=SUM(Data!A1:A2)")
        w.set("Sheet1", 2, 0, "='My Sheet'!A1+1")
        assertEquals("8", w.display("Sheet1", 0, 0))
        assertEquals("10", w.display("Sheet1", 1, 0))
        assertEquals("101", w.display("Sheet1", 2, 0))
        w.set("Data", 0, 0, "5")
        assertEquals("10", w.display("Sheet1", 0, 0))
        assertEquals("11", w.display("Sheet1", 1, 0))
    }

    @Test fun parserRoundTripsFormulas() {
        val src = listOf("=A1+B2*3", "=SUM(A1:B5)", "=\$A\$1&\"x\"", "=Data!A1", "='My Sheet'!B2:C3", "=-(1+2)", "=(1+2)^2", "=1-(2-3)", "=IF(A1>1,\"a\",\"b\")", "=50%")
        for (s in src) {
            val n = Formula.parse(s)
            assertEquals(s, n, Formula.parse(Formula.render(n)))
        }
        assertEquals("=A1+B2*3", Formula.render(Formula.parse("= A1 + B2 * 3")))
        assertEquals("=(1+2)*3", Formula.render(Formula.parse("=(1+2)*3")))
    }

    @Test fun shiftMovesOnlyRelativeParts() {
        assertEquals("=B2+C3", Formula.shift("=A1+B2", 1, 1))
        assertEquals("=\$A\$1+C3", Formula.shift("=\$A\$1+B2", 1, 1))
        assertEquals("=A2+\$B3", Formula.shift("=A1+\$B2", 0, 1))
        assertEquals("=SUM(B2:C3)", Formula.shift("=SUM(A1:B2)", 1, 1))
        assertEquals("=#REF!", Formula.shift("=A1", 0, -1))
        assertEquals("plain", Formula.shift("plain", 3, 3))
    }

    @Test fun badFormulaDoesNotThrow() {
        assertTrue(Formula.parseOrNull("=1+") == null)
        assertTrue(Formula.parseOrNull("=(1") == null)
        assertTrue(Formula.parseOrNull("=\"abc") == null)
        assertTrue(Formula.parseOrNull("=1 2") == null)
    }
}
