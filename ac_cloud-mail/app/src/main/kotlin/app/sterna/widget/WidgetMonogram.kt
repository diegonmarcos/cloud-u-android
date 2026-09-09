package app.sterna.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import app.sterna.R
import app.sterna.ui.components.ToneRamp

/**
 * The badge of a widget row, as a `Bitmap` — the only way a `RemoteViews` can carry a coloured
 */
internal object WidgetMonogram {

    /**
     * The disc for [monogram], painted in the tones handed in — cached, then handed out as is.
     */
    fun badge(context: Context, monogram: RecentRowMonogram, ramps: List<ToneRamp>): Bitmap {
        val side = context.resources.getDimensionPixelSize(R.dimen.widget_recent_monogram)
        val argb = widgetMonogramArgb(monogram, ramps)
        val key = widgetMonogramKey(monogram.initial, argb, side)
        cache.get(key)?.let { return it }
        return draw(monogram.initial, argb, side).also { cache.put(key, it) }
    }

    /**
     * A filled circle with the initial centred on it. The baseline is computed from the FONT
     */
    private fun draw(initial: String, argb: Int, side: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = side / 2f
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }
        canvas.drawCircle(centre, centre, centre, disc)
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = widgetMonogramLetterArgb(argb)
            textSize = side * LETTER_SCALE
            textAlign = Paint.Align.CENTER
        }
        val metrics = letter.fontMetrics
        canvas.drawText(initial, centre, centre - (metrics.ascent + metrics.descent) / 2f, letter)
        return bitmap
    }

    /**
     * The letter's height as a fraction of the disc — the ratio the app's own badge draws at. The
     * widget's disc is smaller, and the ratio is what makes it read as the same badge.
     */
    private const val LETTER_SCALE = 0.4f

    /**
     * Bounded in BYTES, not in entries: a disc's side comes from the resources, so an xxxhdpi
     */
    private const val CACHE_KB = 512

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
}
