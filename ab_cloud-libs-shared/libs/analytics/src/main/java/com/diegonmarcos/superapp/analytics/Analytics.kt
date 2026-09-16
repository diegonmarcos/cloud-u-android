package com.diegonmarcos.superapp.analytics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One shared analytics sink for every constellation app, reporting to BOTH
 * self-hosted backends: Umami (privacy-first, JSON) and Matomo (full sessions,
 * form-encoded).
 *
 * Why not the JS snippet the front pages use: that snippet only runs inside a
 * WebView, so it would see the handful of bundled HTML surfaces and none of the
 * native UI. It also loads Matomo through Tag Manager, whose container is the
 * exact piece that was serving an empty body. Both backends expose a plain HTTP
 * tracking API, so we speak that directly and skip the browser entirely.
 *
 * Opt-in: nothing is sent until [setConsent] is granted. The default is off,
 * and a denied consent also drops whatever is already queued.
 */
object Analytics {

    private const val PREFS = "cloud_analytics"
    private const val KEY_CONSENT = "consent"
    private const val KEY_VISITOR = "visitor_id"

    // Bounded on purpose, and bounded PER SINK. Offline events are worth
    // keeping across a short outage; they are not worth growing without limit
    // in a phone's memory, so the OLDEST are dropped once full — recent
    // activity is the useful part. Two sinks therefore hold at most 400 events
    // between them, which at the size these payloads run to is tens of
    // kilobytes: survivable on the oldest phone in the fleet, and far cheaper
    // than the unbounded growth an offline day would otherwise produce.
    internal const val MAX_QUEUE_PER_SINK = 200

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloud-analytics").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    // One queue per backend, NOT one shared queue. Umami being reachable must
    // never decide whether Matomo's copy of an event survives; see SinkQueue
    // for the full account of the defect this replaces.
    private val umamiQueue = SinkQueue("umami", MAX_QUEUE_PER_SINK)
    private val matomoQueue = SinkQueue("matomo", MAX_QUEUE_PER_SINK)

    // At most ONE drain task in flight. Without this, a burst of events at a
    // backend that is down queues one executor task per event, each paying the
    // 8 second connect timeout, and the phone spends minutes of radio and
    // battery re-learning the same outage. The caller is never blocked and no
    // event ever gets a thread of its own: there is exactly one daemon thread
    // above, and this keeps its backlog to one task.
    private val flushScheduled = AtomicBoolean(false)

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var consent = false

    /** Stable per-INSTALL id. Random, never a hardware/advertising identifier. */
    private val visitorId: String
        get() {
            val p = prefs ?: return "0000000000000000"
            p.getString(KEY_VISITOR, null)?.let { return it }
            val id = (0 until 16).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
            p.edit().putString(KEY_VISITOR, id).apply()
            return id
        }

    // Umami rejects/misattributes requests that arrive without a User-Agent —
    // it derives browser and OS from it, and a missing one is treated as a bot.
    private val userAgent: String
        get() = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "CloudSuperApp/${BuildConfig.AN_APP}"

    @JvmStatic
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Defaults ON for this fleet: these are self-hosted apps on the owner's
        // own devices reporting to the owner's own Umami/Matomo, so opt-in would
        // mean collecting nothing from anyone who never opens settings. Flip the
        // default to false before any public/Play distribution.
        consent = prefs?.getBoolean(KEY_CONSENT, true) ?: true
    }

    @JvmStatic
    fun setConsent(granted: Boolean) {
        consent = granted
        prefs?.edit()?.putBoolean(KEY_CONSENT, granted)?.apply()
        if (granted) flush() else { umamiQueue.clear(); matomoQueue.clear() }
    }

    @JvmStatic
    fun hasConsent(): Boolean = consent

    /** A screen view. [name] is a stable screen key, e.g. "settings". */
    @JvmStatic
    fun screen(name: String) = event("pageview", mapOf("screen" to name))

    /** A named event with optional string properties. */
    @JvmStatic
    @JvmOverloads
    fun event(name: String, props: Map<String, String> = emptyMap()) {
        if (!BuildConfig.AN_ENABLED) return
        val event = name to props
        umamiQueue.offer(event)
        matomoQueue.offer(event)
        if (consent) flush()
    }

    private fun flush() {
        if (!consent || !BuildConfig.AN_ENABLED) return
        if (!flushScheduled.compareAndSet(false, true)) return
        io.execute {
            // Cleared FIRST, so an event arriving while this drain runs still
            // schedules a follow-up rather than being left to sit until the
            // next one happens along.
            flushScheduled.set(false)
            // Each sink drains on ITS OWN result. One backend being down holds
            // that backend's events and nothing else; the other still
            // delivers. This is what replaces `sendUmami(..) or sendMatomo(..)`
            // — that expression did call both, but it reduced their two
            // outcomes to one boolean, so a success on either one discarded the
            // other's failed copy with no queue left holding it and no retry.
            umamiQueue.drain { (name, props) -> sendUmami(name, props) }
            matomoQueue.drain { (name, props) -> sendMatomo(name, props) }
        }
    }

    private fun sendUmami(name: String, props: Map<String, String>): Boolean {
        val base = BuildConfig.AN_UMAMI_URL
        val site = BuildConfig.AN_UMAMI_SITE
        if (base.isEmpty() || site.isEmpty()) return true // not configured: not a failure
        val screen = props["screen"] ?: name
        val payload = JSONObject().apply {
            put("website", site)
            put("hostname", BuildConfig.AN_APP)
            put("url", "/${BuildConfig.AN_APP}/$screen")
            put("title", screen)
            if (name != "pageview") put("name", name)
            if (props.isNotEmpty()) put("data", JSONObject(props as Map<*, *>))
        }
        val body = JSONObject().apply {
            put("type", "event")
            put("payload", payload)
        }.toString()
        return post("$base/api/send", body, "application/json")
    }

    private fun sendMatomo(name: String, props: Map<String, String>): Boolean {
        val base = BuildConfig.AN_MATOMO_URL
        val site = BuildConfig.AN_MATOMO_SITE
        if (base.isEmpty() || site.isEmpty()) return true
        val screen = props["screen"] ?: name
        val params = StringBuilder()
            .append("idsite=").append(enc(site))
            .append("&rec=1&apiv=1")
            .append("&_id=").append(visitorId)
            .append("&rand=").append(Random.nextInt(1_000_000))
            .append("&action_name=").append(enc("${BuildConfig.AN_APP}/$screen"))
            .append("&url=").append(enc("app://${BuildConfig.AN_APP}/$screen"))
        if (name != "pageview") {
            params.append("&e_c=").append(enc(BuildConfig.AN_APP))
                .append("&e_a=").append(enc(name))
        }
        // Raw Tracking API (matomo.php), NOT the Tag Manager container — the
        // container is the part that serves an empty body, and it needs a
        // browser to execute it anyway.
        return post("$base/matomo.php", params.toString(), "application/x-www-form-urlencoded")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun post(url: String, body: String, contentType: String): Boolean = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("User-Agent", userAgent)
        }
        conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray()) }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (e: Exception) {
        // LOG IT. A silent analytics client is undebuggable: this module shipped
        // wired into 12 apps and reported nothing, and the swallowed exception
        // meant the app produced no signal at all while the transport was broken
        // upstream. Never crash the host app - but never fail invisibly either.
        android.util.Log.w("cloud-analytics", "send failed: $url (${e.javaClass.simpleName}: ${e.message})")
        false
    }
}
