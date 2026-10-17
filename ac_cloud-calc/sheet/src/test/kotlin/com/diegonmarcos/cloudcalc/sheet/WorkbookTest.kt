package com.diegonmarcos.cloudcalc.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet model: structure edits adjust references, sheets, undo, formats, clipboard, files. */
class WorkbookTest {
    private val s1 = "Sheet1"

    private fun small(): Workbook = Workbook { 45000.0 }.also { w ->
        w.set(s1, 0, 0, "1")      // A1
        w.set(s1, 0, 1, "2")      // A2
        w.set(s1, 0, 2, "3")      // A3
        w.set(s1, 1, 0, "=SUM(A1:A3)") // B1
        w.set(s1, 1, 1, "=A2*10")      // B2
    }

    @Test fun insertRowAboveMovesCellsAndAdjustsReferences() {
        val w = small()
        w.insertRows(s1, 1)               // new row 2
        assertEquals("", w.raw(s1, 0, 1))
        assertEquals("2", w.raw(s1, 0, 2))
        assertEquals("=SUM(A1:A4)", w.raw(s1, 1, 0))
        assertEquals("=A3*10", w.raw(s1, 1, 2))
        assertEquals("6", w.display(s1, 1, 0))
        assertEquals("20", w.display(s1, 1, 2))
    }

    @Test fun insertRowBelowTheRangeLeavesItAlone() {
        val w = small()
        w.insertRows(s1, 3)
        assertEquals("=SUM(A1:A3)", w.raw(s1, 1, 0))
    }

    @Test fun deleteRowShrinksRangesAndBreaksDirectReferences() {
        val w = small()
        w.deleteRows(s1, 1)               // delete row 2 (value 2)
        assertEquals("=SUM(A1:A2)", w.raw(s1, 1, 0))
        assertEquals("4", w.display(s1, 1, 0))
        assertEquals("", w.raw(s1, 1, 1))     // B2 sat in the deleted row
    }

    @Test fun deletingARowThatHoldsAReferenceGivesRefError() {
        val w = small()
        w.set(s1, 3, 0, "=A2+1")          // D1 -> A2
        w.deleteRows(s1, 1)
        assertEquals("=#REF!+1", w.raw(s1, 3, 0))
        assertEquals("#REF!", w.display(s1, 3, 0))
    }

    @Test fun insertAndDeleteColumns() {
        val w = small()
        w.set(s1, 3, 0, "=B1+1")          // D1
        w.insertCols(s1, 1)               // new column B
        assertEquals("=SUM(A1:A3)", w.raw(s1, 2, 0))   // moved B1 -> C1; A refs unchanged
        assertEquals("=C1+1", w.raw(s1, 4, 0))
        assertEquals("7", w.display(s1, 4, 0))
        w.deleteCols(s1, 1)               // remove the new B again
        assertEquals("=B1+1", w.raw(s1, 3, 0))
        w.deleteCols(s1, 0)               // remove A: ranges over A are gone
        assertEquals("#REF!", w.display(s1, 0, 0))
    }

    @Test fun structureEditsAdjustReferencesFromOtherSheets() {
        val w = small()
        w.addSheet("Other")
        w.set("Other", 0, 0, "=Sheet1!B1+1")
        w.set("Other", 1, 0, "=A1")       // same-sheet ref: untouched by Sheet1 edits
        w.insertRows(s1, 0)
        assertEquals("=Sheet1!B2+1", w.raw("Other", 0, 0))
        assertEquals("=A1", w.raw("Other", 1, 0))
        assertEquals("7", w.display("Other", 0, 0))
    }

    @Test fun insertShiftsColumnWidthsAndRowHeights() {
        val w = small()
        w.setColWidth(s1, 2, 200)
        w.setRowHeight(s1, 4, 50)
        w.insertCols(s1, 0)
        w.insertRows(s1, 0)
        assertEquals(200, w.find(s1)!!.colWidths[3])
        assertEquals(50, w.find(s1)!!.rowHeights[5])
    }

