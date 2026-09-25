package com.diegonmarcos.clouddrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PDF converter (#458, on pdfium since #577) is pure functions over per-page text, so it is
 * executed here on the CI runner instead of being argued from a grep. Each expected string below
 * is what lands in the user's file: a wrong quote in the csv or an unescaped angle bracket in the
 * html is a corrupted export, not a cosmetic bug.
 */
class PdfConvertTest {

    @Test
    fun cleanPageJoinsLinesAndDropsTrailingBlanks() {
        // pdfium ends lines with CR LF and pads glyph advances with spaces.
        assertEquals("a\nb\n", PdfConvert.cleanPage("a  \r\nb\t\r\n"))
        assertEquals("x\ny", PdfConvert.cleanPage("x\ry"))
    }

    @Test
    fun aScanHasNoText() {
        assertTrue(PdfConvert.hasNoText(listOf("", "  \n", "\t")))
        assertTrue(PdfConvert.hasNoText(emptyList()))
        assertFalse(PdfConvert.hasNoText(listOf("", "x")))
    }

    @Test
    fun theRefusalSaysWhy() {
        assertTrue(PdfConvert.NO_TEXT_LAYER_MESSAGE.contains("no text layer"))
        assertTrue(PdfConvert.NO_TEXT_LAYER_MESSAGE.contains("nothing was written"))
    }

    @Test
    fun onlyTheFourHonestTargetsAreOffered() {
        assertEquals(listOf("txt", "md", "html", "csv"), PdfConvert.TARGETS)
    }

    @Test
    fun plainTextSeparatesPagesWithABlankLine() {
        assertEquals("one\n\ntwo\n", PdfConvert.build(listOf("one", "two"), "txt", "a.pdf"))
        assertEquals("", PdfConvert.build(emptyList(), "txt", "a.pdf"))
    }

    @Test
    fun markdownNumbersEveryPage() {
        assertEquals(
            "## Page 1\n\none\n\n## Page 2\n\ntwo\n",
            PdfConvert.build(listOf("one", "two"), "md", "a.pdf")
        )
    }

    @Test
    fun csvIsOneRowPerPageAndFollowsRfc4180() {
        assertEquals(
            "page,text\n1,\"a,b\"\n2,\"say \"\"hi\"\"\"\n3,plain\n4,\"two\nlines\"\n",
            PdfConvert.build(listOf("a,b", "say \"hi\"", "plain", "two\nlines"), "csv", "a.pdf")
        )
    }

    @Test
    fun htmlEscapesTheTextAndTheTitle() {
        val html = PdfConvert.build(listOf("x < y & \"z\"\nnext"), "html", "a&b.pdf")
        assertTrue(html.contains("<title>a&amp;b.pdf</title>"))
        assertTrue(html.contains("<h2 id=\"page-1\">Page 1</h2>"))
        assertTrue(html.contains("<p>x &lt; y &amp; &quot;z&quot;</p>\n<p>next</p>"))
        assertFalse(html.contains("x < y"))
        assertTrue(PdfConvert.build(listOf("t"), "html", "").contains("<title>PDF export</title>"))
    }

    @Test
    fun freeNameNeverOverwrites() {
        assertEquals("r.md", PdfConvert.freeName(emptySet(), "r.md"))
        assertEquals("r (2).md", PdfConvert.freeName(setOf("r.md"), "r.md"))
        assertEquals("r (3).md", PdfConvert.freeName(setOf("r.md", "r (2).md"), "r.md"))
        assertEquals("notes (2)", PdfConvert.freeName(setOf("notes"), "notes"))
    }

    @Test
    fun theConvertedFileSitsBesideTheOriginalWithTheTargetExtension() {
        assertEquals("Report.md", PdfConvert.preferredName("Report.PDF", "md"))
        assertEquals("a.pdf.txt", PdfConvert.preferredName("a.pdf.pdf", "txt"))
        assertEquals("scan.csv", PdfConvert.preferredName("scan", "csv"))
    }

    @Test
    fun aLongPressSelectsTheWholeWord() {
        val text = "Hello, don't stop-gap!"
        assertEquals(0..4, PdfConvert.wordRange(text, 2))
        // The apostrophe and the hyphen INSIDE a word keep it whole.
        assertEquals(7..11, PdfConvert.wordRange(text, 10))
        assertEquals(13..20, PdfConvert.wordRange(text, 17))
    }

    @Test
    fun aLongPressOnNothingSelectsNothing() {
        val text = "Hello, don't stop-gap!"
        assertNull(PdfConvert.wordRange(text, 5))   // the comma
        assertNull(PdfConvert.wordRange(text, 6))   // the space
        assertNull(PdfConvert.wordRange(text, 21))  // the bang
        assertNull(PdfConvert.wordRange(text, -1))
        assertNull(PdfConvert.wordRange(text, 99))
        // A trailing hyphen is punctuation, not part of the word.
        assertNull(PdfConvert.wordRange("end-", 3))
        assertEquals(0..2, PdfConvert.wordRange("end-", 0))
    }
}
