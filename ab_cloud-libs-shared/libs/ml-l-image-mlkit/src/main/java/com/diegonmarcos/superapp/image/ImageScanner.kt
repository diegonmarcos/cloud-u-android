package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.EnumMap
import java.util.EnumSet

/**
 * ONE scan engine for the whole fleet (task #459/#460), running ONLY inside
 * Cloud-Lib-Ml-L-Image-Mlkit.apk (engine-apk-split move 6).
 *
 * ZXing decodes QR codes and linear barcodes; ML Kit text-recognition reads the
 * text in the same pixels. The image arrives as the bytes a caller's file
 * descriptor carried (ImageScanBackendService); the answer leaves as JSON. Typing
 * the raw decode (BarcodePayloadParser) is the client's job in libs:ml-l-image,
 * beside the types the consumers render.
 *
 * Blocking; the binder thread that calls it is already off any UI thread.
 */
internal class ImageScanner {

    // The text-recognition client is a singleton per options (ML Kit returns the
    // same instance for identical options), so it is created once and never
    // closed — closing it would poison every later call in this process.
    // Lazy since #772: the recognizer builds a scanner for barcodes and bitmaps too, and a
    // request with ocr off (or a JVM suite) must not need ML Kit's context to exist.
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // ── barcode decode (ZXing) ──────────────────────────────────────────────

    /** {"found": true, "format", "rawValue"} or {"found": false} — no barcode is a normal outcome. */
    fun barcodeJson(image: ByteArray): String {
        val bitmap = loadBitmap(image) ?: return JSONObject().put("found", false).put("error", CANNOT_DECODE).toString()
        return try {
            decodeBarcode(bitmap)?.let { (format, raw) -> JSONObject().put("found", true).put("format", format).put("rawValue", raw) }
                ?.toString() ?: JSONObject().put("found", false).toString()
        } finally {
            bitmap.recycle()
        }
    }

    /** {"text", "segments": [{"text", "confidence"?}], "language"?} or the same with "error". Never throws. */
    fun ocrJson(image: ByteArray): String {
        val bitmap = loadBitmap(image) ?: return JSONObject().put("text", "").put("segments", JSONArray()).put("error", CANNOT_DECODE).toString()
        return try {
            recognizeText(bitmap).toString()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes the first barcode found in [bitmap]. Returns null when the image
     * carries none — a photo with no barcode is a normal outcome, not an error.
     *
     * [bitmap] is scanned at up to [SCAN_MAX_DIMENSION] pixels on its longest
     * side: ZXing's TRY_HARDER pass on a whole-phone photo only helps if the
     * bitmap is not so huge that the binarizer starves it of memory.
     */
    internal fun decodeBarcode(bitmap: Bitmap): Pair<String, String>? {
        val prepared = prepareForScan(bitmap)
        val width = prepared.width
        val height = prepared.height
        val pixels = IntArray(width * height)
        prepared.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = try {
            RGBLuminanceSource(width, height, pixels)
        } catch (error: Exception) {
            return null
        }
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, true)
            put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.allOf(BarcodeFormat::class.java))
        }