    @Test fun sheetsAddRenameDuplicateDeleteReorder() {
        val w = small()
        assertEquals("Sheet2", w.addSheet())
        assertEquals(listOf("Sheet1", "Sheet2"), w.sheetNames())
        assertFalse(w.renameSheet("Sheet2", "sheet1"))      // names are unique, case-insensitively
        assertFalse(w.renameSheet("Sheet2", ""))
        assertFalse(w.renameSheet("Sheet2", "a/b"))
        assertTrue(w.renameSheet("Sheet2", "Totals"))
        w.set("Totals", 0, 0, "=Sheet1!B1")
        assertTrue(w.renameSheet(s1, "Data"))
        assertEquals("=Data!B1", w.raw("Totals", 0, 0))
        assertEquals("6", w.display("Totals", 0, 0))
        val dup = w.duplicateSheet("Data")
        assertEquals("Data (copy)", dup)
        assertEquals("6", w.display("Data (copy)", 1, 0))
        assertEquals(listOf("Data", "Data (copy)", "Totals"), w.sheetNames())
        w.moveSheet("Totals", 0)
        assertEquals(listOf("Totals", "Data", "Data (copy)"), w.sheetNames())
        assertTrue(w.deleteSheet("Data"))
        assertEquals("#REF!", w.display("Totals", 0, 0))
        assertEquals("=#REF!", w.raw("Totals", 0, 0))
        assertTrue(w.deleteSheet("Data (copy)"))
        assertFalse(w.deleteSheet("Totals"))                // the last sheet stays
    }

    @Test fun renamingAQuotedSheetKeepsFormulasValid() {
        val w = Workbook { 0.0 }
        w.addSheet("My Data")
        w.set("My Data", 0, 0, "9")
        w.set(s1, 0, 0, "='My Data'!A1+1")
        assertEquals("10", w.display(s1, 0, 0))
        w.renameSheet("My Data", "Plain")
        assertEquals("=Plain!A1+1", w.raw(s1, 0, 0))
        w.renameSheet("Plain", "It's")
        assertEquals("='It''s'!A1+1", w.raw(s1, 0, 0))
        assertEquals("10", w.display(s1, 0, 0))
    }

    @Test fun undoAndRedoRestoreCellsAndSheets() {
        val w = small()
        assertTrue(w.canUndo)
        w.set(s1, 0, 0, "100")
        assertEquals("105", w.display(s1, 1, 0))
        assertTrue(w.undo())
        assertEquals("1", w.raw(s1, 0, 0))
        assertEquals("6", w.display(s1, 1, 0))
        assertTrue(w.redo())
        assertEquals("105", w.display(s1, 1, 0))
        w.addSheet("Extra")
        w.undo()
        assertEquals(listOf(s1), w.sheetNames())
        w.redo()
        assertEquals(listOf(s1, "Extra"), w.sheetNames())
        w.insertRows(s1, 0)
        w.undo()
        assertEquals("=SUM(A1:A3)", w.raw(s1, 1, 0))
        w.set(s1, 5, 5, "x")
        assertFalse(w.canRedo)
    }

    @Test fun batchIsOneUndoStep() {
        val w = Workbook { 0.0 }
        w.batch { for (i in 0..9) w.set(s1, 0, i, i.toString()) }
        assertEquals("9", w.raw(s1, 0, 9))
        w.undo()
        assertEquals("", w.raw(s1, 0, 9))
        assertFalse(w.canUndo)
    }

    @Test fun undoOnAFreshWorkbookDoesNothing() {
        val w = Workbook { 0.0 }
        assertFalse(w.undo())
        assertFalse(w.redo())
    }

    @Test fun numberFormats() {
        val w = Workbook { 0.0 }
        w.set(s1, 0, 0, "1234.5678")
        assertEquals("1234.5678", w.display(s1, 0, 0))
        w.setFormat(s1, Area(0, 0, 0, 0), Format.number(2))
        assertEquals("1,234.57", w.display(s1, 0, 0))
        w.setFormat(s1, Area(0, 0, 0, 0), Format.number(0))
        assertEquals("1,235", w.display(s1, 0, 0))
        w.set(s1, 1, 0, "0.256")
        w.setFormat(s1, Area(1, 0, 1, 0), Format.percent(1))
        assertEquals("25.6%", w.display(s1, 1, 0))
        w.set(s1, 2, 0, "-1234.5")
        w.setFormat(s1, Area(2, 0, 2, 0), Format.currency("$", 2))
        assertEquals("-$1,234.50", w.display(s1, 2, 0))
        w.set(s1, 3, 0, "45000")
        w.setFormat(s1, Area(3, 0, 3, 0), Format.DATE)
        assertEquals("2023-03-15", w.display(s1, 3, 0))
        w.setFormat(s1, Area(3, 0, 3, 0), Format.GENERAL)
        assertEquals("45000", w.display(s1, 3, 0))
    }

    @Test fun typingAPercentOrADateImpliesItsFormat() {
        val w = Workbook { 0.0 }
        w.set(s1, 0, 0, "15%")
        assertEquals(Format.Kind.PERCENT, w.format(s1, 0, 0).kind)
        assertEquals("15%", w.display(s1, 0, 0))
        w.set(s1, 0, 1, "=A1*2")
        assertEquals("0.3", w.display(s1, 0, 1))
        w.set(s1, 1, 0, "2024-02-29")
        assertEquals("2024-02-29", w.display(s1, 1, 0))
        w.set(s1, 2, 0, "'123")
        assertEquals("123", w.display(s1, 2, 0))
        w.set(s1, 2, 1, "=C1+1")          // text that reads as a number still adds
        assertEquals("124", w.display(s1, 2, 1))
    }

