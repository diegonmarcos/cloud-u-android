package com.diegonmarcos.superapp.image.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.EnumMap
import java.util.EnumSet

/**
 * ONE scan engine for the whole fleet (task #459/#460).
 *
 * ZXing decodes QR codes and linear barcodes from a [Bitmap]; ML Kit
 * text-recognition reads the text in the same pixels; [BarcodePayloadParser]
 * turns the raw decode into a typed [BarcodePayload] both apps render as
 * actions. cloud-drive calls this from FilesBridge for a single image AND from
 * its folder scan; cloud-media-center calls the same object from its viewer and
 * its album scan — there is exactly one implementation.
 *
 * [decodeBarcode] and [recognizeText] are blocking and safe to call from a
 * background thread (ML Kit and ZXing are both safe for sequential use; the
 * WebView JavascriptInterface bridge already runs off the UI thread).
 */
class ImageScanEngine(context: Context) {

    private val appContext = context.applicationContext

    // The text-recognition client is a singleton per options (ML Kit returns the
    // same instance for identical options), so it is created once and never
    // closed — closing it would poison every later call in this process.
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ── barcode decode (ZXing) ──────────────────────────────────────────────

    /** The formats ZXing's MultiFormatReader handles. Reported verbatim to the UI. */
    fun supportedFormats(): List<String> =
        listOf(
            BarcodeFormat.QR_CODE.name,
            BarcodeFormat.DATA_MATRIX.name,
            BarcodeFormat.AZTEC.name,
            BarcodeFormat.PDF_417.name,
            BarcodeFormat.CODE_128.name,
            BarcodeFormat.CODE_39.name,
            BarcodeFormat.CODE_93.name,
            BarcodeFormat.CODABAR.name,
            BarcodeFormat.EAN_13.name,
            BarcodeFormat.EAN_8.name,
            BarcodeFormat.ITF.name,
            BarcodeFormat.UPC_A.name,
            BarcodeFormat.UPC_E.name,
            BarcodeFormat.RSS_14.name,
            BarcodeFormat.RSS_EXPANDED.name
        )