        return try {
            val result = MultiFormatReader().apply { setHints(hints) }.decodeWithState(binaryBitmap)
            (result.barcodeFormat?.name ?: "UNKNOWN") to result.text.orEmpty()
        } catch (error: NotFoundException) {
            null
        } catch (error: Exception) {
            null
        }
    }

    // ── OCR (ML Kit text-recognition) ───────────────────────────────────────

    /**
     * Recognises the text in [bitmap]: the whole text plus line-level segments
     * with confidence — enough for select/copy/share and save-as-.md consumers.
     * A null confidence or language is simply absent from the JSON.
     */
    internal fun recognizeText(bitmap: Bitmap): JSONObject {
        val prepared = prepareForOcr(bitmap)
        return try {
            // InputImage.fromBitmap with rotation 0: the bitmap is already
            // upright because loadBitmap applies EXIF rotation.
            val image = InputImage.fromBitmap(prepared, 0)
            val visionText = textRecognizer.process(image).awaitBlocking()
            val segments = JSONArray()
            visionText.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    segments.put(JSONObject().put("text", line.text).put("confidence", line.confidence))
                }
            }
            JSONObject().put("text", visionText.text.orEmpty()).put("segments", segments).put("language",
                // The v2 Latin text-recognition model reports the tag of the
                // language it actually recognised (e.g. "en", "es", "pt", "de");
                // null means the model could not commit to one. Consumers show
                // this verbatim so the user sees "Recognised text (English)",
                // not a guess from the app's own locale.
                languageOf(visionText))
        } catch (error: Exception) {
            // Never throw across the bridge: an OCR failure is shown to the user
            // as an empty result with the reason, not as a crashed page.
            JSONObject().put("text", "").put("segments", JSONArray()).put("error", error.message ?: error.javaClass.simpleName)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }


    /**
     * The BCP-47 tag of the language the recogniser committed to, or null.
     *
     * The top-level Text result has NO recognizedLanguage — verified against
     * text-recognition's classes.jar, where only TextBlock/Line/Element (via
     * the package-private TextBase) expose getRecognizedLanguage(). The whole
     * image is read by one Latin-model pass, so the first block's tag stands
     * for the image; null means the model could not commit to one.
     */
    private fun languageOf(text: com.google.mlkit.vision.text.Text): String? = try {
        text.textBlocks.firstOrNull()?.recognizedLanguage?.takeIf { it.isNotBlank() }
    } catch (error: Exception) {
        null
    }

    // ── image loading ────────────────────────────────────────────────────────

    /**
     * Decodes the caller's image bytes, honouring EXIF orientation (a portrait
     * phone photo stores the sensor rotation in EXIF, not in the pixels) and
     * downsampling to at most [MAX_DECODE_DIMENSION]. Null when the bytes are not
     * an image the platform can decode.
     */
    internal fun loadBitmap(image: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, sampleOptions(bounds.outWidth, bounds.outHeight))
            ?: return null
        val orientation = try {
            ExifInterface(ByteArrayInputStream(image)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (error: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return applyExifRotation(decoded, orientation)
    }

    /**
     * ZXing is a luminance barcode reader: colour is noise, not signal, so
     * decoding the full-resolution bitmap is pure cost. Longest side is capped
     * at [SCAN_MAX_DIMENSION] for memory safety.
     */
    private fun prepareForScan(bitmap: Bitmap): Bitmap =
        if (maxDimension(bitmap) <= SCAN_MAX_DIMENSION) bitmap
        else scaleInto(bitmap, SCAN_MAX_DIMENSION)

    /**
     * ML Kit's own guidance: ~1024px on the longest side is the accuracy/speed
     * sweet spot for text recognition. Larger just burns memory on the phone.
     */
    private fun prepareForOcr(bitmap: Bitmap): Bitmap =
        if (maxDimension(bitmap) <= OCR_MAX_DIMENSION) bitmap
        else scaleInto(bitmap, OCR_MAX_DIMENSION)

    private fun maxDimension(bitmap: Bitmap): Int = maxOf(bitmap.width, bitmap.height)

    private fun scaleInto(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val scale = maxDimension.toFloat() / maxDimension(bitmap)
        val width = maxOf(1, (bitmap.width * scale).toInt())
        val height = maxOf(1, (bitmap.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, maxOf(1, width), maxOf(1, height), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun sampleOptions(width: Int, height: Int): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

    private fun computeInSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest > MAX_DECODE_DIMENSION * 2) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /** Rotates the bitmap when the EXIF orientation tag says the sensor was rotated. */
    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        val rotated = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        } catch (error: Exception) {
            bitmap
        }
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    internal companion object {
        /** The load-failure reason, verbatim: Media Center's ScanOutcomeReducer matches it. */
        const val CANNOT_DECODE = "cannot decode the image"

        /** Longest side for the decode pass; larger photos cost memory without improving the hit. */
        const val SCAN_MAX_DIMENSION = 1600

        /** Longest side for ML Kit's recommended text-recognition input. */
        const val OCR_MAX_DIMENSION = 1024

        /** Longest side a bitmap is ever materialised at; the lambda callers scan governs this. */
        const val MAX_DECODE_DIMENSION = 1600
    }
}

// The ML Kit process() call is a Task; this blocks on it the same way the translate engine's
// LocalTranslateEngineClient blocks on its ML Kit calls. Top-level since #772: the recognizer's
// labelling and object detection wait on their Tasks the same way.
internal fun <T> com.google.android.gms.tasks.Task<T>.awaitBlocking(): T {
    val latch = java.util.concurrent.CountDownLatch(1)
    var value: T? = null
    var failure: Exception? = null
    addOnSuccessListener { result -> value = result; latch.countDown() }
    addOnFailureListener { error -> failure = error as? Exception ?: RuntimeException(error); latch.countDown() }
    latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
    if (failure != null) throw failure!!
    return value ?: throw RuntimeException("ML Kit timed out after 30 seconds")
}
