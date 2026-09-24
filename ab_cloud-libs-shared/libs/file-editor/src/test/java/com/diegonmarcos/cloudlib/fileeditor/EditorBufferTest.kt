package com.diegonmarcos.cloudlib.fileeditor

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBufferTest {

    @Test fun undoRedoWalkHistoryAndAnEditClearsRedo() {
        val b = EditorBuffer("a")
        assertFalse(b.canUndo)
        b.edit("ab"); b.edit("abc")
        assertTrue(b.undo()); assertEquals("ab", b.text)
        assertTrue(b.undo()); assertEquals("a", b.text)
        assertFalse(b.undo())
        assertTrue(b.redo()); assertEquals("ab", b.text)
        b.edit("abX")
        assertFalse("a new edit forgets the redo branch", b.canRedo)
        assertTrue(b.undo()); assertEquals("ab", b.text)
        b.edit("ab")   // no-op edit records nothing
        assertEquals("a", run { b.undo(); b.text })
    }

    @Test fun historyIsCapped() {
        val b = EditorBuffer("", maxHistory = 3)
        for (i in 1..10) b.edit("v$i")
        var steps = 0; while (b.undo()) steps++
        assertEquals(3, steps)
        assertEquals("v7", b.text)
    }

    @Test fun findPlainCaseInsensitiveByDefaultAndRegexOnRequest() {
        val b = EditorBuffer("Foo foo FOO\nbar f.o")
        assertEquals(3, b.find("foo").size)
        assertEquals(1, b.find("foo", caseSensitive = true).size)
        assertEquals(listOf(16..18), b.find("f.o"))                       // plain: the dot is literal
        assertEquals(4, b.find("f.o", regex = true).size)                 // regex: any char
        assertTrue(b.find("[", regex = true).isEmpty())                   // invalid regex: no match, no crash
        assertTrue(b.find("").isEmpty())
        assertEquals(4..6, b.findNext("foo", fromOffset = 1))
        assertEquals(0..2, b.findNext("foo", fromOffset = 9))            // wraps
        assertEquals(8..10, b.findPrevious("foo", beforeOffset = 100))
        assertEquals(8..10, b.findPrevious("foo", beforeOffset = 0))     // wraps backwards
        assertNull(b.findNext("zzz", 0))
    }

    @Test fun replaceOneAndReplaceAllAreSingleUndoSteps() {
        val b = EditorBuffer("x1 x2 x3")
        b.replace(b.find("x2").single(), "Y")
        assertEquals("x1 Y x3", b.text)
        assertEquals(2, b.replaceAll("x", "z"))
        assertEquals("z1 Y z3", b.text)
        assertEquals(2, b.replaceAll("z(\\d)", "<$1>", regex = true))
        assertEquals("<1> Y <3>", b.text)
        assertEquals(0, b.replaceAll("nothing", "here"))
        b.undo(); assertEquals("z1 Y z3", b.text)
        b.undo(); assertEquals("x1 Y x3", b.text)
        b.undo(); assertEquals("x1 x2 x3", b.text)
    }

    @Test fun lineAndColumnArithmetic() {
        val b = EditorBuffer("ab\ncde\n\nf")
        assertEquals(4, b.lineCount)
        assertEquals(1, b.lineOf(0)); assertEquals(1, b.columnOf(0))
        assertEquals(2, b.lineOf(3)); assertEquals(1, b.columnOf(3))
        assertEquals(2, b.lineOf(5)); assertEquals(3, b.columnOf(5))
        assertEquals(4, b.lineOf(9)); assertEquals(2, b.columnOf(9))
        assertEquals(0, b.offsetOfLine(1)); assertEquals(3, b.offsetOfLine(2)); assertEquals(7, b.offsetOfLine(3)); assertEquals(8, b.offsetOfLine(4))
        assertEquals(9, b.offsetOfLine(99))
        assertEquals(3, b.wordCount)
    }

    @Test fun lineEndingsDetectedNormalisedAndPreservedOnWrite() {
        assertEquals(LineEnding.CRLF, LineEnding.detect("a\r\nb\r\n"))
        assertEquals(LineEnding.LF, LineEnding.detect("a\nb\r\n\n"))
        assertEquals(LineEnding.CR, LineEnding.detect("a\rb\r"))
        assertEquals(LineEnding.LF, LineEnding.detect("no newline"))
        val doc = EditorDocument.decode("one\r\ntwo\r\n".toByteArray())
        assertEquals("one\ntwo\n", doc.text)
        assertEquals(LineEnding.CRLF, doc.lineEnding)
        val f = File(Files.createTempDirectory("fe").toFile(), "t.txt")
        doc.write(f, "one\ntwo\nthree\n")
        assertArrayEquals("one\r\ntwo\r\nthree\r\n".toByteArray(), f.readBytes())
        doc.write(f, "x\ny", LineEnding.LF)
        assertEquals("x\ny", f.readText())
        assertFalse("no temp file left behind", f.parentFile.listFiles()!!.any { it.name.endsWith(".cloud-drive-tmp") })
    }

    @Test fun bomsAreDetectedAndRestored() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        val d8 = EditorDocument.decode(utf8Bom)
        assertEquals("hi", d8.text); assertTrue(d8.hadBom); assertEquals(Charsets.UTF_8, d8.charset)
        assertArrayEquals(utf8Bom, EditorDocument.encode("hi", LineEnding.LF, d8.charset, d8.hadBom))

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "é".toByteArray(Charsets.UTF_16LE)
        val d16 = EditorDocument.decode(utf16)
        assertEquals("é", d16.text); assertEquals(Charsets.UTF_16LE, d16.charset)
        assertArrayEquals(utf16, EditorDocument.encode("é", LineEnding.LF, d16.charset, d16.hadBom))

        val plain = EditorDocument.decode("ü".toByteArray())
        assertEquals("ü", plain.text); assertFalse(plain.hadBom)
    }
}
