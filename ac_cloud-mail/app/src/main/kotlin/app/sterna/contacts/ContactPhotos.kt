package app.sterna.contacts

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes contact thumbnails from the device address book, off the main thread, with a small
 */
object ContactPhotos {
    /** Longest side kept, in pixels. Thumbnails are ~96 px; this only guards against odd sources. */
    private const val MAX_PX = 128

    /**
     * Room for about sixteen decoded thumbnails (128 px ARGB_8888 = 64 KiB at worst, 96 px = 36 KiB)
     */
    private const val MAX_ENTRIES = 48
    private const val MAX_BYTES = 1L * 1024 * 1024

    /** Cached decode result; a null [image] is a remembered miss, not an absent entry. */
    private class Cached(val image: ImageBitmap?)

    // Keyed by the contact thumbnail URI: the address book hands out one per contact, and it is the
    // only thing identifying the picture. Two suggestion rows for the same contact therefore share
    // a single decode.
    private val cache = PhotoCache<Cached>(MAX_ENTRIES, MAX_BYTES)

    /** What is already in memory for [photoUri] — lets a row draw its photo without a frame of monogram. */
    fun cached(photoUri: String?): ImageBitmap? = photoUri?.let { cache.get(it)?.image }

    /**
     * The thumbnail for [photoUri], decoded on IO and cached. Returns null — and the caller falls
     */
    suspend fun load(context: Context, photoUri: String?): ImageBitmap? {
        if (photoUri.isNullOrBlank()) return null
        cache.get(photoUri)?.let { return it.image }
        // Photos live behind the same permission as the suggestions themselves. A revoked
        // permission must degrade to monograms, never to an error.
        if (!AndroidContacts.hasPermission(context)) return null
        return withContext(Dispatchers.IO) {
            cache.get(photoUri)?.let { return@withContext it.image }
            val bitmap = decode(context, photoUri)
            val image = bitmap?.asImageBitmap()
            cache.put(photoUri, Cached(image), bitmap?.let { it.byteCount.toLong() } ?: 0L)
            image
        }
    }

    private fun decode(context: Context, photoUri: String) = runCatching {
        val uri = Uri.parse(photoUri)
        // First pass reads the header only, so an unexpectedly large picture is never fully
        // decoded: the second pass subsamples it down to MAX_PX while reading.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_PX)
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}

/** Power-of-two subsampling that brings the longest side at or under [maxPx]. */
internal fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= maxPx) {
        longest /= 2
        sample *= 2
    }
    return sample
}
