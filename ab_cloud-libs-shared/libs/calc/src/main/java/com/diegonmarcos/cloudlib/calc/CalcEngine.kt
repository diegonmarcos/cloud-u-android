package com.diegonmarcos.cloudlib.calc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * libqalculate behind one lock. Created lazily by [CalcBackendService]; the first call loads
 * the compiled-in definitions (a few hundred ms) and every later call reuses them.
 *
 * Offline by construction: nothing here touches the network except [fetchRates], which a
 * client calls on purpose. It downloads the files libqalculate NAMES (qcore::rates_sources
 * reads Calculator::getExchangeRatesUrl/FileName), so no URL is restated in this repository,
 * through HttpURLConnection — Android's resolver, so the SuperApp's DNS menu applies (#741) —
 * into the engine's own files dir, then asks libqalculate to reload them. Until a fetch
 * succeeds the rates are the ones compiled into libqalculate, and `info.rates_time` says how
 * old they are.
 */
class CalcEngine(context: Context) {
    private val userDir = File(context.filesDir, "qalculate").apply { mkdirs() }
    private val lock = Any()
    @Volatile private var ready = false

    private fun str(b: ByteArray) = String(b, Charsets.UTF_8)
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun <T> native(block: () -> T): T = synchronized(lock) {
        if (!ready) {
            str(QalcNative.init(bytes(userDir.absolutePath)))
            ready = true
        }
        block()
    }

    fun info(): String = native {
        JSONObject(str(QalcNative.info()))
            .put("engine_release", BuildConfig.QALC_RELEASE)
            .put("contract", CONTRACT)
            .toString()
    }

    fun eval(expr: String, optionsJson: String?): String {
        if (expr.length > MAX_EXPR) return error("expression longer than $MAX_EXPR characters")
        val o = EvalOptions.parse(optionsJson)
        return native {
            str(QalcNative.eval(bytes(expr), o.inBase, o.outBase, o.precision, o.angle, o.approx, o.mixedUnits, o.unicode, o.timeoutMs))
        }
    }

    fun plot(expr: String, xmin: Double, xmax: Double, steps: Int): String {
        if (expr.length > MAX_EXPR) return error("expression longer than $MAX_EXPR characters")
        return native { str(QalcNative.plot(bytes(expr), xmin, xmax, steps, PLOT_TIMEOUT_MS)) }
    }

    fun complete(prefix: String, max: Int): String =
        native { str(QalcNative.complete(bytes(prefix), max.coerceIn(1, MAX_ITEMS))) }

    fun items(kind: String, category: String, max: Int): String =
        native { str(QalcNative.items(bytes(kind), bytes(category), max.coerceIn(1, MAX_ITEMS))) }

    fun ratesInfo(): String = native {
        val sources = JSONArray(str(QalcNative.ratesSources()))
        JSONObject().put("sources", sources)
            .put("time", ratesTime(sources, JSONObject(str(QalcNative.info())).optLong("rates_time")))
            .toString()
    }

    /** Download every rate file libqalculate names, then reload. Partial success is reported per source. */
    fun fetchRates(): String {
        val sources = JSONArray(native { str(QalcNative.ratesSources()) })
        val fetched = JSONArray()
        val failed = JSONArray()
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            val url = s.getString("url")
            val target = File(s.getString("file"))
            runCatching { download(url, target) }
                .onSuccess { fetched.put(url) }
                .onFailure { failed.put(JSONObject().put("url", url).put("error", it.message ?: it.toString())) }
        }
        val reload = JSONObject(native { str(QalcNative.reloadRates()) })
        return JSONObject().put("ok", fetched.length() > 0 && reload.optBoolean("ok"))
            .put("fetched", fetched).put("failed", failed)
            .put("time", ratesTime(sources, reload.optLong("time"))).toString()
    }

    /** The rates' own date (the ECB file's), never a cached or compiled-in stamp when the file is there. */
    private fun ratesTime(sources: JSONArray, fallback: Long): Long =
        RatesDate.of((0 until sources.length()).map { sources.getJSONObject(it).let { s -> s.getString("url") to s.getString("file") } }, fallback)

    private fun download(url: String, target: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = NET_TIMEOUT_MS
        c.readTimeout = NET_TIMEOUT_MS
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            // Bounded read by hand: InputStream.readNBytes is API 33, the engine runs from 26.
            val body = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() <= MAX_RATES_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            if (body.isEmpty()) throw IllegalStateException("empty body")
            if (body.size > MAX_RATES_BYTES) throw IllegalStateException("larger than $MAX_RATES_BYTES bytes")
            target.parentFile?.mkdirs()
            // Written beside and renamed over, so libqalculate never reads half a file.
            val part = File(target.path + ".part")
            part.writeBytes(body)
            if (!part.renameTo(target)) throw IllegalStateException("could not replace ${target.name}")
        } finally {
            c.disconnect()
        }
    }

    private fun error(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    companion object {
        /** The CONTRACT this engine serves; the manifest meta-data declares the same number. */
        const val CONTRACT = 1
        const val MAX_EXPR = 4000
        const val MAX_ITEMS = 2000
        const val PLOT_TIMEOUT_MS = 5000
        const val NET_TIMEOUT_MS = 15_000
        const val MAX_RATES_BYTES = 2 * 1024 * 1024
    }
}