    @Test fun generalFormatShowsTenSignificantDigits() {
        assertEquals("0.3333333333", Fmt.general(1.0 / 3))
        assertEquals("100", Fmt.general(100.0))
        assertEquals("-0.5", Fmt.general(-0.5))
        assertEquals("0.3", Fmt.general(0.1 + 0.2))
    }

    @Test fun copyPasteMovesRelativeReferences() {
        val w = small()
        val clip = w.copy(s1, Area(1, 1, 1, 1))      // B2 = A2*10
        val a = w.paste(s1, 3, 3, clip)              // D4 -> C... shifts by (+2,+2): =C4*10
        assertEquals(Area(3, 3, 3, 3), a)
        assertEquals("=C4*10", w.raw(s1, 3, 3))
        w.set(s1, 2, 3, "7")
        assertEquals("70", w.display(s1, 3, 3))
        w.undo()
        w.undo()
        assertEquals("", w.raw(s1, 3, 3))
    }

    @Test fun pasteATwoDimensionalBlock() {
        val w = small()
        val clip = w.copy(s1, Area(0, 0, 1, 1))
        val a = w.paste(s1, 0, 5, clip)
        assertEquals(Area(0, 5, 1, 6), a)
        assertEquals("1", w.raw(s1, 0, 5))
        assertEquals("=SUM(A6:A8)", w.raw(s1, 1, 5))
        assertEquals("=A7*10", w.raw(s1, 1, 6))
    }

    @Test fun tsvFromTheSystemClipboard() {
        val w = Workbook { 0.0 }
        val a = w.pasteText(s1, 1, 1, "a\t1\t=B2*2\nb\t\"x\ty\"\t3\n")
        assertEquals(Area(1, 1, 3, 2), a)
        assertEquals("a", w.raw(s1, 1, 1))
        assertEquals("1", w.raw(s1, 2, 1))
        assertEquals("=B2*2", w.raw(s1, 3, 1))
        assertEquals("x\ty", w.raw(s1, 2, 2))
        val tsv = w.toTsv(s1, Area(1, 1, 3, 2))
        assertEquals("a\t1\t#VALUE!\nb\t\"x\ty\"\t3\n", tsv)
        assertEquals("a\t1\t=B2*2\nb\t\"x\ty\"\t3\n", w.toTsv(s1, Area(1, 1, 3, 2), formulas = true))
    }

    @Test fun clearKeepsTheFormatOfAFormattedCell() {
        val w = Workbook { 0.0 }
        w.set(s1, 0, 0, "5")
        w.setFormat(s1, Area(0, 0, 0, 0), Format.percent(0))
        w.clear(s1, Area(0, 0, 0, 0))
        assertEquals("", w.raw(s1, 0, 0))
        assertEquals(Format.Kind.PERCENT, w.format(s1, 0, 0).kind)
        assertEquals(-1, w.extent(s1).first)
    }

    @Test fun csvRoundTrip() {
        val w = Workbook { 0.0 }
        w.set(s1, 0, 0, "name")
        w.set(s1, 1, 0, "qty")
        w.set(s1, 0, 1, "a,b")
        w.set(s1, 1, 1, "3")
        w.set(s1, 0, 2, "say \"hi\"")
        w.set(s1, 1, 2, "2.5")
        w.set(s1, 0, 3, "two\nlines")
        w.set(s1, 1, 3, "=B2+B3")
        val csv = w.exportCsv(s1)
        assertEquals("name,qty\n\"a,b\",3\n\"say \"\"hi\"\"\",2.5\n\"two\nlines\",5.5\n", csv)
        val v = Workbook { 0.0 }
        v.importCsv(s1, csv)
        for (r in 0..3) for (c in 0..1) assertEquals("cell $c,$r", w.display(s1, c, r), v.display(s1, c, r))
        assertEquals(csv, v.exportCsv(s1))
    }

