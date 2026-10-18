package com.diegonmarcos.clouddrive.files.convert

import com.diegonmarcos.clouddrive.files.FileOps
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** txt / md → PDF through the plain writer: the file's own structure is parsed back (header, xref offsets, page count, escaping). */
class PdfWriterTest {

    private fun render(lines: List<PdfWriter.Line>): String = String(ByteArrayOutputStream().also { PdfWriter.write(lines, it) }.toByteArray(), Charsets.ISO_8859_1)

    @Test fun theFileIsAWellFormedPdfWithCorrectXrefOffsets() {
        val pdf = render(listOf(PdfWriter.Line("Hello (world) \\ café — ok")))
        assertTrue(pdf.startsWith("%PDF-1.4\n")); assertTrue(pdf.endsWith("%%EOF\n"))
        val startxref = Regex("startxref\\n(\\d+)\\n").find(pdf)!!.groupValues[1].toInt()
        assertTrue(pdf.substring(startxref).startsWith("xref\n0 7\n")) // catalog, pages, 2 fonts, 1 page, 1 content + the free entry
        val entries = Regex("(\\d{10}) 00000 n \\n").findAll(pdf).map { it.groupValues[1].toInt() }.toList()
        assertEquals(6, entries.size)
        entries.forEachIndexed { i, off -> assertTrue("object ${i + 1} at $off", pdf.substring(off).startsWith("${i + 1} 0 obj\n")) }
        assertTrue(pdf, pdf.contains("(Hello \\(world\\) \\\\ caf\\351 \\227 ok) Tj")) // é = octal 351, em dash = WinAnsi 0x97
    }

    @Test fun longTextFlowsOntoMorePagesAndWrapsAtTheMargin() {
        val lines = (1..200).map { PdfWriter.Line("line $it") }
        val pages = PdfWriter.pages(lines)
        assertTrue("200 lines at 10pt need several A4 pages, got ${pages.size}", pages.size in 3..5)
        assertEquals(pages.size, Regex("/Type /Page /Parent").findAll(render(lines)).count())
        val wrapped = PdfWriter.wrap("word ".repeat(40).trim(), 30)
        assertTrue(wrapped.all { it.length <= 30 } && wrapped.size > 1)
        assertEquals("word ".repeat(40).trim(), wrapped.joinToString(" "))
        assertEquals(listOf("x".repeat(10), "x".repeat(10)), PdfWriter.wrap("x".repeat(20), 10)) // no space: hard cut
    }

    @Test fun anEmptyDocumentIsStillOneValidPage() {
        assertEquals(1, PdfWriter.pages(emptyList()).size)
        assertTrue(render(emptyList()).contains("/Count 1"))
    }

    @Test fun markdownBecomesSizedHeadingsAndBulletsAndTxtIsSetAsWritten() {
        val md = TextToPdf.lines("# Title\n\nSome **bold** [site](http://x.test)\n- item\n\n```\ncode\n```\n", markdown = true)
        assertTrue(md.any { it.text == "Title" && it.bold && it.size > 14f })
        assertTrue(md.any { it.text == "Some bold site (http://x.test)" })
        assertTrue(md.any { it.text == "• item" })
        assertTrue(md.any { it.text == "code" && it.indent > 0f })
        val txt = TextToPdf.lines("a\tb\r\n  c", markdown = false)
        assertEquals(listOf("a    b", "  c"), txt.map { it.text })
    }

    @Test fun theConverterWritesAPdfBesideTheSourceAndNeverOverwrites() {
        val dir = Files.createTempDirectory("pdfconv").toFile()
        val src = File(dir, "notes.md").apply { writeText("# Hi\n\ntext\n") }
        val noProgress = FileOps.Progress { _, _, _ -> }
        val first = TextToPdf.convert(src, dir, noProgress) { false }.single()
        val second = TextToPdf.convert(src, dir, noProgress) { false }.single()
        assertEquals("notes.pdf", first.name); assertEquals("notes (2).pdf", second.name)
        assertTrue(first.readBytes().copyOf(8).toString(Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
    }
}
