package com.diegonmarcos.superapp.browser.download

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.min

/**
 * The download manager's data model: what one download is, its state, and the pure
 * rules over it (backoff, names, formatting). Plain JVM: the JVM unit tests run it
 * without Android.
 */
enum class DlState {
    QUEUED, RUNNING, PAUSED, WAITING_NETWORK, RETRYING, FAILED, COMPLETED, CANCELLED;

    val active get() = this == QUEUED || this == RUNNING || this == RETRYING || this == WAITING_NETWORK
    val finished get() = this == COMPLETED || this == CANCELLED
}

/** One byte range of a segmented download: [start]..[end] inclusive, [done] bytes written from [start]. */
data class DlSegment(val start: Long, val end: Long, val done: Long = 0) {
    val length get() = end - start + 1
    val complete get() = done >= length
    val next get() = start + done
}

/**
 * One download. [kind] is "http" or "torrent". The request inputs (cookie, agent,
 * referer) are kept so a resume after a reboot is still signed in.
 * [file] is the path in app-specific storage while it downloads; [publishedUri] is
 * the MediaStore (or shared-store) address once it is published.
 */
data class DlJob(
    val id: String,
    val url: String,
    val fileName: String,
    val mime: String = "",
    val dir: String = "",
    val kind: String = KIND_HTTP,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val checksum: String? = null,
    val state: DlState = DlState.QUEUED,
    val total: Long = -1,
    val downloaded: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptRanges: Boolean? = null,
    val segments: List<DlSegment> = emptyList(),
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val error: String? = null,
    val file: String = "",
    val publishedUri: String? = null,
    val saveToSharedStore: Boolean = false,
    val createdAt: Long = 0,
    val finishedAt: Long = 0,
    /** Torrent only: the info-hash, the files chosen (indices), sequential order, seeding. */
    val infoHash: String? = null,
    val selectedFiles: List<Int>? = null,
    val sequential: Boolean = false,
    val seed: Boolean = false,
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val validator: String? get() = etag?.takeIf { !it.startsWith("W/") } ?: lastModified

    companion object {
        const val KIND_HTTP = "http"
        const val KIND_TORRENT = "torrent"
    }
}

/** Live numbers that are not worth persisting: bytes per second and seconds left. */
data class DlLive(val bytesPerSec: Long = 0, val etaSec: Long = -1, val peers: Int = 0, val seeds: Int = 0,
                  val upPerSec: Long = 0, val fileProgress: List<Pair<String, Float>> = emptyList())

/** The queue's policy, written by the browser's settings and read by the service. */
data class DlPolicy(
    val parallel: Int = 3,
    val wifiOnly: Boolean = false,
    val allowMetered: Boolean = true,
    val speedLimitKbps: Int = 0,
    val segments: Int = 4,
    val sharedStore: Boolean = false,
    val seedRatio: Int = 0,
    val seedMinutes: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("parallel", parallel).put("wifiOnly", wifiOnly).put("allowMetered", allowMetered)
        .put("speedLimitKbps", speedLimitKbps).put("segments", segments).put("sharedStore", sharedStore)
        .put("seedRatio", seedRatio).put("seedMinutes", seedMinutes)

    companion object {
        fun fromJson(raw: String?): DlPolicy {
            val o = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
            val d = DlPolicy()
            return DlPolicy(
                o.optInt("parallel", d.parallel).coerceIn(1, 8), o.optBoolean("wifiOnly", d.wifiOnly),
                o.optBoolean("allowMetered", d.allowMetered), o.optInt("speedLimitKbps", 0).coerceAtLeast(0),
                o.optInt("segments", d.segments).coerceIn(1, 8), o.optBoolean("sharedStore", false),
                o.optInt("seedRatio", 0).coerceAtLeast(0), o.optInt("seedMinutes", 0).coerceAtLeast(0))
        }
    }
}

/** JSON (de)serialisation of the jobs list, pure and JVM-tested. */
object DlCodec {
    fun toJson(jobs: List<DlJob>): String = JSONArray().also { arr -> jobs.forEach { arr.put(job(it)) } }.toString()

