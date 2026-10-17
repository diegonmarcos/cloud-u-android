package com.diegonmarcos.superapp.browser.download

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/** Why a running transfer stopped before its end. */
enum class DlStop { PAUSE, CANCEL, NETWORK_POLICY, TIMEOUT }

/** The switch the queue flips to stop a transfer; the engine checks it between buffers. */
class DlControl {
    private val reason = AtomicReference<DlStop?>(null)
    @Volatile private var conns: List<HttpURLConnection> = emptyList()
    val stop: DlStop? get() = reason.get()
    fun request(r: DlStop) {
        reason.compareAndSet(null, r)
        conns.forEach { runCatching { it.disconnect() } }   // unblocks a read waiting on a dead socket
    }
    @Synchronized internal fun track(c: HttpURLConnection) { conns = conns + c }
    @Synchronized internal fun untrack(c: HttpURLConnection) { conns = conns - c }
}

/** Free bytes where the file lives; injectable so a test can fill the disk. */
fun interface DlDisk { fun usable(dir: File): Long }

/** A shared token bucket: every transfer's bytes go through it, so the limit is global. 0 = unlimited. */
class DlRateLimiter(@Volatile var bytesPerSec: Long = 0) {
    private var tokens = 0.0
    private var last = System.nanoTime()

    fun acquire(n: Int) {
        while (true) {
            val limit = bytesPerSec
            if (limit <= 0) return
            val wait: Long
            synchronized(this) {
                val now = System.nanoTime()
                tokens = minOf(limit.toDouble(), tokens + (now - last) / 1e9 * limit)
                last = now
                if (tokens >= n || tokens >= limit) { tokens -= n; return }
                wait = (((n - tokens) / limit) * 1000).toLong().coerceIn(1, 250)
            }
            Thread.sleep(wait)
        }
    }
}

sealed class DlOutcome {
    abstract val job: DlJob
    data class Completed(override val job: DlJob) : DlOutcome()
    data class Stopped(override val job: DlJob, val reason: DlStop) : DlOutcome()
    data class Failed(override val job: DlJob, val error: String, val retryable: Boolean) : DlOutcome()
}

/**
 * The HTTP(S) transfer: resumable with Range + If-Range (ETag, else Last-Modified), optional
 * segmented download over several connections, a global speed limit, free-space checks before
 * and during the transfer, and a size + checksum check at the end. Plain JVM
 * (HttpURLConnection); the queue (DlQueue) owns retries, persistence and the service.
 *
 * Resume rules (the unit tests pin each):
 *  - 206 whose Content-Range starts at our offset -> append.
 *  - 200 to a ranged request -> the server has no range support or the validator changed
 *    (If-Range failed): truncate and restart from byte 0 with that same response.
 *  - 416 -> complete when we already hold the declared size, else restart.
 */
