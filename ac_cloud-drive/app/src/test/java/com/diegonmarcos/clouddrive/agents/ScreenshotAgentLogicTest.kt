package com.diegonmarcos.clouddrive.agents

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Screenshot agent's pure half: the contract id, the capture time, the source app, the
 * entity extractor, the record's JSON round trip and the store's files (what Cloud Tasks
 * reads), and the OCR band geometry that lets a tall screenshot be read near full resolution.
 */
class ScreenshotAgentLogicTest {

    private fun record(id: String = "0123456789abcdef", text: String = "Dentist\nThu 16 Oct 10:30", error: String? = null) = ScreenshotRecord(
        id = id, sourcePath = "/phone/Pictures/Screenshots/Screenshot_20261013-101500_Chrome.png",
        sourceSize = 482113, sourceMtimeMs = 1791886500000, takenAtMs = 1791886500000, processedAtMs = 1791886560000,
        width = 1080, height = 2400, text = text, lang = "und-Latn",
        blocks = listOf(ScreenshotRecord.Block("Dentist", listOf(40, 210, 380, 260), listOf(ScreenshotRecord.Line("Dentist", listOf(40, 210, 380, 260), 0.91)))),
        labels = listOf(ScreenshotRecord.Label("Text", 0.93, "image-labeling")),
        objects = listOf(ScreenshotRecord.Obj(listOf(1, 2, 3, 4), listOf(ScreenshotRecord.Label("Menu", 0.6, "object-detection")))),
        entities = ScreenshotRecord.entities(text),
        engine = mapOf("ocr" to "mlkit-text-latin", "agent_version" to "1"),
        error = error,
        colours = listOf(ScreenshotRecord.Colour("#101010", 0.7)),
        barcode = ScreenshotRecord.Barcode("QR_CODE", "https://example.org"),
        sourceApp = "Chrome",
    )