    @Test fun csvParserEdgeCases() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "")), Csv.parse("a,b\r\nc,\r\n"))
        assertEquals(listOf(listOf("", "")), Csv.parse(","))
        assertEquals(listOf(listOf("x\"y")), Csv.parse("\"x\"\"y\""))
        assertEquals(emptyList<List<String>>(), Csv.parse(""))
        assertEquals(listOf(listOf("a;b", "c")), Csv.parse("\"a;b\";c", ';'))
        assertEquals(listOf(listOf("a", "b")), Csv.parse("﻿a,b"))
    }

    @Test fun importReplacesTheSheet() {
        val w = small()
        w.importCsv(s1, "x,y\n1,2\n")
        assertEquals("2", w.raw(s1, 1, 1))
        assertEquals("y", w.raw(s1, 1, 0))
        assertEquals("", w.raw(s1, 0, 2))
        w.undo()
        assertEquals("=SUM(A1:A3)", w.raw(s1, 1, 0))
    }

    @Test fun workbookJsonRoundTrip() {
        val w = small()
        w.addSheet("Two")
        w.set("Two", 2, 2, "=Sheet1!B1")
        w.setFormat("Two", Area(2, 2, 2, 2), Format.currency("€", 1))
        w.setColWidth("Two", 1, 99)
        w.select(1)
        val json = w.toJson()
        val v = Workbook.fromJson(json)
        assertNotNull(v)
        v!!
        assertEquals(listOf(s1, "Two"), v.sheetNames())
        assertEquals(1, v.active)
        assertEquals("=Sheet1!B1", v.raw("Two", 2, 2))
        assertEquals("€6.0", v.display("Two", 2, 2))
        assertEquals(99, v.find("Two")!!.colWidths[1])
        assertEquals(json, v.toJson())
        assertFalse(v.canUndo)
    }

    @Test fun loadingGarbageChangesNothing() {
        val w = small()
        assertFalse(w.load("not json"))
        assertFalse(w.load("{\"format\":\"other\"}"))
        assertEquals("1", w.raw(s1, 0, 0))
        assertNull(Workbook.fromJson("[]"))
    }

    @Test fun extentIgnoresFormatOnlyCells() {
        val w = small()
        assertEquals(1 to 2, w.extent(s1))
        assertEquals(-1 to -1, Workbook { 0.0 }.extent(s1))
    }

    @Test fun columnNames() {
        assertEquals("A", A1.colName(0))
        assertEquals("Z", A1.colName(25))
        assertEquals("AA", A1.colName(26))
        assertEquals("AZ", A1.colName(51))
        assertEquals("XFD", A1.colName(16383))
        assertEquals(16383, A1.parseCol("XFD"))
        assertEquals(Area(0, 0, 2, 4), A1.parseArea("c5:A1"))
        assertEquals(Area(1, 1, 1, 1), A1.parseArea("\$B\$2"))
        assertNull(A1.parseArea("A0"))
        assertNull(A1.parseArea("hello"))
    }

    @Test fun structureEditsShiftAbsoluteAndMixedReferences() {
        val w = Workbook { 45000.0 }
        w.set(s1, 0, 0, "5"); w.set(s1, 0, 1, "7")
        w.set(s1, 2, 0, "=A\$1+\$A\$2+\$A2")       // C1
        w.insertRows(s1, 0, 2)                 // two rows above everything
        assertEquals("=A\$3+\$A\$4+\$A4", w.raw(s1, 2, 2))
        assertEquals("19", w.display(s1, 2, 2))
        w.insertCols(s1, 0)                    // a column left of A
        assertEquals("=B\$3+\$B\$4+\$B4", w.raw(s1, 3, 2))
        w.deleteRows(s1, 3)                    // the row of the 7 (B4) is gone
        assertEquals("=B\$3+#REF!+#REF!", w.raw(s1, 3, 2))
        assertEquals("#REF!", w.display(s1, 3, 2))
        assertTrue(w.undo())
        assertEquals("=B\$3+\$B\$4+\$B4", w.raw(s1, 3, 2))
    }

    @Test fun clipboardTsvCutAndPasteAtAnchor() {
        val w = small()
        assertEquals("1\t6\n2\t20", w.toTsv(s1, Area(0, 0, 1, 1)).trimEnd())
        assertEquals("1\t=SUM(A1:A3)\n2\t=A2*10", w.toTsv(s1, Area(0, 0, 1, 1), formulas = true).trimEnd())
        val clip = w.copy(s1, Area(0, 0, 0, 1))
        w.clear(s1, Area(0, 0, 0, 1))
        assertEquals("", w.raw(s1, 0, 0)); assertEquals("", w.raw(s1, 0, 1))
        val a = w.paste(s1, 4, 5, clip)
        assertEquals(Area(4, 5, 4, 6), a)
        assertEquals("1", w.raw(s1, 4, 5)); assertEquals("2", w.raw(s1, 4, 6))
        val t = w.pasteText(s1, 2, 2, "a\tb\nc\td")
        assertEquals(Area(2, 2, 3, 3), t)
        assertEquals("d", w.raw(s1, 3, 3))
    }
}