    /**
     * Decodes the first barcode found in [bitmap]. Returns null when the image
     * carries none — a photo with no barcode is a normal outcome, not an error.
     *
     * [bitmap] is scanned at up to [SCAN_MAX_DIMENSION] pixels on its longest
     * side: ZXing's TRY_HARDER pass on a whole-phone photo only helps if the
     * bitmap is not so huge that the binarizer starves it of memory.
     */
    fun decodeBarcode(bitmap: Bitmap): BarcodeScan? {
        val prepared = prepareForScan(bitmap) ?: return null
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
            BarcodeScan(
                format = result.barcodeFormat?.name ?: "UNKNOWN",
                rawValue = result.text.orEmpty(),
                payload = BarcodePayloadParser.parse(result.text.orEmpty())
            )
        } catch (error: NotFoundException) {
            null
        } catch (error: Exception) {
            null
        }
    }

    /** Convenience for callers holding a [File] (cloud-drive's FilesBridge paths). */
    fun decodeBarcode(file: File): BarcodeScan? {
        val bitmap = loadBitmap(file) ?: return null
        return try {
            decodeBarcode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Convenience for callers holding a content [Uri] (cloud-media-center). */
    fun decodeBarcode(uri: Uri): BarcodeScan? {
        val bitmap = loadBitmap(uri) ?: return null
        return try {
            decodeBarcode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    // ── OCR (ML Kit text-recognition) ───────────────────────────────────────

    /**
     * Recognises the text in [bitmap]. Returns an [OcrResult] whose full text
     * is [OcrResult.text] and whose line-level segments carry confidence —
     * enough for both select/copy/share and save-as-.md consumers.
     */
    fun recognizeText(bitmap: Bitmap): OcrResult {
        val prepared = prepareForOcr(bitmap)
        return try {
            // InputImage.fromBitmap with rotation 0: the bitmap is already
            // upright because loadBitmap applies EXIF rotation.
            val image = InputImage.fromBitmap(prepared, 0)
            val visionText = textRecognizer.process(image).awaitBlocking()
            val segments = visionText.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    OcrSegment(
                        text = line.text,
                        confidence = line.confidence?.floatValue()
                    )
                }
            }
            OcrResult(
                text = visionText.text.orEmpty(),
                segments = segments
            )
        } catch (error: Exception) {
            // Never throw across the bridge: an OCR failure is shown to the user
            // as an empty result with the reason, not as a crashed page.
            OcrResult(text = "", segments = emptyList(), error = error.message ?: error.javaClass.simpleName)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    /** Convenience for [File] callers, mirroring [decodeBarcode]. */
    fun recognizeText(file: File): OcrResult {
        val bitmap = loadBitmap(file) ?: return OcrResult(text = "", segments = emptyList(), error = "cannot decode the image")
        return try {
            recognizeText(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Convenience for content-URI callers, mirroring [decodeBarcode]. */
    fun recognizeText(uri: Uri): OcrResult {
        val bitmap = loadBitmap(uri) ?: return OcrResult(text = "", segments = emptyList(), error = "cannot decode the image")
        return try {
            recognizeText(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    // ── shared image loading ─────────────────────────────────────────────────

    /**
     * Loads a bitmap from a file, honouring EXIF orientation (a phone photo
     * taken in portrait stores the sensor rotation in EXIF, not in the pixels)
     * and downsampling to at most [MAX_DECODE_DIMENSION]. Returns null when the
     * file is not an image the platform can decode.
     */
    fun loadBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            sampleOptions(bounds.outWidth, bounds.outHeight)
        ) ?: return null
        val orientation = readFileExifOrientation(file) ?: ExifInterface.ORIENTATION_NORMAL
        return applyExifRotation(decoded, orientation)
    }

    /** Loads a bitmap from a content [Uri], same EXIF + downsampling policy. The underlying stream is opened once for the bounds pass and once for the pixels; EXIF is read from its own stream because ExifInterface consumes what it reads. */
    fun loadBitmap(uri: Uri): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val decoded = try {
            resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val orientation = resolver.openInputStream(uri)?.use { stream ->
                try {
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (error: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }
            } ?: ExifInterface.ORIENTATION_NORMAL
            val pixels = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, sampleOptions(bounds.outWidth, bounds.outHeight))
            }
            pixels?.let { applyExifRotation(it, orientation) }
        } catch (error: Exception) {
            null
        }
        return decoded
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

    private fun readFileExifOrientation(file: File): Int? =
        try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (error: Exception) {
            null
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

    // The ML Kit process() call is a Task; this blocks on it the same way the
    // translate engine's LocalTranslateEngineClient blocks on its ML Kit calls.
    private fun <T> com.google.android.gms.tasks.Task<T>.awaitBlocking(): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var value: T? = null
        var failure: Exception? = null
        addOnSuccessListener { result -> value = result; latch.countDown() }
        addOnFailureListener { error -> failure = error as? Exception ?: RuntimeException(error); latch.countDown() }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        if (failure != null) throw failure
        return value ?: throw RuntimeException("ML Kit text recognition timed out after 30 seconds")
    }

    private companion object {
        /** Longest side for the decode pass; larger photos cost memory without improving the hit. */
        const val SCAN_MAX_DIMENSION = 1600

        /** Longest side for ML Kit's recommended text-recognition input. */
        const val OCR_MAX_DIMENSION = 1024

        /** Longest side a bitmap is ever materialised at; the lambda callers scan governs this. */
        const val MAX_DECODE_DIMENSION = 1600
    }
}

/** One successful barcode decode: the format name, the raw value, and its typed meaning. */
data class BarcodeScan(
    val format: String,
    val rawValue: String,
    val payload: BarcodePayload
)

/** One line of recognised text with ML Kit's confidence, when it reports one. */
data class OcrSegment(
    val text: String,
    val confidence: Float?
)

/**
 * The OCR outcome. [text] is the whole recognised text; [segments] are the
 * line-level pieces; [error] is null on success and a short reason on failure
 * (recognizeText never throws).
 */
data class OcrResult(
    val text: String,
    val segments: List<OcrSegment>,
    val error: String? = null
)