    @Test fun idIsSha256OfPathSizeMtime() {
        val id = ScreenshotRecord.idOf("/a/b.png", 10, 20)
        assertEquals(16, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
        assertEquals(id, ScreenshotRecord.idOf("/a/b.png", 10, 20))
        assertFalse(id == ScreenshotRecord.idOf("/a/b.png", 10, 21))
    }

    @Test fun takenAtPrefersExifThenFileNameThenMtime() {
        assertEquals(5L, ScreenshotRecord.takenAt("Screenshot_20261013-101500.png", 5L, 9L, ZoneOffset.UTC))
        assertEquals(java.time.LocalDateTime.of(2026, 10, 13, 10, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            ScreenshotRecord.takenAt("Screenshot_20261013-101500.png", null, 9L, ZoneOffset.UTC))
        assertEquals(9L, ScreenshotRecord.takenAt("IMG.png", null, 9L, ZoneOffset.UTC))
    }

    @Test fun sourceAppComesFromTheFileName() {
        assertEquals("Chrome", ScreenshotRecord.sourceAppOf("Screenshot_20261013-101500_Chrome.png"))
        assertEquals("com.whatsapp", ScreenshotRecord.sourceAppOf("Screenshot_20261013_101500_com.whatsapp.jpg"))
        assertNull(ScreenshotRecord.sourceAppOf("Screenshot_20261013-101500.png"))
        assertNull(ScreenshotRecord.sourceAppOf("holiday.jpg"))
    }

    @Test fun entitiesFindDatesTimesUrlsMailsPhones() {
        val t = "Dentist Thu 16 Oct 10:30 see https://ex.org/a. mail a.b@ex.org call +34 600 123 456 on 2026-10-16"
        val e = ScreenshotRecord.entities(t)
        val types = e.map { it.type }
        assertTrue(types.containsAll(listOf("date", "time", "url", "email", "phone")))
        e.forEach { assertEquals(it.text, t.substring(it.start, it.end)) }
        assertEquals("https://ex.org/a", e.first { it.type == "url" }.text)
        assertEquals("16 Oct", e.first { it.type == "date" }.text)
        assertEquals("10:30", e.first { it.type == "time" }.text)
    }

    @Test fun recordRoundTripsThroughTheContractJson() {
        val r = record()
        val json = r.toJson()
        assertEquals(ScreenshotRecord.SCHEMA, json["schema"].toString().trim('"'))
        val back = ScreenshotRecord.parse(json.toString())!!
        assertEquals(r, back)
        assertTrue(r.indexLine().contains("\"has_text\":true"))
    }

    @Test fun storeWritesRecordsIndexAndStateAtomically() {
        val root = File(Files.createTempDirectory("shots").toFile(), ScreenshotStore.RELATIVE)
        val s = ScreenshotStore(root)
        s.write(record("aaaaaaaaaaaaaaaa"))
        s.write(record("bbbbbbbbbbbbbbbb", error = "ocr: boom"))
        assertEquals(setOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"), s.ids())
        assertEquals(listOf("bbbbbbbbbbbbbbbb"), s.failedIds())
        assertEquals("Dentist", s.read("aaaaaaaaaaaaaaaa")!!.blocks.single().text)
        s.remove("aaaaaaaaaaaaaaaa")
        assertFalse(s.has("aaaaaaaaaaaaaaaa"))
        val lines = s.index.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines.last().contains("\"deleted\":true"))
        s.writeState(ScreenshotStore.State(1, 2, 1, 3, 10, 7))
        assertEquals(ScreenshotStore.State(1, 2, 1, 3, 10, 7), s.state())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun bandsCoverTheImageAndOwnEachRowOnce() {
        for ((w, h) in listOf(1080 to 2400, 1080 to 1000, 1440 to 3200, 720 to 30000, 1080 to 1081)) {
            val tiles = OcrLayout.tiles(w, h)
            assertTrue(tiles.size <= OcrLayout.MAX_TILES)
            assertEquals(0, tiles.first().top); assertEquals(h, tiles.last().bottom)
            assertEquals(0, tiles.first().coreTop); assertEquals(h, tiles.last().coreBottom)
            tiles.zipWithNext().forEach { (a, b) ->
                assertTrue("bands overlap", b.top < a.bottom)
                assertEquals("cores meet", a.coreBottom, b.coreTop)
                assertTrue(a.coreBottom > b.top && a.coreBottom <= a.bottom)
            }
            for (y in 0 until h step 7) assertEquals(1, tiles.count { OcrLayout.owns(it, OcrLayout.Box(0, y, 1, y + 1)) })
        }
    }

    @Test fun aLineCutByABandEdgeIsTakenWholeFromTheNext() {
        val tiles = OcrLayout.tiles(1080, 2400)
        val a = tiles[0]; val b = tiles[1]
        val line = OcrLayout.Box(10, a.bottom - 20, 300, a.bottom + 30)
        val clipped = OcrLayout.Box(10, line.t, 300, a.bottom)
        assertFalse(OcrLayout.owns(a, clipped))
        assertTrue(OcrLayout.owns(b, line))
    }

    @Test fun boxesScaleBackToSourcePixels() {
        val t = OcrLayout.Tile(1000, 2080, 1080, 2000)
        assertEquals(OcrLayout.Box(20, 1040, 120, 1080), OcrLayout.toSource(10, 20, 50, 20, 540, 540, 1080, t))
    }

    @Test fun linesGroupIntoBlocksInReadingOrder() {
        fun l(s: String, l: Int, t: Int, r: Int, b: Int) = OcrLayout.TextLine(s, OcrLayout.Box(l, t, r, b), 0.9)
        val blocks = OcrLayout.blocks(listOf(
            l("second line", 40, 260, 400, 300), l("Title", 40, 210, 380, 250),
            l("12:30", 900, 212, 1000, 248),
            l("Far below", 40, 900, 300, 940),
        ))
        assertEquals(listOf("Title\nsecond line", "12:30", "Far below"), blocks.map { it.text })
        assertEquals(listOf(40, 210, 400, 300), blocks[0].bbox)
        assertEquals("Title\nsecond line\n12:30\nFar below", OcrLayout.text(blocks))
    }
}
