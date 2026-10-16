package app.sterna.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Past this the editor is shown a downscaled copy: a 5 MB photo as a data: URI would choke the page. */
internal const val INLINE_PREVIEW_MAX_BYTES = 400_000

/** The longest side of a downscaled preview, in pixels. */
private const val PREVIEW_MAX_SIDE = 1280

/** The types the editor page accepts in a `data:image/…` source - its own allow-list, mirrored here. */
private val PAGE_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp")

/**
 * A `data:` URI the editor can SHOW for a picked picture. The part that is SENT is always the original
 * bytes; this is only the picture on screen, so a large or unusual one (a photo, a HEIC) is decoded
 * and re-encoded smaller. Throws when the bytes are not an image the platform can decode.
 */
internal fun inlinePreviewDataUri(type: String, bytes: ByteArray): String {
    val lower = type.lowercase()
    if (lower in PAGE_IMAGE_TYPES && bytes.size <= INLINE_PREVIEW_MAX_BYTES) {
        return "data:$lower;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: error("not an image")
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
    bitmap.recycle()
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
