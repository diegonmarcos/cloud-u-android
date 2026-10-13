package com.diegonmarcos.clouddrive.agents

/**
 * The geometry of the Screenshot agent's OCR, pure so ScreenshotLayoutTest runs it on the JVM.
 *
 * WHY TILES. The fleet's image engine (Cloud-Lib-Ml-L-Image-Mlkit) reads text on a frame of at
 * most 1024 px on its longest side. A 1080x2400 screenshot sent whole would be read at 460 px
 * wide, where status-bar and chat text is a few pixels tall. So the agent cuts the screenshot
 * into overlapping horizontal bands about as tall as the image is wide, reads each band at close
 * to full resolution, and stitches the lines back in source pixels.
 *
 * WHICH BAND OWNS A LINE. Bands overlap by [OVERLAP] px. A line belongs to the band whose CORE
 * (the band minus half the overlap at each inner edge) holds its vertical centre, so a line cut
 * by one band's edge is taken whole from the next band and never twice.
 */
object OcrLayout {
    /** Pixels two neighbouring bands share; a text line up to this tall is always whole in one band. */
    const val OVERLAP = 160
    /** A very long scroll capture still costs a bounded number of engine calls. */
    const val MAX_TILES = 40

    data class Tile(val top: Int, val bottom: Int, val coreTop: Int, val coreBottom: Int) {
        val height get() = bottom - top
    }

    /** [left, top, right, bottom]. */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val h get() = b - t
        val cy get() = (t + b) / 2
        fun list() = listOf(l, t, r, b)
        fun union(o: Box) = Box(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
    }

    data class TextLine(val text: String, val box: Box, val confidence: Double?)

    /** The bands of a [width] x [height] image; one band when it is not taller than wide. */
    fun tiles(width: Int, height: Int): List<Tile> {
        if (width <= 0 || height <= 0) return emptyList()
        var band = maxOf(width, OVERLAP * 3)
        if (height <= band) return listOf(Tile(0, height, 0, height))
        // Past MAX_TILES bands the bands grow (and are read a little smaller) instead of leaving gaps.
        if ((height - OVERLAP + band - OVERLAP - 1) / (band - OVERLAP) > MAX_TILES) band = (height - OVERLAP + MAX_TILES - 1) / MAX_TILES + OVERLAP
        val step = band - OVERLAP
        val tops = generateSequence(0) { it + step }.takeWhile { it + band < height }.toMutableList()
        tops.add(maxOf(0, height - band))
        val distinct = tops.distinct()
        return distinct.mapIndexed { i, top ->
            val bottom = minOf(height, top + band)
            val coreTop = if (i == 0) 0 else (top + distinct[i - 1] + band) / 2
            val coreBottom = if (i == distinct.lastIndex) height else (distinct[i + 1] + bottom) / 2
            Tile(top, bottom, coreTop, coreBottom)
        }
    }

    /** A line read in [tile] (box already in source pixels) is kept when its centre is in the tile's core. */
    fun owns(tile: Tile, box: Box): Boolean = box.cy >= tile.coreTop && box.cy < tile.coreBottom

    /** A box read on a frame of [frameW] x [frameH] that showed source rows [tile].top.., back in source pixels. */
    fun toSource(x: Int, y: Int, w: Int, h: Int, frameW: Int, frameH: Int, tileW: Int, tile: Tile): Box {
        val sx = if (frameW > 0) tileW.toDouble() / frameW else 1.0
        val sy = if (frameH > 0) tile.height.toDouble() / frameH else 1.0
        return Box((x * sx).toInt(), tile.top + (y * sy).toInt(), ((x + w) * sx).toInt(), tile.top + ((y + h) * sy).toInt())
    }

    /** Lines in reading order: top to bottom, and left to right within one visual row. */
    fun readingOrder(lines: List<TextLine>): List<TextLine> {
        if (lines.isEmpty()) return lines
        val sorted = lines.sortedBy { it.box.t }
        val rows = ArrayList<MutableList<TextLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            val anchor = row?.first()
            if (anchor != null && kotlin.math.abs(l.box.cy - anchor.box.cy) < maxOf(1, minOf(l.box.h, anchor.box.h)) / 2) row.add(l) else rows.add(mutableListOf(l))
        }
        return rows.flatMap { r -> r.sortedBy { it.box.l } }
    }

    /**
     * Lines grouped into blocks (paragraphs, bubbles, cards): a line joins the previous block when
     * it starts below it within [gapFactor] of a line height and the two overlap horizontally.
     */
    fun blocks(lines: List<TextLine>, gapFactor: Double = 0.9): List<ScreenshotRecord.Block> {
        val groups = ArrayList<MutableList<TextLine>>()
        for (l in readingOrder(lines)) {
            val g = groups.lastOrNull { grp ->
                val last = grp.last()
                val gap = l.box.t - last.box.b
                val overlap = minOf(l.box.r, last.box.r) - maxOf(l.box.l, last.box.l)
                gap >= -last.box.h / 2 && gap <= gapFactor * maxOf(last.box.h, l.box.h) && overlap > 0
            }
            if (g != null) g.add(l) else groups.add(mutableListOf(l))
        }
        return groups.map { g ->
            val box = g.map { it.box }.reduce { a, b -> a.union(b) }
            ScreenshotRecord.Block(g.joinToString("\n") { it.text }, box.list(), g.map { ScreenshotRecord.Line(it.text, it.box.list(), it.confidence) })
        }
    }

    /** The record's `text`: blocks in reading order joined by newlines. */
    fun text(blocks: List<ScreenshotRecord.Block>): String = blocks.joinToString("\n") { it.text }
}
