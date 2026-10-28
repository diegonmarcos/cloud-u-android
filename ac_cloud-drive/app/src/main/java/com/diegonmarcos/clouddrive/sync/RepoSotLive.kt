package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The baked registry (REPOS_SOT_B64) is a snapshot: an installed build keeps listing what the registry has since
 * renamed or retired. On table open this asks the declared registry url with a CONDITIONAL GET (If-None-Match, a 304
 * moves no body), at most every [minIntervalMs], keeps the body and its ETag in [dir], and the cached copy replaces the
 * baked one at runtime. Pure over an injected [Fetcher] and clock, so the JVM suite drives every branch.
 */
object RepoSotLive {
    data class Response(val code: Int, val body: String?, val etag: String?)
    fun interface Fetcher { fun get(url: String, etag: String?): Response }

    enum class Outcome { SKIPPED_FRESH, NOT_MODIFIED, UPDATED, FAILED }

    private const val BODY = "repos-sot-live.json"
    private const val ETAG = "repos-sot-live.etag"
    private const val CHECKED = "repos-sot-live.checked"

    /** The cached live registry text, null when none was ever fetched. */
    fun cached(dir: File): String? = File(dir, BODY).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun refresh(dir: File, url: String, fetcher: Fetcher, minIntervalMs: Long, now: Long, force: Boolean = false): Outcome {
        if (url.isBlank()) return Outcome.FAILED
        val checked = File(dir, CHECKED).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        if (!force && cached(dir) != null && now - checked < minIntervalMs) return Outcome.SKIPPED_FRESH
        val etag = File(dir, ETAG).takeIf { it.isFile && cached(dir) != null }?.readText()?.trim()?.ifBlank { null }
        val r = runCatching { fetcher.get(url, etag) }.getOrNull() ?: return Outcome.FAILED
        return when {
            r.code == 304 -> { File(dir, CHECKED).writeText(now.toString()); Outcome.NOT_MODIFIED }
            r.code == 200 && r.body != null && valid(r.body) -> {
                dir.mkdirs()
                File(dir, BODY).writeText(r.body)
                File(dir, ETAG).writeText(r.etag.orEmpty())
                File(dir, CHECKED).writeText(now.toString())
                Outcome.UPDATED
            }
            else -> Outcome.FAILED // a bad answer never replaces a good cache
        }
    }

    /** A registry body is usable when it parses to at least one repository. */
    fun valid(body: String): Boolean = body.contains("cloud-repos/v1") && Declarations.parseRepoSot(body).repos.isNotEmpty()

    /** The real fetcher: HttpURLConnection with If-None-Match, short timeouts, the body capped at 2 MB. */
    val http = Fetcher { url, etag ->
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            val code = c.responseCode
            if (code != 200) Response(code, null, null)
            else Response(code, c.inputStream.use { it.readNBytes(2_000_000).toString(Charsets.UTF_8) }, c.getHeaderField("ETag"))
        } finally { c.disconnect() }
    }
}
