package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * #772 the on-device route's two models, both BUNDLED in this APK so they work offline from the
 * first run: ML Kit image labelling (the default ~400-label model) and object detection (single
 * image, several objects, coarse classification). The clients are created once and never closed
 * — closing one poisons every later call in this process, as ImageScanner notes for OCR. The
 * labeller asks for everything over a low floor; the request's own min_confidence filters here.
 *
 * #798 [Detector]'s objects mode runs the same detector with the full-label classifier of
 * data/models.json (staged as an uncompressed asset, so LocalModel maps it): one client per mode,
 * because a STREAM_MODE client is what carries tracking ids from one frame to the next. Both ask
 * for every label over the floor and the request's min_confidence filters here.
 */
internal class MlKitOnDevice : Recognizer.OnDevice, Detector.OnDevice {
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

    private val classifier by lazy { LocalModel.Builder().setAssetFilePath(BuildConfig.DETECT_MODEL_ASSET).build() }
    private val single by lazy { custom(ObjectDetectorOptionsBase.SINGLE_IMAGE_MODE) }
    private val stream by lazy { custom(ObjectDetectorOptionsBase.STREAM_MODE) }

    private fun custom(mode: Int): ObjectDetector = ObjectDetection.getClient(
        CustomObjectDetectorOptions.Builder(classifier)
            .setDetectorMode(mode)
            .enableMultipleObjects()
            .enableClassification()
            .setClassificationConfidenceThreshold(FLOOR)
            .setMaxPerObjectLabelCount(LABELS_PER_OBJECT)
            .build(),
    )

    override fun objects(bitmap: Bitmap, stream: Boolean, minConfidence: Double, max: Int, labelsPerObject: Int): List<Detector.Obj> =
        (if (stream) this.stream else single).process(InputImage.fromBitmap(bitmap, 0)).awaitBlocking().take(max).map { o ->
            val r = o.boundingBox
            Detector.Obj(
                o.trackingId,
                o.labels.filter { it.confidence >= minConfidence }.sortedByDescending { it.confidence }.take(labelsPerObject)
                    .map { Recognizer.Label(it.text, it.confidence.toDouble()) },
                r.left, r.top, r.width(), r.height(),
            )
        }

    override fun model(mode: String): String = when (mode) {
        Detector.OBJECTS -> BuildConfig.DETECT_MODEL_NAME
        Detector.LABELS -> "ML Kit image labelling (bundled)"
        else -> "ML Kit text recognition (Latin)"
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
        /** ML Kit's own cap on labels per object; the request's labels_per_object trims further. */
        const val LABELS_PER_OBJECT = 5
    }
}
