package com.diegonmarcos.clouddrive.agents

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.media.ExifInterface
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AGENTS ▸ SCREENSHOTS: every screenshot on the phone gets OCR text with line boxes, image labels,
 * detected objects and extracted entities, all ON DEVICE through the fleet's image engine
 * (libs:ml-l-image → Cloud-Lib-Ml-L-Image-Mlkit: ML Kit text recognition, image labelling, object
 * detection), written to the published contract (data/agent-screenshots-contract.json).
 *
 * INCREMENTAL: a screenshot whose contract id (path + size + mtime) already has a record is skipped,
 * so a pass only works on what is new or edited; a record whose source is gone is removed.
 * Blocking; the worker runs it off the main thread. [shouldStop] is polled per image.
 */
object ScreenshotAgent {
    data class Status(val running: Boolean = false, val done: Int = 0, val todo: Int = 0, val current: String = "", val message: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    fun store(): ScreenshotStore = ScreenshotStore(File(SharedStore.root(), ScreenshotStore.RELATIVE))

    /** The folders Android writes screenshots to, under shared storage. */
    fun sources(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screenshots"),
            File(ext, "Screenshots"),
        ).filter { it.isDirectory }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
    }

    fun screenshots(): List<File> = sources().flatMap { d ->
        d.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && it.extension.lowercase() in ScreenshotStore.IMAGE_EXTENSIONS }.toList()
    }.sortedByDescending { it.lastModified() }

    fun run(ctx: Context, shouldStop: () -> Boolean = { false }): Status {
        if (_status.value.running) return _status.value
        val store = store()
        val files = screenshots()
        val wanted = files.associateBy { ScreenshotRecord.idOf(it.absolutePath, it.length(), it.lastModified()) }
        // Sources that are gone or were edited: their old records go.
        store.all().filter { it.id !in wanted.keys }.forEach { store.remove(it.id) }
        val todo = wanted.filterKeys { !store.has(it) }
        _status.value = Status(true, 0, todo.size)
        val engine = ImageScanEngine(ctx)
        val problem = if (todo.isEmpty()) null else engine.check()
        if (problem != null) {
            _status.value = Status(false, 0, todo.size, message = problem)
            return _status.value
        }
        val before = store.state()
        var done = 0; var failed = 0; var cursor = before.cursorMtimeMs
        for ((id, f) in todo) {
            if (shouldStop()) break
            _status.value = _status.value.copy(current = f.name, done = done)
            val r = runCatching { process(engine, id, f) }.getOrElse { e -> failedRecord(id, f, e.message ?: e.toString()) }
            store.write(r)
            if (r.error != null) failed++
            done++
            cursor = maxOf(cursor, f.lastModified())
        }
        store.writeState(ScreenshotStore.State(System.currentTimeMillis(), before.processed + done, before.failed + failed, cursor))
        _status.value = Status(false, done, todo.size - done)
        return _status.value
    }

    private fun process(engine: ImageScanEngine, id: String, f: File): ScreenshotRecord {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.absolutePath, it) }
        val w = bounds.outWidth.coerceAtLeast(0); val h = bounds.outHeight.coerceAtLeast(0)
        val errors = ArrayList<String>()
        val ocr = engine.recognizeText(f)
        ocr.error?.let { errors.add("ocr: $it") }
        val lines = engine.detect(f, RecognitionConfig.detectRequest("text", false)).also { it.error?.let { e -> errors.add("text: $e") } }
        val labels = engine.detect(f, RecognitionConfig.detectRequest("labels", false)).also { it.error?.let { e -> errors.add("labels: $e") } }
        val objects = engine.detect(f, RecognitionConfig.detectRequest("objects", false)).also { it.error?.let { e -> errors.add("objects: $e") } }
        val blocks = lines.boxes.map { b -> val bb = scale(b, lines, w, h); ScreenshotRecord.Block(b.label, bb, listOf(ScreenshotRecord.Line(b.label, bb, b.p.takeIf { it > 0 }))) }
        val text = ocr.text.ifBlank { blocks.joinToString("\n") { it.text } }
        return ScreenshotRecord(
            id = id, sourcePath = f.absolutePath, sourceSize = f.length(), sourceMtimeMs = f.lastModified(),
            takenAtMs = ScreenshotRecord.takenAt(f.name, exifTime(f), f.lastModified()), processedAtMs = System.currentTimeMillis(),
            width = w, height = h, text = text, lang = ocr.language ?: "und-Latn",
            blocks = blocks,
            labels = (labels.labels.map { ScreenshotRecord.Label(it.label, it.p, "image-labeling") } + objects.boxes.flatMap { b -> (listOf(Recognition.Label(b.label, b.p)) + b.alts) }.map { ScreenshotRecord.Label(it.label, it.p, "object-detection") })
                .filter { it.label.isNotBlank() }.groupBy { it.label.lowercase() }.map { (_, v) -> v.maxBy { it.confidence } }.sortedByDescending { it.confidence },
            objects = objects.boxes.map { b -> ScreenshotRecord.Obj(scale(b, objects, w, h), (listOf(Recognition.Label(b.label, b.p)) + b.alts).filter { it.label.isNotBlank() }.map { ScreenshotRecord.Label(it.label, it.p, "object-detection") }) },
            entities = ScreenshotRecord.entities(text),
            engine = mapOf("ocr" to "mlkit-text", "labeling" to "mlkit-image-labeling", "objects" to "mlkit-object-detection", "agent_version" to ScreenshotRecord.AGENT_VERSION.toString()),
            error = errors.joinToString("; ").ifBlank { null },
        )
    }

    /** A box from an analysed (possibly downscaled) frame, back in source pixels. */
    private fun scale(b: Recognition.Box, r: Recognition, w: Int, h: Int): List<Int> {
        val sx = if (r.width > 0 && w > 0) w.toDouble() / r.width else 1.0
        val sy = if (r.height > 0 && h > 0) h.toDouble() / r.height else 1.0
        return listOf((b.x * sx).toInt(), (b.y * sy).toInt(), ((b.x + b.w) * sx).toInt(), ((b.y + b.h) * sy).toInt())
    }

    private fun exifTime(f: File): Long? = runCatching {
        val v = ExifInterface(f.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(v)?.time
    }.getOrNull()

    private fun failedRecord(id: String, f: File, why: String) = ScreenshotRecord(
        id, f.absolutePath, f.length(), f.lastModified(), ScreenshotRecord.takenAt(f.name, null, f.lastModified()), System.currentTimeMillis(),
        0, 0, "", "und", emptyList(), emptyList(), emptyList(), emptyList(), mapOf("agent_version" to ScreenshotRecord.AGENT_VERSION.toString()), why,
    )
}
