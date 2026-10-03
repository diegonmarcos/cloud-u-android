package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * #798 live identification: what is in a camera frame (or a photo), with boxes, fully on device, in
 * one of three modes —
 *
 *  - objects: ML Kit object detection whose classifier is the full-label model of data/models.json
 *    (1000 ImageNet classes), every box named with its best labels; in `stream` mode the detector
 *    keeps tracking ids across a caller's successive frames.
 *  - labels: ML Kit image labelling of the whole frame.
 *  - text: ML Kit text recognition, one box per line.
 *
 * The answer is the uniform Recognition shape (libs:ml-l-image), boxes in the coordinates of the
 * frame as it was sized for detection (`width`/`height`), so a caller maps them onto its own view.
 * Thresholds and sizes arrive in the request (recognition.json's `detect` block via
 * RecognitionConfig.detectRequest). Never throws: a failure is {"ok": false, "error"}.
 */
internal class Detector(
    private val scanner: ImageScanner,
    private val onDevice: OnDevice,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One detected object: its tracking id (stream mode), its labels best first, its box. */
    data class Obj(val id: Int?, val labels: List<Recognizer.Label>, val x: Int, val y: Int, val w: Int, val h: Int)

    /** ML Kit's half, behind an interface so the suite can stand in for it. */
    interface OnDevice {
        fun objects(bitmap: Bitmap, stream: Boolean, minConfidence: Double, max: Int, labelsPerObject: Int): List<Obj>
        fun labels(bitmap: Bitmap, minConfidence: Double, max: Int): List<Recognizer.Label>
        /** What answers each mode, for the result's `model`. */
        fun model(mode: String): String
    }

    fun json(image: ByteArray, request: String): String =
        runCatching { detect(image, JSONObject(request)).toString() }
            .getOrElse { failure(it.message ?: it.javaClass.simpleName).toString() }

    fun detect(image: ByteArray, req: JSONObject): JSONObject {
        val mode = req.optString("mode", OBJECTS)
        if (mode !in MODES) return failure("unknown mode $mode (one of $MODES)")
        val d = req.optJSONObject("detect") ?: JSONObject()
        val loaded = scanner.loadBitmap(image) ?: return failure(ImageScanner.CANNOT_DECODE)
        // Below the OCR limit, so text mode's boxes are in this frame's coordinates too.
        val work = fit(loaded, d.optInt("max_side", 640).coerceIn(MIN_SIDE, ImageScanner.OCR_MAX_DIMENSION))
        try {
            val t0 = clock()
            val minConfidence = d.optDouble("min_confidence", 0.4)
            val out = JSONObject()
                .put("ok", true).put("route", Recognizer.ML).put("requested", Recognizer.ML).put("fell_back", false).put("reason", "")
                .put("mode", mode).put("model", onDevice.model(mode))
                .put("labels", JSONArray()).put("boxes", JSONArray()).put("text", "").put("colours", JSONArray())
                .put("barcode", JSONObject.NULL).put("answers", JSONObject()).put("cost", JSONObject.NULL)
                .put("width", work.width).put("height", work.height)
            when (mode) {
                OBJECTS -> {
                    val objs = onDevice.objects(work, req.optBoolean("stream"), minConfidence, d.optInt("max_objects", 5), d.optInt("labels_per_object", 3))
                    out.put("boxes", JSONArray().apply { objs.forEach { put(box(it)) } })
                    out.put("labels", labels(summary(objs)))
                }
                LABELS -> out.put("labels", labels(onDevice.labels(work, minConfidence, d.optInt("max_labels", 10))))
                TEXT -> {
                    val ocr = scanner.recognizeText(work)
                    ocr.optString("error").takeIf { it.isNotBlank() }?.let { return failure("text: $it") }
                    out.put("text", ocr.optString("text"))
                    val segs = ocr.optJSONArray("segments") ?: JSONArray()
                    out.put("boxes", JSONArray().apply {
                        for (i in 0 until segs.length()) {
                            val s = segs.getJSONObject(i)
                            val b = s.optJSONObject("box") ?: continue
                            put(JSONObject().put("label", s.optString("text")).put("p", s.optDouble("confidence", 1.0))
                                .put("x", b.optInt("x")).put("y", b.optInt("y")).put("w", b.optInt("w")).put("h", b.optInt("h"))
                                .put("id", JSONObject.NULL).put("alts", JSONArray()))
                        }
                    })
                }
            }
            return out.put("latency_ms", clock() - t0)
        } finally {
            if (work !== loaded) work.recycle()
            loaded.recycle()
        }
    }

    private fun box(o: Obj): JSONObject {
        val best = o.labels.firstOrNull()
        return JSONObject().put("label", best?.label ?: UNKNOWN).put("p", best?.p ?: 0.0)
            .put("x", o.x).put("y", o.y).put("w", o.w).put("h", o.h)
            .put("id", o.id ?: JSONObject.NULL).put("alts", labels(o.labels))
    }

    private fun labels(l: List<Recognizer.Label>) = JSONArray().apply { l.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } }

    private fun failure(why: String): JSONObject = JSONObject()
        .put("ok", false).put("route", Recognizer.ML).put("requested", Recognizer.ML).put("error", why)

    companion object {
        const val OBJECTS = "objects"
        const val LABELS = "labels"
        const val TEXT = "text"
        val MODES = listOf(OBJECTS, LABELS, TEXT)

        /** A box whose classifier named nothing over the threshold is still an object. */
        const val UNKNOWN = "object"

        /** Below this a frame has too few pixels for any of the three models. */
        const val MIN_SIDE = 64

        /** The frame's labels in objects mode: each object's best label once, at its highest probability, best first. */
        fun summary(objs: List<Obj>): List<Recognizer.Label> =
            objs.mapNotNull { it.labels.firstOrNull() }.groupBy { it.label }
                .map { (label, ls) -> Recognizer.Label(label, ls.maxOf { it.p }) }
                .sortedByDescending { it.p }
    }
}
