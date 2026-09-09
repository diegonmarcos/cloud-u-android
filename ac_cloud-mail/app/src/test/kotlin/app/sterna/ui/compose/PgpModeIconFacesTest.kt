package app.sterna.ui.compose

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * The four compose modes must be told apart ON SCREEN, and in the steady state the only permanent
 */
class PgpModeIconFacesTest {

    @Test fun `every mode's padlock is ink and not an empty canvas`() {
        // A rasteriser that silently drew nothing would make all four masks "distinct from
        // nothing" and the measurement below would pass on four blank squares.
        for (mode in PgpMode.entries) {
            val icon = pgpModeIcon(mode)
            val ink = face(mode).count { it }
            assertTrue(
                "the rasteriser produced $ink inked pixels for $mode (${icon.name}) out of " +
                    "$PIXELS: that is not a padlock, it is a blank square, and every distinctness " +
                    "measurement in this class is meaningless until it is fixed.",
                ink >= MIN_INK,
            )
        }
    }

    @Test fun `the four modes wear four faces, no two of which look alike`() {
        val faces = PgpMode.entries.associateWith { face(it) }
        val measured = PgpMode.entries.flatMapIndexed { i, a ->
            PgpMode.entries.drop(i + 1).map { b -> Triple(a, b, differing(faces[a]!!, faces[b]!!)) }
        }
        record(measured)
        val worst = measured.minBy { it.third }
        assertTrue(
            "two compose modes wear padlocks the eye cannot tell apart: ${worst.first} " +
                "(${pgpModeIcon(worst.first).name}) and ${worst.second} " +
                "(${pgpModeIcon(worst.second).name}) differ by ${worst.third} of $PIXELS " +
                "rasterised pixels, below the $DISTINCT_PIXELS this test requires. The composer " +
                "then shows one face for two different promises about the message — for ENCRYPT " +
                "vs ENCRYPT_UNSIGNED, the same padlock on a signed and on an unsigned message. " +
                "All six pairs, in differing pixels: " +
                measured.joinToString(", ") { "${it.first}/${it.second}=${it.third}" },
            worst.third >= DISTINCT_PIXELS,
        )
    }

    /** Binary ink mask of the face [mode] wears, [SIDE] × [SIDE], row-major. */
    private fun face(mode: PgpMode): BooleanArray = rasterise(pgpModeIcon(mode))

    /** Pixels where exactly one of the two masks has ink — the XOR of the two drawings. */
    private fun differing(a: BooleanArray, b: BooleanArray): Int = a.indices.count { a[it] != b[it] }

    /**
     * Drops the six measurements next to the build outputs. testLogging prints failures only, so a
     */
    private fun record(measured: List<Triple<PgpMode, PgpMode, Int>>) {
        val out = File("build/reports/pgp-icon-faces.txt")
        out.parentFile?.mkdirs()
        out.writeText(
            buildString {
                appendLine(
                    "written ${System.currentTimeMillis()} — $SIDE×$SIDE raster, $PIXELS pixels, " +
                        "threshold $DISTINCT_PIXELS",
                )
                for (mode in PgpMode.entries) {
                    appendLine("$mode -> ${pgpModeIcon(mode).name}, ink=${face(mode).count { it }}")
                }
                for ((a, b, d) in measured) appendLine("$a / $b : $d differing pixels")
                for (mode in PgpMode.entries) {
                    appendLine()
                    appendLine("$mode — ${pgpModeIcon(mode).name}")
                    val mask = face(mode)
                    for (row in 0 until SIDE step 2) {
                        appendLine(
                            (0 until SIDE step 1)
                                .joinToString("") { if (mask[row * SIDE + it]) "#" else "." },
                        )
                    }
                }
            },
        )
    }

    // ── the rasteriser ───────────────────────────────────────────────────────────────────────

    /** An affine map, `[a c e; b d f]`, from icon viewport units to raster pixels. */
    private class Xf(
        val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float,
    ) {
        fun x(px: Float, py: Float) = a * px + c * py + e
        fun y(px: Float, py: Float) = b * px + d * py + f

        /** This map applied AFTER [m]. */
        fun then(m: Xf) = Xf(
            a * m.a + c * m.b, b * m.a + d * m.b,
            a * m.c + c * m.d, b * m.c + d * m.d,
            a * m.e + c * m.f + e, b * m.e + d * m.f + f,
        )

        companion object {
            fun translate(tx: Float, ty: Float) = Xf(1f, 0f, 0f, 1f, tx, ty)
            fun scale(sx: Float, sy: Float) = Xf(sx, 0f, 0f, sy, 0f, 0f)
            fun rotate(degrees: Float): Xf {
                val r = Math.toRadians(degrees.toDouble())
                return Xf(cos(r).toFloat(), sin(r).toFloat(), -sin(r).toFloat(), cos(r).toFloat(), 0f, 0f)
            }
        }
    }

