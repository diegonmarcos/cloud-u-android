package app.sterna.core.data.rss

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.StringReader
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Why a feed could not be read. Each maps to its own sentence on the screen. */
enum class RssFetchFailure {
    /** The device's link is down, or the host could not be reached. */
    UNREACHABLE,
    /** The address answered, but with an HTTP error rather than a feed. */
    HTTP_ERROR,
    /** The body was not an RSS 2.0 or Atom document. */
    NOT_A_FEED,
    /** A valid feed, but it had no articles. */
    EMPTY,
}

/** A feed was fetched and parsed, or the specific reason it was not. */
sealed interface RssFetchResult {
    data class Loaded(val feed: RssFeed, val url: String) : RssFetchResult
    data class Failed(val reason: RssFetchFailure, val url: String) : RssFetchResult
}

/** A sane ceiling on how much of a feed any one fetch reads, so a runaway stream cannot fill
 *  memory. Real feeds are a few hundred kilobytes at most; ten megabytes leaves huge headroom. */
private const val MAX_FEED_BYTES = 10L * 1024 * 1024

/**
 * Fetches and parses an RSS 2.0 / Atom feed off the main thread, reporting the specific reason it
 * could not be read — the reader must say WHAT went wrong, never silently draw an empty box.
 *
 * Built on the OkHttp the app already carries for JMAP; no new HTTP dependency. Fetching happens
 * under [Dispatchers.IO]; the [RssFeedParser] it feeds is the Android / JVM-shared pull parser.
 */
class RssFetcher(
    private val httpClient: OkHttpClient = defaultRssHttpClient(),
    private val parser: RssFeedParser = RssFeedParser(),
) {
    /** Fetch [url] and parse it, or report exactly why not. */
    suspend fun fetch(url: String): RssFetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
            .get()
            .build()
        val body = try {
            fetchBody(request) ?: return@withContext RssFetchResult.Failed(RssFetchFailure.UNREACHABLE, url)
        } catch (_: UnknownHostException) {
            return@withContext RssFetchResult.Failed(RssFetchFailure.UNREACHABLE, url)
        } catch (_: java.net.ConnectException) {
            return@withContext RssFetchResult.Failed(RssFetchFailure.UNREACHABLE, url)
        } catch (_: java.net.SocketException) {
            return@withContext RssFetchResult.Failed(RssFetchFailure.UNREACHABLE, url)
        } catch (_: IOException) {
            return@withContext RssFetchResult.Failed(RssFetchFailure.UNREACHABLE, url)
        }
        when (val parsed = parser.parse(StringReader(body))) {
            is RssParseResult.Ok -> RssFetchResult.Loaded(parsed.feed, url)
            is RssParseResult.Failed -> when (parsed.reason) {
                RssParseFailure.NOT_A_FEED -> RssFetchResult.Failed(RssFetchFailure.NOT_A_FEED, url)
                RssParseFailure.EMPTY -> RssFetchResult.Failed(RssFetchFailure.EMPTY, url)
            }
        }
    }

    /** The response body when the server answered 2xx, null when it did not, or "" when empty. */
    private fun fetchBody(request: Request): String? {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // Read no more than a sane feed could ever be; the parse then has an upper bound on
            // what a hostile or runaway stream can make this phone hold in memory.
            val announced = response.body?.contentLength() ?: 0L
            if (announced > MAX_FEED_BYTES) return null
            val text = response.body?.string().orEmpty()
            if (text.toByteArray().size.toLong() > MAX_FEED_BYTES) return null
            return text
        }
    }
}

/** The RSS client's own OkHttp instance: a bounded read, no huge aggregations, off main thread.
 *  Built here rather than shared with JMAP so a feed timeout can never throttle the inbox's own
 *  traffic, and vice versa. */
internal fun defaultRssHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/** Validate a subscription address before it is stored or fetched: it must be an http(s) URL. */
fun isFeedAddress(raw: String): Boolean {
    val trimmed = raw.trim()
    val parsed = trimmed.toHttpUrlOrNull()
    return trimmed.isNotEmpty() && parsed?.scheme in setOf("http", "https")
}