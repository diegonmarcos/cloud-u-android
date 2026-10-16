package com.diegonmarcos.cloudcalc.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Point-and-click references: what a tap inserts, where, and what replaces what. */
class RefsTest {
    private fun cell(c: Int, r: Int) = Area(c, r, c, r)

    @Test fun refTextIsACellARangeOrASheetQualifiedOne() {
        assertEquals("B3", Refs.ref(cell(1, 2)))
        assertEquals("A1:B5", Refs.ref(Area(0, 0, 1, 4)))
        assertEquals("Sheet2!A1", Refs.ref(cell(0, 0), "Sheet2"))
        assertEquals("'My Sheet'!C2:D3", Refs.ref(Area(2, 1, 3, 2), "My Sheet"))
        assertEquals("'It''s'!A1", Refs.ref(cell(0, 0), "It's"))
    }

    @Test fun aTapInsertsTheAddressAtTheCaret() {
        val e = Refs.insert(Refs.Edit("=", 1), "B2")
        assertEquals("=B2", e.text); assertEquals(3, e.start); assertEquals(3, e.end); assertEquals(1..2, e.last)
        val mid = Refs.insert(Refs.Edit("=+C3", 2), "A1")      // caret between "+" and "C3"
        assertEquals("=+A1C3", mid.text); assertEquals(4, mid.start)
    }

    @Test fun aSecondTapReplacesTheLastInsertedRefUntilSomethingIsTyped() {
        val one = Refs.insert(Refs.Edit("=A1+", 4), "B2")
        assertEquals("=A1+B2", one.text)
        val two = Refs.insert(one, "C3")
        assertEquals("=A1+C3", two.text); assertEquals(4..5, two.last)
        val three = Refs.insert(two, "D44")
        assertEquals("=A1+D44", three.text); assertEquals(4..6, three.last)
        // an operator typed after the ref: the caret moved on and the next tap INSERTS
        val typed = Refs.Edit(three.text + "*", three.text.length + 1, three.text.length + 1, three.last)
        assertNull(Refs.replaceable(typed))
        assertEquals("=A1+D44*E5", Refs.insert(typed, "E5").text)
        // the caret moved away from the ref: no replacement either
        assertNull(Refs.replaceable(three.copy(start = 1, end = 1)))
    }

    @Test fun aSelectionIsReplacedByTheReference() {
        val e = Refs.insert(Refs.Edit("=SUM(xx)", 5, 7), "A1:B5")
        assertEquals("=SUM(A1:B5)", e.text)
    }

    @Test fun crossSheetAndRangeDragReplaceTheSameSpan() {
        var e = Refs.insert(Refs.Edit("=SUM(", 5), Refs.ref(cell(0, 0), "Sheet2"))
        assertEquals("=SUM(Sheet2!A1", e.text)
        // a drag grows the range: each step replaces the previous insertion
        e = Refs.insert(e, Refs.ref(Area(0, 0, 1, 1), "Sheet2"))
        e = Refs.insert(e, Refs.ref(Area(0, 0, 1, 4), "Sheet2"))
        assertEquals("=SUM(Sheet2!A1:B5", e.text)
    }

    @Test fun aTapInsertsOnlyWhereAReferenceCanStand() {
        assertTrue(Refs.canInsert(Refs.Edit("=", 1)))
        assertTrue(Refs.canInsert(Refs.Edit("=SUM(", 5)))
        assertTrue(Refs.canInsert(Refs.Edit("=A1 + ", 6)))
        assertTrue(Refs.canInsert(Refs.Edit("=IF(A1>", 7)))
        assertTrue(Refs.canInsert(Refs.Edit("=SUM(A1,", 8)))
        assertFalse("after a reference an operator is due", Refs.canInsert(Refs.Edit("=A1", 3)))
        assertFalse("after a number", Refs.canInsert(Refs.Edit("=2", 2)))
        assertFalse("not a formula", Refs.canInsert(Refs.Edit("hello +", 7)))
        assertFalse("no text", Refs.canInsert(Refs.Edit("", 0)))
        // right after an inserted ref a tap may still replace it
        assertTrue(Refs.canInsert(Refs.insert(Refs.Edit("=", 1), "A1")))
    }

    @Test fun tokensFindEveryReferenceWithItsSheetAndCells() {
        val t = Refs.tokens("=SUM(A1:B5)+Sheet2!C3*'My Sheet'!\$D\$4+LOG10(2)+\"A1\"")
        assertEquals(listOf("A1:B5", "Sheet2!C3", "'My Sheet'!\$D\$4"), t.map { "=SUM(A1:B5)+Sheet2!C3*'My Sheet'!\$D\$4+LOG10(2)+\"A1\"".substring(it.range) })
        assertEquals(Area(0, 0, 1, 4), t[0].area); assertNull(t[0].sheet)
        assertEquals("Sheet2", t[1].sheet); assertEquals(Area(2, 2, 2, 2), t[1].area)
        assertEquals("My Sheet", t[2].sheet); assertEquals(Area(3, 3, 3, 3), t[2].area)
    }

    @Test fun tokensIgnoreFunctionNamesStringsAndNonFormulas() {
        assertTrue(Refs.tokens("A1+B2").isEmpty())
        assertTrue(Refs.tokens("=ATAN2(1,2)+LOG10(3)").isEmpty())
        assertTrue(Refs.tokens("=\"B2\"&\"C3\"").isEmpty())
        assertEquals(1, Refs.tokens("=\"x\"&A1").size)
    }

    @Test fun tokensNormaliseAReversedRange() {
        assertEquals(Area(0, 0, 1, 4), Refs.tokens("=SUM(B5:A1)").single().area)
    }
}
