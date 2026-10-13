package com.diegonmarcos.clouddrive.agents

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The Screenshot agent's record, exactly as data/agent-screenshots-contract.json (v1) and
 * docs/screenshot-agent-contract.md publish it for other fleet apps (Cloud Calendar first).
 * Pure Kotlin: the id, the capture time, the entity extractor and the JSON round trip are
 * JVM-tested (ScreenshotRecordTest). A field added here is added to the contract in the same
 * commit; a field renamed or removed is a v2.
 */
data class ScreenshotRecord(
    val id: String,
    val sourcePath: String,
    val sourceSize: Long,
    val sourceMtimeMs: Long,
    val takenAtMs: Long,
    val processedAtMs: Long,
    val width: Int,
    val height: Int,
    val text: String,
    val lang: String,
    val blocks: List<Block>,
    val labels: List<Label>,
    val objects: List<Obj>,
    val entities: List<Entity>,
    val engine: Map<String, String>,
    val error: String?,
    /** Additive (v1.1): the dominant colours, biggest share first. */
    val colours: List<Colour> = emptyList(),
    /** Additive (v1.1): the first barcode / QR code in the image, when there is one. */
    val barcode: Barcode? = null,
    /** Additive (v1.1): the app the screenshot was taken in, when the file name says (Screenshot_..._Chrome.png). */
    val sourceApp: String? = null,
) {
    /** [left, top, right, bottom] in source pixels. */
    data class Line(val text: String, val bbox: List<Int>, val confidence: Double? = null)
    data class Block(val text: String, val bbox: List<Int>, val lines: List<Line>)
    data class Label(val label: String, val confidence: Double, val source: String)
    data class Obj(val bbox: List<Int>, val labels: List<Label>)
    data class Entity(val type: String, val text: String, val start: Int, val end: Int)
    data class Colour(val hex: String, val share: Double)
    data class Barcode(val format: String, val raw: String)

    val failed: Boolean get() = error != null && blocks.isEmpty() && labels.isEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", SCHEMA); put("id", id); put("source_path", sourcePath); put("source_size", sourceSize)
        put("source_mtime_ms", sourceMtimeMs); put("taken_at_ms", takenAtMs); put("processed_at_ms", processedAtMs)
        put("width", width); put("height", height); put("text", text); put("lang", lang)
        put("blocks", buildJsonArray {
            blocks.forEach { b ->
                add(buildJsonObject {
                    put("text", b.text); put("bbox", box(b.bbox))
                    put("lines", buildJsonArray { b.lines.forEach { l -> add(buildJsonObject { put("text", l.text); put("bbox", box(l.bbox)); l.confidence?.let { put("confidence", round(it)) } }) } })
                })
            }
        })
        put("labels", buildJsonArray { labels.forEach { add(buildJsonObject { put("label", it.label); put("confidence", round(it.confidence)); put("source", it.source) }) } })
        put("objects", buildJsonArray { objects.forEach { o -> add(buildJsonObject { put("bbox", box(o.bbox)); put("labels", buildJsonArray { o.labels.forEach { add(buildJsonObject { put("label", it.label); put("confidence", round(it.confidence)) }) } }) }) } })
        put("entities", buildJsonArray { entities.forEach { e -> add(buildJsonObject { put("type", e.type); put("text", e.text); put("start", e.start); put("end", e.end) }) } })
        put("engine", buildJsonObject { engine.forEach { (k, v) -> if (k == "agent_version") put(k, v.toIntOrNull() ?: AGENT_VERSION) else put(k, v) } })
        put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
        put("colours", buildJsonArray { colours.forEach { add(buildJsonObject { put("hex", it.hex); put("share", round(it.share)) }) } })
        put("barcode", barcode?.let { b -> buildJsonObject { put("format", b.format); put("raw", b.raw) } } ?: JsonNull)
        put("source_app", sourceApp?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** The index.jsonl line for this record. */
    fun indexLine(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", id); put("source_path", sourcePath); put("taken_at_ms", takenAtMs); put("processed_at_ms", processedAtMs); put("has_text", text.isNotBlank())
    })

    companion object {
        const val SCHEMA = "cloud-drive.agent.screenshots/1"
        const val AGENT_VERSION = 1

        private fun box(b: List<Int>) = JsonArray(b.map { JsonPrimitive(it) })
        private fun round(d: Double) = Math.round(d * 10000.0) / 10000.0

        /** First 16 hex chars of sha256(path \n size \n mtime): the contract's id. */
        fun idOf(path: String, size: Long, mtimeMs: Long): String =
            MessageDigest.getInstance("SHA-256").digest("$path\n$size\n$mtimeMs".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        private val NAME_STAMP = Regex("""(20\d{2})[-_]?(\d{2})[-_]?(\d{2})[-_ T]?(\d{2})[-_.]?(\d{2})[-_.]?(\d{2})""")

        /** Capture time: EXIF first (when the caller has it), then a timestamp in the file name (Screenshot_20261013-101500), then mtime. */
        fun takenAt(fileName: String, exifMs: Long?, mtimeMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            exifMs?.takeIf { it > 0 }?.let { return it }
            NAME_STAMP.find(fileName)?.let { m ->
                val (y, mo, d, h, mi, s) = m.destructured
                runCatching { return LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).atZone(zone).toInstant().toEpochMilli() }
            }
            return mtimeMs
        }

        /**
         * The app a screenshot was taken in, when Android put it in the file name: Pixel writes
         * Screenshot_20261013-101500_Chrome.png, One UI Screenshot_20261013_101500_com.whatsapp.jpg.
         */
        fun sourceAppOf(fileName: String): String? {
            val stem = fileName.substringBeforeLast('.')
            val m = Regex("""^Screenshot[_-]\d{8}[-_]\d{6}(?:\d{0,3})?[_-](.+)$""", RegexOption.IGNORE_CASE).find(stem) ?: return null
            return m.groupValues[1].trim().takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
        }

        private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec|january|february|march|april|june|july|august|september|october|november|december|ene|abr|ago|dic|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre"
        private val PATTERNS: List<Pair<String, Regex>> = listOf(
            "url" to Regex("""\bhttps?://[^\s<>"]+|\bwww\.[\w-]+(\.[\w-]+)+[^\s<>"]*""", RegexOption.IGNORE_CASE),
            "email" to Regex("""\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b"""),
            "date" to Regex("""\b(\d{4}-\d{2}-\d{2}|\d{1,2}[/.]\d{1,2}[/.]\d{2,4}|\d{1,2}\s+(?:de\s+)?(?:$MONTHS)\.?(?:\s+(?:de\s+)?\d{4})?|(?:$MONTHS)\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?)\b""", RegexOption.IGNORE_CASE),
            "time" to Regex("""\b([01]?\d|2[0-3])[:h][0-5]\d(?:\s?[ap]\.?m\.?)?\b|\b(1[0-2]|0?[1-9])\s?[ap]\.?m\.?(?![\w])""", RegexOption.IGNORE_CASE),
            "phone" to Regex("""(?<![\w])\+?\d[\d\s().-]{7,}\d(?![\w])"""),
        )

        /** Dates, times, URLs, e-mails and phone numbers in [text], with offsets; an earlier, longer match wins an overlap. */
        fun entities(text: String): List<Entity> {
            val found = PATTERNS.flatMap { (type, re) ->
                re.findAll(text).map { m ->
                    val v = m.value.trimEnd('.', ',', ';', ')')
                    Entity(type, v, m.range.first, m.range.first + v.length)
                }
            }
            val kept = ArrayList<Entity>()
            for (e in found.sortedWith(compareBy<Entity> { it.start }.thenByDescending { it.end - it.start })) {
                if (kept.none { e.start < it.end && it.start < e.end }) kept.add(e)
            }
            return kept
        }

        fun fromJson(o: JsonObject): ScreenshotRecord {
            fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull
            fun s(k: String) = str(o[k]).orEmpty()
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            fun i(e: JsonElement?) = (e as? JsonPrimitive)?.intOrNull ?: 0
            fun d(e: JsonElement?) = str(e)?.toDoubleOrNull()
            fun bb(e: JsonElement?) = (e as? JsonArray)?.map { i(it) }.orEmpty()
            fun objs(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return ScreenshotRecord(
                id = s("id"), sourcePath = s("source_path"), sourceSize = l("source_size"), sourceMtimeMs = l("source_mtime_ms"),
                takenAtMs = l("taken_at_ms"), processedAtMs = l("processed_at_ms"),
                width = i(o["width"]), height = i(o["height"]),
                text = s("text"), lang = s("lang"),
                blocks = objs(o["blocks"]).map { b -> Block(str(b["text"]).orEmpty(), bb(b["bbox"]), objs(b["lines"]).map { ln -> Line(str(ln["text"]).orEmpty(), bb(ln["bbox"]), d(ln["confidence"])) }) },
                labels = objs(o["labels"]).map { Label(str(it["label"]).orEmpty(), d(it["confidence"]) ?: 0.0, str(it["source"]).orEmpty()) },
                objects = objs(o["objects"]).map { ob -> Obj(bb(ob["bbox"]), objs(ob["labels"]).map { Label(str(it["label"]).orEmpty(), d(it["confidence"]) ?: 0.0, "object-detection") }) },
                entities = objs(o["entities"]).map { Entity(str(it["type"]).orEmpty(), str(it["text"]).orEmpty(), i(it["start"]), i(it["end"])) },
                engine = (o["engine"] as? JsonObject)?.mapValues { str(it.value).orEmpty() }.orEmpty(),
                error = str(o["error"]),
                colours = objs(o["colours"]).map { Colour(str(it["hex"]).orEmpty(), d(it["share"]) ?: 0.0) },
                barcode = (o["barcode"] as? JsonObject)?.let { Barcode(str(it["format"]).orEmpty(), str(it["raw"]).orEmpty()) },
                sourceApp = str(o["source_app"]),
            )
        }

        fun parse(text: String): ScreenshotRecord? = runCatching { fromJson(Json.parseToJsonElement(text).jsonObject) }.getOrNull()

    }
}