    /** One closed contour, already in raster coordinates. */
    private class Contour {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        fun add(x: Float, y: Float) { xs.add(x); ys.add(y) }
    }

    private fun rasterise(icon: ImageVector): BooleanArray {
        val mask = BooleanArray(PIXELS)
        val viewport = Xf.scale(SIDE / icon.viewportWidth, SIDE / icon.viewportHeight)
        draw(icon.root, viewport, icon.name, mask)
        return mask
    }

    private fun draw(group: VectorGroup, parent: Xf, icon: String, mask: BooleanArray) {
        check(group.clipPathData.isEmpty()) {
            "$icon: group '${group.name}' carries a clip path, which this rasteriser does not " +
                "apply — the mask it produced would not be the ink on screen."
        }
        val at = parent
            .then(Xf.translate(group.translationX + group.pivotX, group.translationY + group.pivotY))
            .then(Xf.rotate(group.rotation))
            .then(Xf.scale(group.scaleX, group.scaleY))
            .then(Xf.translate(-group.pivotX, -group.pivotY))
        for (node in group) {
            when (node) {
                is VectorGroup -> draw(node, at, icon, mask)
                is VectorPath -> {
                    val what = "$icon: path '${node.name}'"
                    check(node.fill != null && node.fillAlpha > 0f) {
                        "$what is not filled (fill=${node.fill}, alpha=${node.fillAlpha}); this " +
                            "rasteriser only fills, so its ink would not be the ink on screen."
                    }
                    check(node.stroke == null) {
                        "$what is STROKED, and this rasteriser draws no strokes — refusing rather " +
                            "than measuring a drawing that is missing part of its ink."
                    }
                    check(node.trimPathStart == 0f && node.trimPathEnd == 1f) {
                        "$what is trimmed, which this rasteriser ignores."
                    }
                    fill(
                        contours(node.pathData, at, what),
                        node.pathFillType == PathFillType.EvenOdd,
                        mask,
                    )
                }
                else -> error("$icon: unknown vector node ${node::class.java.name}")
            }
        }
    }

    /**
     * Compose path nodes → closed contours of straight segments, in raster coordinates.
     */
    private fun contours(nodes: List<PathNode>, at: Xf, what: String): List<Contour> {
        val out = ArrayList<Contour>()
        var current: Contour? = null
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var cubicX = 0f
        var cubicY = 0f
        var quadX = 0f
        var quadY = 0f
        var afterCubic = false
        var afterQuad = false

        fun open(x: Float, y: Float) {
            current = Contour().also { out.add(it); it.add(at.x(x, y), at.y(x, y)) }
        }
        // After a Close, a drawing command starts a NEW contour at the closed subpath's start
        // point (which is where cx/cy have just been put back to) — not a contour missing its
        // first point, which would drop a whole segment from the fill.
        fun lineTo(x: Float, y: Float) {
            val c = current ?: Contour().also {
                out.add(it); current = it; it.add(at.x(cx, cy), at.y(cx, cy))
            }
            c.add(at.x(x, y), at.y(x, y))
        }
        fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val x0 = cx
            val y0 = cy
            for (step in 1..STEPS) {
                val t = step.toFloat() / STEPS
                val u = 1f - t
                lineTo(
                    u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3,
                    u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3,
                )
            }
        }
        fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {
            val x0 = cx
            val y0 = cy
            for (step in 1..STEPS) {
                val t = step.toFloat() / STEPS
                val u = 1f - t
                lineTo(
                    u * u * x0 + 2f * u * t * x1 + t * t * x2,
                    u * u * y0 + 2f * u * t * y1 + t * t * y2,
                )
            }
        }

