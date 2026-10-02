package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * #772 the on-device route's two models, both BUNDLED in this APK so they work offline from the
 * first run: ML Kit image labelling (the default ~400-label model) and object detection (single
 * image, several objects, coarse classification). The clients are created once and never closed
 * — closing one poisons every later call in this process, as ImageScanner notes for OCR. The
 * labeller asks for everything over a low floor; the request's own min_confidence filters here.
 */
internal class MlKitOnDevice : Recognizer.OnDevice {
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(FLOOR).build()) }
    private val detector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptionsBase.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build(),
        )
    }

    override fun labels(bitmap: Bitmap, minConfidence: Double, max: Int): List<Recognizer.Label> =
        labeler.process(InputImage.fromBitmap(bitmap, 0)).awaitBlocking()
            .filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }
            .take(max)
            .map { Recognizer.Label(it.text, it.confidence.toDouble()) }

    override fun objects(bitmap: Bitmap, max: Int): List<Recognizer.Box> =
        detector.process(InputImage.fromBitmap(bitmap, 0)).awaitBlocking().take(max).map { o ->
            val best = o.labels.maxByOrNull { it.confidence }
            val r = o.boundingBox
            Recognizer.Box(best?.text ?: "object", best?.confidence?.toDouble() ?: 0.0, r.left, r.top, r.width(), r.height())
        }

    private companion object {
        const val FLOOR = 0.1f
    }
}
