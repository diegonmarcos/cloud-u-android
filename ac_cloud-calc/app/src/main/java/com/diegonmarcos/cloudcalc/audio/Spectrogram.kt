package com.diegonmarcos.cloudcalc.audio

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * The waterfall: the newest [rows] rows of log bands (dB), newest first, as pixels between three
 * theme colours (quiet, mid, loud) — the screen draws it, and "What is it?" sends it as a PNG to a
 * decision model that takes images. Colours come from the caller (res/values/colors.xml), never
 * from here.
 */
class Spectrogram(val rows: Int, val bands: Int) {
    private val data = ArrayDeque<DoubleArray>()

    @Synchronized fun push(row: DoubleArray) {
        data.addFirst(row)
        while (data.size > rows) data.removeLast()
    }

    @Synchronized fun count(): Int = data.size

    /** rows × bands ARGB, newest row on top; rows not yet filled are [quiet]. */
    @Synchronized fun pixels(quiet: Int, mid: Int, loud: Int, floorDb: Double): IntArray {
        val px = IntArray(rows * bands) { quiet }
        data.forEachIndexed { r, row -> for (b in 0 until minOf(bands, row.size)) px[r * bands + b] = colour(row[b], floorDb, quiet, mid, loud) }
        return px
    }

    fun bitmap(quiet: Int, mid: Int, loud: Int, floorDb: Double): Bitmap =
        Bitmap.createBitmap(pixels(quiet, mid, loud, floorDb), bands, rows, Bitmap.Config.ARGB_8888)

    companion object {
        /** [db] from [floorDb]..0 as a colour on quiet → mid → loud. */
        fun colour(db: Double, floorDb: Double, quiet: Int, mid: Int, loud: Int): Int {
            val t = ((db - floorDb) / -floorDb).coerceIn(0.0, 1.0)
            return if (t < 0.5) lerp(quiet, mid, t * 2) else lerp(mid, loud, (t - 0.5) * 2)
        }

        private fun lerp(a: Int, b: Int, t: Double): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xff) + (((b shr shift) and 0xff) - ((a shr shift) and 0xff)) * t).toInt() and 0xff
            return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** A bitmap as a `data:image/png;base64,` URL. */
        fun dataUrl(bmp: Bitmap): String {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