class DlHttpEngine(
    private val limiter: DlRateLimiter = DlRateLimiter(),
    private val disk: DlDisk = DlDisk { it.usableSpace },
    private val segmentThreshold: Long = 16L * 1024 * 1024,
    private val progressEveryBytes: Long = 256L * 1024,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /** Bytes kept free on the disk no matter what. */
    var reserveBytes: Long = 64L * 1024 * 1024

    fun run(job0: DlJob, file: File, maxSegments: Int, control: DlControl, onProgress: (DlJob) -> Unit): DlOutcome {
        file.parentFile?.mkdirs()
        var job = job0.copy(file = file.path, error = null)
        if (job.segments.isNotEmpty() && job.segments.any { !it.complete }) {
            when (val o = runSegmented(job, file, control, onProgress)) {
                is SegResult.Done -> return o.outcome
                is SegResult.Fallback -> job = o.job
            }
        } else if (job.segments.isNotEmpty()) {
            return finish(job.copy(downloaded = job.segments.sumOf { it.done }), file)
        }
        return runSingle(job, file, maxSegments, control, onProgress, restarted = false)
    }

    private fun runSingle(job0: DlJob, file: File, maxSegments: Int, control: DlControl,
                          onProgress: (DlJob) -> Unit, restarted: Boolean): DlOutcome {
        var job = job0
        val have = if (file.exists()) file.length() else 0L
        if (have != job.downloaded && have > 0) job = job.copy(downloaded = have)
        if (have == 0L) job = job.copy(downloaded = 0)
        val offset = job.downloaded
        val conn = open(job, if (offset > 0) offset else null, null, job.validator.takeIf { offset > 0 })
        control.track(conn)
        try {
            val code = try { conn.responseCode } catch (e: IOException) {
                return stoppedOr(control, job) ?: DlOutcome.Failed(job, "Network: ${e.message ?: e.javaClass.simpleName}", true)
            }
            val etag = conn.getHeaderField("ETag")
            val lastMod = conn.getHeaderField("Last-Modified")
            val ranges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            when {
                code == 416 -> {
                    if (job.total > 0 && offset >= job.total) return finish(job, file)
                    if (restarted) return DlOutcome.Failed(job, "Server refuses the range", false)
                    file.delete()
                    return runSingle(job.copy(downloaded = 0, etag = null, lastModified = null), file, maxSegments, control, onProgress, true)
                }
                code == 206 -> {
                    val cr = parseContentRange(conn.getHeaderField("Content-Range"))
                    val changed = job.etag != null && etag != null && etag != job.etag
                    if (cr == null || cr.first != offset || changed) {
                        if (restarted) return DlOutcome.Failed(job, "Server sent the wrong range", false)
                        conn.disconnect(); file.delete()
                        return runSingle(job.copy(downloaded = 0, etag = null, lastModified = null), file, maxSegments, control, onProgress, true)
                    }
                    job = job.copy(acceptRanges = true, total = if (cr.third > 0) cr.third else job.total,
                        etag = etag ?: job.etag, lastModified = lastMod ?: job.lastModified)
                }
                code in 200..299 -> {
                    // A full body. With an offset this means no range support, or the validator changed.
                    val reason = when {
                        offset == 0L -> null
                        job.validator != null && (etag ?: lastMod) != null && (etag ?: lastMod) != job.validator -> "file changed on the server"
                        else -> "server has no range support"
                    }
                    if (offset > 0) { file.delete() }
                    job = job.copy(downloaded = 0, total = conn.contentLengthLong, etag = etag, lastModified = lastMod,
                        acceptRanges = ranges, error = reason?.let { "Restarted: $it" })
                    // Large file on a range-capable server with a strong validator: split it.
                    if (offset == 0L && maxSegments > 1 && ranges && job.total >= segmentThreshold && job.validator != null) {
                        spaceError(file, job.total)?.let { return DlOutcome.Failed(job, it, false) }
                        conn.disconnect(); control.untrack(conn)
                        val seg = plan(job.total, maxSegments)
                        RandomAccessFile(file, "rw").use { it.setLength(job.total) }
                        return when (val o = runSegmented(job.copy(segments = seg), file, control, onProgress)) {
                            is SegResult.Done -> o.outcome
                            is SegResult.Fallback -> runSingle(o.job, file, 1, control, onProgress, true)
                        }
                    }
                }
                else -> {
                    val msg = "HTTP $code"
                    return DlOutcome.Failed(job, msg, DlBackoff.retryable(code))
                }
            }
            if (job.total > 0) spaceError(file, job.total - job.downloaded)?.let { return DlOutcome.Failed(job, it, false) }
            onProgress(job)
            val append = job.downloaded > 0
            val out = java.io.FileOutputStream(file, append)
            var done = job.downloaded
            var sinceTick = 0L; var sinceDisk = 0L
            try {
                conn.inputStream.use { input ->
                    out.use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            stoppedOr(control, job.copy(downloaded = done))?.let { o.flush(); return it }
                            val n = try { input.read(buf) } catch (e: IOException) {
                                o.flush()
                                return stoppedOr(control, job.copy(downloaded = done))
                                    ?: DlOutcome.Failed(job.copy(downloaded = done), "Network: ${e.message ?: "connection lost"}", true)
                            }
                            if (n < 0) break
                            limiter.acquire(n)
                            o.write(buf, 0, n)
                            done += n; sinceTick += n; sinceDisk += n
                            if (sinceTick >= progressEveryBytes) { sinceTick = 0; o.flush(); onProgress(job.copy(downloaded = done)) }
                            if (sinceDisk >= DISK_CHECK_EVERY) {
                                sinceDisk = 0
                                if (disk.usable(file.parentFile ?: file) < reserveBytes / 2)
                                    return DlOutcome.Failed(job.copy(downloaded = done), "Not enough free space", false)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                return stoppedOr(control, job.copy(downloaded = done))
                    ?: DlOutcome.Failed(job.copy(downloaded = done), "Network: ${e.message ?: "connection lost"}", true)
            }
            job = job.copy(downloaded = done)
            if (job.total > 0 && done < job.total) return DlOutcome.Failed(job, "Connection ended early", true)
            if (job.total < 0) job = job.copy(total = done)
            onProgress(job)
            return finish(job, file)
        } finally {
            control.untrack(conn)
            conn.disconnect()
        }
    }

    private sealed class SegResult {
        data class Done(val outcome: DlOutcome) : SegResult()
        data class Fallback(val job: DlJob) : SegResult()
    }

    /** Download every incomplete segment on its own connection, into the pre-sized file. */
    private fun runSegmented(job0: DlJob, file: File, control: DlControl, onProgress: (DlJob) -> Unit): SegResult {
        val segs = job0.segments
        val done = AtomicLongArray(segs.size).also { a -> segs.forEachIndexed { i, s -> a.set(i, s.done) } }
        val failure = AtomicReference<Pair<String, Boolean>?>(null)
        val fallback = AtomicReference(false)
        val local = DlControl()
        fun snapshot() = job0.copy(segments = segs.mapIndexed { i, s -> s.copy(done = done.get(i)) },
            downloaded = (0 until segs.size).sumOf { done.get(it) })
        val lock = Any()
        var lastTick = 0L
        val threads = segs.mapIndexedNotNull { i, s ->
            if (s.complete) null else Thread {
                val start = s.start + done.get(i)
                val conn = open(job0, start, s.end, job0.validator)
                local.track(conn); control.track(conn)
                try {
                    val code = conn.responseCode
                    val cr = parseContentRange(conn.getHeaderField("Content-Range"))
                    if (code != 206 || cr == null || cr.first != start) {
                        if (code in 200..299 || code == 206 || code == 416) fallback.set(true)
                        else failure.compareAndSet(null, "HTTP $code" to DlBackoff.retryable(code))
                        local.request(DlStop.CANCEL); return@Thread
                    }
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(start)
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var pos = start
                            while (pos <= s.end) {
                                if (control.stop != null || local.stop != null) return@Thread
                                val want = minOf(buf.size.toLong(), s.end - pos + 1).toInt()
                                val n = input.read(buf, 0, want)
                                if (n < 0) break
                                limiter.acquire(n)
                                raf.write(buf, 0, n)
                                pos += n
                                done.addAndGet(i, n.toLong())
                                synchronized(lock) {
                                    val total = (0 until segs.size).sumOf { done.get(it) }
                                    if (total - lastTick >= progressEveryBytes) { lastTick = total; onProgress(snapshot()) }
                                }
                            }
                            if (pos <= s.end) failure.compareAndSet(null, "Connection ended early" to true)
                        }
                    }
                } catch (e: IOException) {
                    if (control.stop == null && local.stop == null)
                        failure.compareAndSet(null, "Network: ${e.message ?: "connection lost"}" to true)
                    local.request(DlStop.CANCEL)
                } finally {
                    local.untrack(conn); control.untrack(conn); conn.disconnect()
                }
            }.apply { name = "dl-seg-$i"; start() }
        }
        threads.forEach { it.join() }
        val job = snapshot()
        control.stop?.let { return SegResult.Done(DlOutcome.Stopped(job, it)) }
        if (fallback.get()) {
            file.delete()
            return SegResult.Fallback(job0.copy(segments = emptyList(), downloaded = 0, etag = null, lastModified = null,
                acceptRanges = false, error = "Restarted: server stopped serving ranges"))
        }
        failure.get()?.let { (msg, retry) -> return SegResult.Done(DlOutcome.Failed(job, msg, retry)) }
        onProgress(job)
        return SegResult.Done(finish(job, file))
    }

    /** Size, then checksum when one was given. */
    private fun finish(job: DlJob, file: File): DlOutcome {
        val len = file.length()
        if (job.total > 0 && len != job.total)
            return DlOutcome.Failed(job.copy(downloaded = 0, segments = emptyList()).also { file.delete() },
                "Size mismatch: ${len} of ${job.total} bytes", true)
        job.checksum?.takeIf { it.isNotBlank() }?.let { want ->
            val got = digest(file, want.substringBefore(':', "sha256"))
            if (got == null || !got.equals(want.substringAfter(':'), ignoreCase = true))
                return DlOutcome.Failed(job, "Checksum mismatch", false)
        }
        return DlOutcome.Completed(job.copy(downloaded = len, total = len, segments = emptyList(), error = null))
    }

    private fun stoppedOr(control: DlControl, job: DlJob): DlOutcome? = control.stop?.let { DlOutcome.Stopped(job, it) }

    private fun spaceError(file: File, need: Long): String? {
        val free = disk.usable(file.parentFile ?: file)
        return if (need > 0 && free - need < reserveBytes) "Not enough free space: needs ${DlFormat.bytes(need)}, ${DlFormat.bytes(free)} free" else null
    }

    private fun open(job: DlJob, from: Long?, to: Long?, ifRange: String?): HttpURLConnection {
        val c = URL(job.url).openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept-Encoding", "identity")   // a gzip body breaks byte ranges
        job.userAgent?.let { c.setRequestProperty("User-Agent", it) }
        job.cookie?.let { c.setRequestProperty("Cookie", it) }
        job.referer?.let { c.setRequestProperty("Referer", it) }
        if (from != null) {
            c.setRequestProperty("Range", "bytes=$from-${to ?: ""}")
            ifRange?.let { c.setRequestProperty("If-Range", it) }
        }
        return c
    }

    companion object {
        const val DISK_CHECK_EVERY = 8L * 1024 * 1024

        /** "bytes 100-199/1000" -> (100, 199, 1000); total -1 when "*". */
        fun parseContentRange(h: String?): Triple<Long, Long, Long>? {
            val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(h ?: return null) ?: return null
            return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull() ?: -1)
        }

        /** [total] bytes in [n] near-equal segments, none smaller than 1 MiB. */
        fun plan(total: Long, n: Int): List<DlSegment> {
            val count = n.coerceAtMost((total / (1024 * 1024)).toInt().coerceAtLeast(1)).coerceAtLeast(1)
            val size = total / count
            return (0 until count).map { i ->
                val s = i * size
                DlSegment(s, if (i == count - 1) total - 1 else s + size - 1)
            }
        }

        fun digest(file: File, algo: String): String? = runCatching {
            val md = MessageDigest.getInstance(when (algo.lowercase()) { "md5" -> "MD5"; "sha1", "sha-1" -> "SHA-1"; "sha512" -> "SHA-512"; else -> "SHA-256" })
            file.inputStream().use { s: InputStream ->
                val b = ByteArray(64 * 1024)
                while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}

/**
 * The engine plus retries: a retryable failure (network drop, 5xx, 429, short body) waits an
 * exponential backoff and resumes from where it stopped; a final one (4xx, checksum, disk) ends.
 * [networkOk] is asked before each retry: false parks the job as WAITING_NETWORK instead.
 */
class DlRunner(
    private val engine: DlHttpEngine,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val maxAttempts: Int = DlBackoff.MAX_ATTEMPTS,
    private val backoff: (Int) -> Long = { DlBackoff.delayMs(it) },
) {
    fun run(job0: DlJob, file: File, segments: Int, control: DlControl, networkOk: () -> Boolean = { true },
            onRetry: (DlJob, Long) -> Unit = { _, _ -> }, onProgress: (DlJob) -> Unit): DlOutcome {
        var job = job0
        while (true) {
            val o = engine.run(job, file, segments, control, onProgress)
            if (o !is DlOutcome.Failed || !o.retryable || control.stop != null) return o
            job = o.job.copy(attempts = o.job.attempts + 1, error = o.error)
            if (job.attempts >= maxAttempts) return DlOutcome.Failed(job, o.error, true)
            if (!networkOk()) return DlOutcome.Stopped(job, DlStop.NETWORK_POLICY)
            val wait = backoff(job.attempts - 1)
            onRetry(job.copy(nextAttemptAt = System.currentTimeMillis() + wait), wait)
            var left = wait
            while (left > 0) {
                if (control.stop != null) return DlOutcome.Stopped(job, control.stop!!)
                val step = minOf(left, 250L); sleeper(step); left -= step
            }
        }
    }
}