        for (node in nodes) {
            var isCubic = false
            var isQuad = false
            when (node) {
                is PathNode.MoveTo -> {
                    cx = node.x; cy = node.y; startX = cx; startY = cy; open(cx, cy)
                }
                is PathNode.RelativeMoveTo -> {
                    cx += node.dx; cy += node.dy; startX = cx; startY = cy; open(cx, cy)
                }
                is PathNode.LineTo -> { cx = node.x; cy = node.y; lineTo(cx, cy) }
                is PathNode.RelativeLineTo -> { cx += node.dx; cy += node.dy; lineTo(cx, cy) }
                is PathNode.HorizontalTo -> { cx = node.x; lineTo(cx, cy) }
                is PathNode.RelativeHorizontalTo -> { cx += node.dx; lineTo(cx, cy) }
                is PathNode.VerticalTo -> { cy = node.y; lineTo(cx, cy) }
                is PathNode.RelativeVerticalTo -> { cy += node.dy; lineTo(cx, cy) }
                is PathNode.CurveTo -> {
                    cubicTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                    cubicX = node.x2; cubicY = node.y2; cx = node.x3; cy = node.y3; isCubic = true
                }
                is PathNode.RelativeCurveTo -> {
                    val x1 = cx + node.dx1; val y1 = cy + node.dy1
                    val x2 = cx + node.dx2; val y2 = cy + node.dy2
                    val x3 = cx + node.dx3; val y3 = cy + node.dy3
                    cubicTo(x1, y1, x2, y2, x3, y3)
                    cubicX = x2; cubicY = y2; cx = x3; cy = y3; isCubic = true
                }
                is PathNode.ReflectiveCurveTo -> {
                    val x1 = if (afterCubic) 2f * cx - cubicX else cx
                    val y1 = if (afterCubic) 2f * cy - cubicY else cy
                    cubicTo(x1, y1, node.x1, node.y1, node.x2, node.y2)
                    cubicX = node.x1; cubicY = node.y1; cx = node.x2; cy = node.y2; isCubic = true
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    val x1 = if (afterCubic) 2f * cx - cubicX else cx
                    val y1 = if (afterCubic) 2f * cy - cubicY else cy
                    val x2 = cx + node.dx1; val y2 = cy + node.dy1
                    val x3 = cx + node.dx2; val y3 = cy + node.dy2
                    cubicTo(x1, y1, x2, y2, x3, y3)
                    cubicX = x2; cubicY = y2; cx = x3; cy = y3; isCubic = true
                }
                is PathNode.QuadTo -> {
                    quadTo(node.x1, node.y1, node.x2, node.y2)
                    quadX = node.x1; quadY = node.y1; cx = node.x2; cy = node.y2; isQuad = true
                }
                is PathNode.RelativeQuadTo -> {
                    val x1 = cx + node.dx1; val y1 = cy + node.dy1
                    val x2 = cx + node.dx2; val y2 = cy + node.dy2
                    quadTo(x1, y1, x2, y2)
                    quadX = x1; quadY = y1; cx = x2; cy = y2; isQuad = true
                }
                is PathNode.ReflectiveQuadTo -> {
                    val x1 = if (afterQuad) 2f * cx - quadX else cx
                    val y1 = if (afterQuad) 2f * cy - quadY else cy
                    quadTo(x1, y1, node.x, node.y)
                    quadX = x1; quadY = y1; cx = node.x; cy = node.y; isQuad = true
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    val x1 = if (afterQuad) 2f * cx - quadX else cx
                    val y1 = if (afterQuad) 2f * cy - quadY else cy
                    val x2 = cx + node.dx; val y2 = cy + node.dy
                    quadTo(x1, y1, x2, y2)
                    quadX = x1; quadY = y1; cx = x2; cy = y2; isQuad = true
                }
                PathNode.Close -> { cx = startX; cy = startY; current = null }
                else -> error(
                    "$what uses ${node::class.java.simpleName}, which this rasteriser cannot " +
                        "draw (elliptical arcs are not implemented). Implement it here — do NOT " +
                        "skip it: a skipped segment silently shrinks the drawing being measured.",
                )
            }
            afterCubic = isCubic
            afterQuad = isQuad
        }
        return out
    }

    /**
     * Scan-line fill: for every pixel row, the crossings of the contours at the row's centre,
     */
    private fun fill(contours: List<Contour>, evenOdd: Boolean, mask: BooleanArray) {
        for (row in 0 until SIDE) {
            val y = row + 0.5f
            val xs = ArrayList<Float>()
            val dirs = ArrayList<Int>()
            for (contour in contours) {
                val n = contour.xs.size
                if (n < 2) continue
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val y1 = contour.ys[i]
                    val y2 = contour.ys[j]
                    if (y1 == y2) continue
                    if (y < minOf(y1, y2) || y >= maxOf(y1, y2)) continue
                    val x1 = contour.xs[i]
                    val x2 = contour.xs[j]
                    xs.add(x1 + (y - y1) / (y2 - y1) * (x2 - x1))
                    dirs.add(if (y2 > y1) 1 else -1)
                }
            }
            if (xs.isEmpty()) continue
            val order = xs.indices.sortedBy { xs[it] }
            var winding = 0
            for (k in 0 until order.size - 1) {
                winding += dirs[order[k]]
                val inside = if (evenOdd) (k % 2 == 0) else winding != 0
                if (!inside) continue
                val from = xs[order[k]]
                val to = xs[order[k + 1]]
                var col = kotlin.math.ceil(from - 0.5f).toInt()
                if (col < 0) col = 0
                while (col < SIDE && col + 0.5f < to) {
                    mask[row * SIDE + col] = true
                    col++
                }
            }
        }
    }

    private companion object {
        /** 24 dp of viewport rasterised 4×, so a 2 dp stroke is 8 pixels wide and cannot vanish. */
        const val SIDE = 96
        const val PIXELS = SIDE * SIDE

        /** Straight segments per Bézier: at this scale the flattening error is well under a pixel. */
        const val STEPS = 32

        /**
         * How many pixels two faces must differ by. Deliberately a FRANK gap, not a hair — and the
         */
        const val DISTINCT_PIXELS = 400

        /**
         * Below this a mask is not a drawing. The four faces shipped ink 2278 to 3846 pixels, so
         */
        const val MIN_INK = 1200
    }
}
