package com.diegonmarcos.capitalfinterm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** A blocking GET that answers the body text or throws IOException. The app's one network seam; the tests give it a fake. */
interface Http {
    @Throws(IOException::class)
    fun get(url: String): String

    /** A bodiless POST (the container actions); the answer's text. */
    @Throws(IOException::class)
    fun post(url: String): String
}

/**
 * The real GET: HttpURLConnection, so names are resolved by Android's own resolver (the SuperApp's Configs > Mesh > DNS choice applies,
 * no resolver of our own), https only, bounded size and time. It carries no credential and logs nothing.
 */
class UrlHttp(
    private val connectMs: Int = 10_000,
    private val readMs: Int = 25_000,
    private val maxBytes: Int = 4_000_000,
    /** Hosts allowed over plain http: the private mesh names (WireGuard encrypts the hop), taken from the baked fleet declaration. */
    private val cleartextHosts: Set<String> = emptySet(),
) : Http {
    override fun get(url: String): String = call("GET", url)

    override fun post(url: String): String = call("POST", url)

    private fun call(method: String, url: String): String {
        val u = URL(url)
        val plain = u.protocol == "http" && u.host in cleartextHosts
        if (u.protocol != "https" && !plain) throw IOException("https only")
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.requestMethod = method
            c.instanceFollowRedirects = false
            if (method == "POST") {
                c.doOutput = true
                c.outputStream.use { it.write(ByteArray(0)) }
            }
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "application/json, text/csv, application/xml, */*")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("answer too large")
                }
                return out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val AGENT = "Capital-Stack-FinTerminal"
    }
}

/** Remembers an answer for [ttlMs], so two panels reading the same series (or a re-opened dashboard) cost one request. */
class CachedHttp(private val inner: Http, private val ttlMs: Long = 60_000L, private val clock: () -> Long = System::currentTimeMillis) : Http {
    private val seen = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Forgets every answer, so a refresh asks the sources again. */
    fun clear() = seen.clear()

    override fun get(url: String): String {
        val now = clock()
        seen[url]?.let { (at, body) -> if (now - at < ttlMs) return body }
        val body = inner.get(url)
        seen[url] = now to body
        return body
    }

    override fun post(url: String): String = inner.post(url).also { seen.clear() }
}
