/*
 * Task #461 — the camera's entry point onto the ONE shared image-scan engine.
 *
 * A camera's natural "scan" is the thing it just captured: the user holds the
 * phone up to a code or a page, so a scan meant for content must read the
 * photograph, not only decode the live preview. This class runs the shared
 * engine (libs:ml-l-image-mlkit — ImageScanEngine: ZXing barcode decode + ML
 * Kit OCR) over a captured image's content Uri, off whatever thread the
 * caller chooses. It is deliberately a thin shell: the engine, the typed
 * payload parser and the failure vocabulary all live in the one shared
 * module that cloud-drive and cloud-media-center already compile in — a
 * private copy of the decode path is the exact defect #170/#261 this fleet
 * fixed, so there is none here.
 *
 * Both halves are always asked, exactly as the shared engine's callers do:
 * decodeBarcode returns null for "no barcode" AND for "could not load", and
 * the OCR result's error string is the tiebreaker that tells them apart.
 */
package cld.camera.analyzer

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.image.mlkit.BarcodeScan
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import com.diegonmarcos.superapp.image.mlkit.OcrResult

/**
 * The camera's surface over the shared [ImageScanEngine]. Construct once and
 * reuse: the engine holds an ML Kit text-recognition client that must be a
 * singleton per options, so a fresh instance per scan would be needless churn.
 */
class ImageContentScanner(context: Context) {

    private val engine = ImageScanEngine(context.applicationContext)

    /** What one captured image produced: the decoded barcode (or null) and the OCR text. */
    data class Content(
        val barcode: BarcodeScan?,
        val ocr: OcrResult
    ) {
        /** Whether the scan found anything worth showing. */
        val hasAnyContent: Boolean get() = barcode != null || ocr.text.isNotBlank()
    }

    /**
     * Runs the shared engine over [uri] and returns the result. Blocking; safe
     * from any thread the shared engine documents (the ML Kit and ZXing calls
     * are safe for sequential background use). Never throws — the engine's
     * OCR contract returns an OcrResult with an error string instead.
     */
    fun scan(uri: Uri): Content {
        val barcode = engine.decodeBarcode(uri)
        val ocr = engine.recognizeText(uri)
        return Content(barcode, ocr)
    }
}