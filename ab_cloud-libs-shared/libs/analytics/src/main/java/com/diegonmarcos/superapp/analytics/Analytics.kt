package com.diegonmarcos.superapp.analytics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One shared analytics sink for every constellation app, reporting to BOTH
 * self-hosted backends: Umami (privacy-first, JSON) and Matomo (full sessions,
 * form-encoded).
 *
 * #871 The HTTP POST to both backends runs in the sink engine (Cloud-Lib-Analytics-Sink.apk,
 * [SinkLink]); this object keeps the per-app parts: queues, consent, visitor id, site ids.
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
    @Volatile private var link: SinkLink? = null
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
        link = SinkLink(context)
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
            // The engine is bound only for the length of a drain.
            link?.release()
        }
    }

    // #871 What an event looks like on the wire, and the POST itself, live in the sink engine
    // (libs/analytics-sink). This side hands it the per-app facts it cannot know: which app,
    // which sites, which visitor. The engine answers false for "keep it queued" - an absent or
    // down engine is therefore the same as a down backend: nothing lost, nothing sent.
    private fun sendUmami(name: String, props: Map<String, String>): Boolean {
        val base = BuildConfig.AN_UMAMI_URL
        val site = BuildConfig.AN_UMAMI_SITE
        if (base.isEmpty() || site.isEmpty()) return true // not configured: not a failure
        return link?.umami(request(base, site, name, props)) ?: false
    }

    private fun sendMatomo(name: String, props: Map<String, String>): Boolean {
        val base = BuildConfig.AN_MATOMO_URL
        val site = BuildConfig.AN_MATOMO_SITE
        if (base.isEmpty() || site.isEmpty()) return true
        return link?.matomo(request(base, site, name, props)) ?: false
    }

    private fun request(base: String, site: String, name: String, props: Map<String, String>) = JSONObject().apply {
        put("app", BuildConfig.AN_APP)
        put("base", base)
        put("site", site)
        put("name", name)
        put("props", JSONObject(props as Map<*, *>))
        put("visitor", visitorId)
        put("ua", userAgent)
    }
}
