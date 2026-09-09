package com.diegonmarcos.superapp.updater.source

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * THE ONE STREAMING DOWNLOAD LOOP.
 *
 * There were three: [GhcrClient.blob], ReleaseSource's inline loop in
 * ApkSource, and ApkInstallWorker's private `download`. Same 64 kB copy written
 * three times, and — exactly as the three copies of sha256 did before them —
 * their guarantees drifted apart. Only ApkInstallWorker chased redirects by
 * hand; only two of the three throttled their progress callbacks; NONE of them
 * could resume, so every one restarted at byte zero after any interruption.
 *
 * ## Why zero-resume was survivable until it wasn't
 *
 * Every app in the constellation is between 6 and 33 MB. A transfer that size
 * completes inside a single TCP session on almost any link, so the restart path
 * was effectively never taken and its absence cost nothing. cloud-sheets
 * (Collabora Office, one 278,215,660-byte layer — eight times the next largest
 * app) does NOT complete inside one session on a phone: the screen locks, the
 * process is backgrounded, the radio hands over between cells. Each of those
 * ends the read, and without a Range request the next attempt threw away every
 * byte already on disk. A download that restarts from zero every few minutes
 * never finishes, and — because the retry immediately republished
 * `Downloading(0, …)` — it never looked like it had failed either.
 *
 * ## What this guarantees
 *
 * - **Streamed to disk, always.** 64 kB at a time into a `.part` sibling; the
 *   full artifact is never held in memory. A 265 MB ByteArray on a phone is an
 *   OutOfMemoryError, and nothing here can allocate one.
 * - **Resumed with HTTP Range.** The `.part` file SURVIVES a failure on
 *   purpose — that is the thing being resumed. GitHub's release CDN and GHCR
 *   both answer `accept-ranges: bytes` (measured 2026-09-09 against
 *   Cloud-Sheets.apk).
 * - **Bounded by a stall watchdog, not by a total deadline.** A call timeout
 *   would cap the whole transfer and so would kill a big file on a healthy
 *   connection purely for being big — the wrong control. What must be bounded
 *   is a transfer that has stopped MOVING: [STALL_MS] with zero new bytes
 *   fails loudly with a message naming how far it got. A slow link that keeps
 *   delivering keeps going, however long it takes.
 * - **Progress from bytes actually written**, and `total = -1` when the server
 *   declined to say — never a hard zero that reads as stuck.
 */
internal object Download {

    private const val TAG = "Updater/Download"

    /** One read may block this long. This is the in-attempt stall detector:
     *  the socket dying quietly is what it catches, and it is a READ timeout
     *  rather than a whole-call timeout precisely so a 265 MB transfer is not
     *  killed for taking 265 MB worth of time. */
    private const val READ_MS = 60_000
    private const val CONNECT_MS = 15_000

    /** No new bytes for this long, across retries, and the transfer is dead
     *  rather than slow. The user is told so, with the byte count reached. */
    const val STALL_MS = 120_000L

    /** Attempts that make ZERO progress before giving up. An attempt that does
     *  move bytes resets this, so a flaky link is retried indefinitely as long
     *  as it is still advancing — which is the honest reading of "slow". */
    private const val MAX_DEAD_ATTEMPTS = 5

    private const val RETRY_BACKOFF_MS = 2_000L
    private const val MAX_REDIRECTS = 5
    private const val PROGRESS_TICK_MS = 80L

    /** A transfer that stopped moving. Distinct from a plain [IOException] so
     *  callers can say "stalled" rather than the generic "download failed" —
     *  the two need different things from the user (wait / check the link). */
    class Stalled(message: String) : IOException(message)

