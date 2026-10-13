/*
 * Task #460 — the scan UI contract reduction. ScanOutcomeReducer turns the
 * shared engine's two raw results (BarcodeScan?, OcrResult) into the state the
 * sheet renders; a regression here silently turns "no barcode" into a blank
 * sheet on the phone, so the exact mapping is pinned on the JVM in every
 * build. All inputs are the lib's plain data classes — no Android needed.
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.scan

import com.diegonmarcos.superapp.image.mlkit.BarcodePayload
import com.diegonmarcos.superapp.image.mlkit.BarcodeScan
import com.diegonmarcos.superapp.image.mlkit.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanOutcomeReducerTest {

    private val qr = BarcodeScan(
        format = "QR_CODE",
        rawValue = "https://example.com/a",
        payload = BarcodePayload.Url("https://example.com/a")
    )

    private fun ocr(
        text: String = "Hello world",
        error: String? = null,
        language: String? = null
    ) = OcrResult(text = text, segments = emptyList(), error = error, language = language)

    @Test
    fun barcodeAndTextProduceBothSections() {
        val outcome = ScanOutcomeReducer.reduce(qr, ocr(text = "HELLO", language = "en"))
        assertEquals(qr, outcome.barcode)
        assertEquals("HELLO", outcome.ocr?.text)
        assertEquals("en", outcome.ocr?.language)
        assertFalse(outcome.barcodeAbsent)
        assertFalse(outcome.ocrAbsent)
        assertNull(outcome.failure)
        assertTrue(outcome.hasAnything)
    }

    @Test
    fun noBarcodeAndNoTextReportBothAbsent() {
        val outcome = ScanOutcomeReducer.reduce(null, ocr(text = ""))
        assertNull(outcome.barcode)
        assertNull(outcome.ocr)
        assertTrue(outcome.barcodeAbsent)
        assertTrue(outcome.ocrAbsent)
        assertNull(outcome.failure)
        assertFalse(outcome.hasAnything)
    }

    @Test
    fun cannotDecodeErrorMapsToUnsupportedFormat() {
        val outcome = ScanOutcomeReducer.reduce(null, ocr(error = "cannot decode the image"))
        assertEquals(ScanFailure.UnsupportedFormat, outcome.failure)
    }

    @Test
    fun downloadErrorMapsToModelNotDownloaded() {
        val outcome = ScanOutcomeReducer.reduce(null, ocr(error = "Model downloading has failed"))
        assertEquals(ScanFailure.ModelNotDownloaded, outcome.failure)
        // case-insensitive: the engine's message capitalisation varies by release
        val lower = ScanOutcomeReducer.reduce(null, ocr(error = "Failed to DOWNLOAD the model"))
        assertEquals(ScanFailure.ModelNotDownloaded, lower.failure)
    }

    @Test
    fun unknownErrorIsKeptVerbatim() {
        val outcome = ScanOutcomeReducer.reduce(null, ocr(error = "some engine message"))
        assertEquals(ScanFailure.Other("some engine message"), outcome.failure)
    }

    @Test
    fun barcodeSurvivesOcrFailure() {
        val outcome = ScanOutcomeReducer.reduce(qr, ocr(error = "some engine message"))
        assertEquals(qr, outcome.barcode)
        assertFalse(outcome.barcodeAbsent)
        assertEquals(ScanFailure.Other("some engine message"), outcome.failure)
        assertTrue(outcome.hasAnything)
    }

    @Test
    fun blankOcrTextWithBarcodeMeansBarcodeOnly() {
        val outcome = ScanOutcomeReducer.reduce(qr, ocr(text = ""))
        assertEquals(qr, outcome.barcode)
        assertNull(outcome.ocr)
        assertTrue(outcome.ocrAbsent)
        assertNull(outcome.failure)
    }
}