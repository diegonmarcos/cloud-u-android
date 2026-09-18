/*
 * Task #460 — read what is IN an image (barcode decode + OCR).
 *
 * The UI contract for the scan feature. ImageScanViewModel runs the shared
 * scan engine (libs:ml-l-image-mlkit) off the main thread and reduces its
 * two raw results (BarcodeScan?, OcrResult) into one state the sheet renders.
 * The reductions here are what let the UI say WHY nothing came back — an
 * empty result box is this fleet's dominant defect shape and it is banned:
 * every scan ends in a message naming the real reason (no barcode, no text,
 * unsupported format, permission refused, model not downloaded).
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.scan

import com.diegonmarcos.superapp.image.mlkit.BarcodeScan
import com.diegonmarcos.superapp.image.mlkit.OcrResult

/** What a single image scan produced. At most one failure can be set. */
data class ImageScanOutcome(
    /** The decoded barcode, when the image carried one. */
    val barcode: BarcodeScan? = null,
    /** True when the image decodes fine but carries no barcode at all. */
    val barcodeAbsent: Boolean = false,
    /** The recognised text, when OCR succeeded and found text. */
    val ocr: OcrResult? = null,
    /** True when OCR ran but the image holds no readable text. */
    val ocrAbsent: Boolean = false,
    /** The reason the scan could not produce everything, when one applies. */
    val failure: ScanFailure? = null
) {
    val hasAnything: Boolean
        get() = barcode != null || ocr != null
}

/**
 * The named reasons a scan cannot produce content. Each maps to one
 * user-visible string in ImageScanSheet, so "nothing came back" always says
 * why. [Other] carries ML Kit's own message when it is none of the known
 * shapes — inventing a friendlier lie is exactly what this fleet stopped doing.
 */
sealed interface ScanFailure {
    /** The app has no read access to the image (SecurityException). */
    data object PermissionRefused : ScanFailure

    /** The bytes are not an image the platform can decode. */
    data object UnsupportedFormat : ScanFailure

    /** ML Kit's lazy text-recognition model is not on the device and could not be fetched. */
    data object ModelNotDownloaded : ScanFailure

    /** Any other engine-reported failure; [reason] is the engine's own message. */
    data class Other(val reason: String) : ScanFailure
}

/**
 * Turns the shared engine's raw results into the UI contract. Pure function so
 * the exact reduction is unit-tested on the JVM in every build: a regression
 * here would silently turn "no barcode" into a blank sheet on the phone.
 */
object ScanOutcomeReducer {

    fun reduce(barcode: BarcodeScan?, ocr: OcrResult): ImageScanOutcome {
        ocr.error?.let { error ->
            val failure = when {
                // The engine's own "could not decode the bytes" message — the
                // image is not one the platform can read.
                error == CANNOT_DECODE_IMAGE -> ScanFailure.UnsupportedFormat
                // ML Kit's lazy model download failing surfaces with
                // "download" in the message ("Model downloading has failed",
                // "Failed to download the model…").
                error.contains("download", ignoreCase = true) -> ScanFailure.ModelNotDownloaded
                else -> ScanFailure.Other(error)
            }
            // The failure explains the OCR side; the barcode half may still
            // have succeeded (a QR can decode even when the OCR model is off).
            return ImageScanOutcome(barcode = barcode, failure = failure)
        }
        return ImageScanOutcome(
            barcode = barcode,
            barcodeAbsent = barcode == null,
            ocr = ocr.takeIf { it.text.isNotBlank() },
            ocrAbsent = ocr.text.isBlank()
        )
    }

    /** Matches the engine's load-failure message verbatim (ImageScanEngine). */
    private const val CANNOT_DECODE_IMAGE = "cannot decode the image"
}