    /**
     * Stream [url] into [target], resuming any partial download already on disk.
     *
     * [expectedBytes] is the size the caller already learned from the OCI
     * manifest or a HEAD probe, or <= 0 when unknown. It is used for two
     * things and nothing else: to recognise a `.part` that is already complete
     * (so an interruption at 99% costs one rename rather than 265 MB), and to
     * throw away a `.part` LONGER than the artifact, which cannot be a prefix
     * of it and would otherwise be resumed into garbage.
     *
     * [onProgress] receives bytes actually written to disk and the total, or -1
     * for an unknown total. Throttled to one call per [PROGRESS_TICK_MS].
     *
     * Returns the finished file's length. Throws [Stalled] when the transfer
     * stops moving, [CancellationException] on cancel, [IOException] otherwise.
     */
    fun toFile(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        expectedBytes: Long = -1L,
        shouldCancel: () -> Boolean = { false },
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): Long {
        val part = File(target.parentFile, target.name + ".part")

        // A .part longer than the artifact is not a prefix of it — it is a
        // leftover from a DIFFERENT build that happened to use this filename
        // (the release source names its file per app, not per version). Resuming
        // that would splice two builds together into a file that is the right
        // length and not an APK.
        //
        // ponytail: length is the only cheap identity signal here. A replaced
        // asset of EXACTLY the same size would still be resumed wrongly; the
        // caller's digest/zip verification is what catches that, and it deletes
        // the part. Upgrade path if that ever bites: store the ETag beside the
        // part and send If-Range.
        if (expectedBytes > 0 && part.isFile && part.length() > expectedBytes) {
            Log.w(TAG, "discarding ${part.name}: ${part.length()} B exceeds the " +
                "expected $expectedBytes B — not a prefix of this artifact")
            part.delete()
        }
        // Already complete on disk: an interruption between the last byte and
        // the rename must not cost the whole download again.
        if (expectedBytes > 0 && part.isFile && part.length() == expectedBytes) {
            onProgress?.invoke(expectedBytes, expectedBytes)
            return finish(part, target)
        }

        var lastMovedAt = System.currentTimeMillis()
        var deadAttempts = 0
        while (true) {
            if (shouldCancel()) throw CancellationException("download cancelled")
            val before = if (part.isFile) part.length() else 0L
            val failure: IOException? = try {
                streamOnce(url, part, headers, before, shouldCancel, onProgress)
                null
            } catch (c: CancellationException) {
                throw c
            } catch (io: IOException) {
                io
            }
            if (failure == null) break

            val after = if (part.isFile) part.length() else 0L
            if (after > before) {
                // The link is alive and the file grew; this was an interruption,
                // not a stall. Resuming from `after` is the entire point.
                lastMovedAt = System.currentTimeMillis()
                deadAttempts = 0
                Log.i(TAG, "resuming ${target.name} at $after B after: ${failure.message}")
            } else {
                deadAttempts++
                val stillMs = System.currentTimeMillis() - lastMovedAt
                if (deadAttempts >= MAX_DEAD_ATTEMPTS || stillMs >= STALL_MS) {
                    throw Stalled(
                        "stalled at ${after} of " +
                            (if (expectedBytes > 0) "$expectedBytes" else "unknown") +
                            " bytes — no new data for ${stillMs / 1000}s over " +
                            "$deadAttempts attempts (${failure.message})"
                    )
                }
                Log.w(TAG, "no progress on ${target.name} (attempt $deadAttempts): ${failure.message}")
            }
            Thread.sleep(RETRY_BACKOFF_MS)
        }
        return finish(part, target)
    }

    /**
     * Drop both the finished file and any partial one.
     *
     * Called when verification rejects what was downloaded: the bytes on disk
     * are known-bad, so leaving the `.part` would have the next attempt resume
     * ON TOP of them forever. Resume is only ever an optimisation over a prefix
     * we still trust.
     */
    fun discard(target: File) {
        target.delete()
        File(target.parentFile, target.name + ".part").delete()
    }

    /** Move the completed part into place. Rename can only fail across
     *  filesystems — both live in cacheDir — but copy rather than lose the
     *  download if it ever does. */
    private fun finish(part: File, target: File): Long {
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target.length()
    }

    /**
     * One connection attempt: request from [resumeFrom], append what arrives.
     *
     * Redirects are chased by hand rather than with `instanceFollowRedirects`,
     * for three reasons that all bite this path. HttpURLConnection will not
     * follow across protocols; it re-sends our request properties to whatever
     * host it lands on, which would hand a GHCR bearer token to a CDN; and a
     * GitHub release asset is two hops to a presigned URL whose signature is
     * only valid for an hour, so the ORIGINAL url must be the thing retried,
     * not the expired presigned one.
     */
    private fun streamOnce(
        url: String,
        part: File,
        headers: Map<String, String>,
        resumeFrom: Long,
        shouldCancel: () -> Boolean,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val origin = URL(url).host
        var current = url
        var hops = 0
        while (true) {
            if (shouldCancel()) throw CancellationException("download cancelled")
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = false
                // Credentials go to the host they were minted for and nowhere
                // else — the same rule curl follows on a cross-host redirect.
                // The presigned CDN url carries its own authorisation in the
                // query string and rejects a second one.
                if (URL(current).host == origin) headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code with no Location for $current")
                conn.disconnect()
                if (++hops > MAX_REDIRECTS) throw IOException("too many redirects for $url")
                current = URL(URL(current), location).toString()
                continue
            }
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.readText()
                conn.disconnect()
                throw IOException("HTTP $code for $current: $body")
            }
            // 206 = the server honoured Range and is sending the REMAINDER.
            // 200 after asking for a range = it ignored Range and is resending
            // the WHOLE artifact, so appending would concatenate two copies;
            // the part has to be truncated back to zero and refilled.
            val append = code == 206
            if (resumeFrom > 0 && !append)
                Log.w(TAG, "server ignored Range for $current — restarting from zero")
            val startAt = if (append) resumeFrom else 0L
            val total = totalBytes(conn, startAt, append)
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = startAt
                    var lastTick = 0L
                    while (true) {
                        if (shouldCancel()) throw CancellationException("download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= PROGRESS_TICK_MS) {
                            onProgress?.invoke(written, total)
                            lastTick = now
                        }
                    }
                    output.fd.sync()
                    onProgress?.invoke(written, total)
                }
            }
            return
        }
    }

    /**
     * Full artifact size, or -1 when the server declined to say.
     *
     * On a 206 the Content-Length is what REMAINS, not the whole file, so
     * reporting it as the total would make a resumed download show a progress
     * bar against the wrong denominator — 90% of the last 10%. Content-Range's
     * "bytes 100-999/1000" carries the real total; fall back to summing only
     * when it is absent or unparseable.
     */
    private fun totalBytes(conn: HttpURLConnection, startAt: Long, append: Boolean): Long {
        if (append) {
            conn.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()
                ?.toLongOrNull()?.let { return it }
        }
        val len = conn.contentLengthLong
        if (len < 0) return -1L
        return if (append) startAt + len else len
    }
}