    fun job(j: DlJob): JSONObject = JSONObject().apply {
        put("id", j.id); put("url", j.url); put("fileName", j.fileName); put("mime", j.mime); put("dir", j.dir)
        put("kind", j.kind); putOpt("userAgent", j.userAgent); putOpt("referer", j.referer); putOpt("cookie", j.cookie)
        putOpt("checksum", j.checksum); put("state", j.state.name); put("total", j.total); put("downloaded", j.downloaded)
        putOpt("etag", j.etag); putOpt("lastModified", j.lastModified); j.acceptRanges?.let { put("acceptRanges", it) }
        put("segments", JSONArray().also { a -> j.segments.forEach { a.put(JSONArray().put(it.start).put(it.end).put(it.done)) } })
        put("attempts", j.attempts); put("nextAttemptAt", j.nextAttemptAt); putOpt("error", j.error); put("file", j.file)
        putOpt("publishedUri", j.publishedUri); put("saveToSharedStore", j.saveToSharedStore)
        put("createdAt", j.createdAt); put("finishedAt", j.finishedAt); putOpt("infoHash", j.infoHash)
        j.selectedFiles?.let { s -> put("selectedFiles", JSONArray().also { a -> s.forEach { a.put(it) } }) }
        put("sequential", j.sequential); put("seed", j.seed)
    }

    fun fromJson(raw: String?): List<DlJob> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { parse(it) }.getOrNull() } }
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    fun parse(o: JSONObject): DlJob {
        val segs = o.optJSONArray("segments") ?: JSONArray()
        val sel = o.optJSONArray("selectedFiles")
        return DlJob(
            id = o.getString("id"), url = o.optString("url"), fileName = o.optString("fileName"), mime = o.optString("mime"),
            dir = o.optString("dir"), kind = o.optString("kind", DlJob.KIND_HTTP), userAgent = o.str("userAgent"),
            referer = o.str("referer"), cookie = o.str("cookie"), checksum = o.str("checksum"),
            state = runCatching { DlState.valueOf(o.optString("state")) }.getOrDefault(DlState.PAUSED),
            total = o.optLong("total", -1), downloaded = o.optLong("downloaded"), etag = o.str("etag"),
            lastModified = o.str("lastModified"), acceptRanges = if (o.has("acceptRanges")) o.optBoolean("acceptRanges") else null,
            segments = (0 until segs.length()).mapNotNull { k -> segs.optJSONArray(k)?.let { DlSegment(it.optLong(0), it.optLong(1), it.optLong(2)) } },
            attempts = o.optInt("attempts"), nextAttemptAt = o.optLong("nextAttemptAt"), error = o.str("error"),
            file = o.optString("file"), publishedUri = o.str("publishedUri"), saveToSharedStore = o.optBoolean("saveToSharedStore"),
            createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"), infoHash = o.str("infoHash"),
            selectedFiles = sel?.let { a -> (0 until a.length()).map { a.optInt(it) } },
            sequential = o.optBoolean("sequential"), seed = o.optBoolean("seed"),
        )
    }
}

/** Retry timing: exponential with a cap and a little jitter, so a flapping server is not hammered. */
object DlBackoff {
    const val MAX_ATTEMPTS = 8
    fun delayMs(attempt: Int, jitter: Double = Math.random()): Long {
        val base = 2_000L shl min(attempt.coerceAtLeast(0), 8)          // 2s, 4s, 8s ... 512s
        return min(base, 300_000L) + (jitter * 1_000).toLong()          // capped at 5 min
    }

    /** HTTP statuses worth another try; everything else 4xx is final. */
    fun retryable(status: Int) = status == 408 || status == 425 || status == 429 || status >= 500
}

/** Names and sizes in words. */
object DlFormat {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0; var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bps: Long) = bytes(bps) + "/s"

    fun eta(sec: Long): String = when {
        sec < 0 -> "--"
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }

    /**
     * The file name a Content-Disposition header names: RFC 6266 `filename*=UTF-8''…` wins over
     * `filename="…"`; null when the header names none. Path parts are stripped (no ../ escapes).
     */
    fun dispositionName(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^';]+)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"'), m.groupValues[1].trim()) }.getOrNull()
            if (!v.isNullOrBlank()) return safeName(v)
        }
        Regex("""filename\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;]+))""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().replace("\\\"", "\"")
            if (v.isNotBlank()) return safeName(v)
        }
        return null
    }

    /** A name safe for one path segment. */
    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("""[\u0000-\u001f<>:"|?*]"""), "_").trim().trimStart('.')
        return clean.ifBlank { "download" }.take(200)
    }

    /** "name.ext" -> "name (2).ext" when [taken] says the name is in use. */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot); val ext = name.substring(dot)
        var i = 2
        while (taken("$stem ($i)$ext")) i++
        return "$stem ($i)$ext"
    }
}
