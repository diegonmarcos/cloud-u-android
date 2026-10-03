package com.diegonmarcos.superapp.browser

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The live page, as something a caller off the screen can act on: the fragment
 * implements it while it is resumed. Every call arrives on the MAIN thread (WebView
 * refuses any other) and answers through [done] exactly once, possibly later
 * (find, script extraction and translation all finish asynchronously).
 */
interface BrowserPageHost {
    fun facts(): Map<String, Boolean>
    fun act(id: String, args: Map<String, String>, done: (JSONObject) -> Unit)
}

/**
 * #802 how a write that did not come from the screen (a debug route, the fleet
 * import) reaches the live browser. The stores are the truth and the fragment
 * re-reads them; [post] only tells it WHEN. [call] runs an action on the live page
 * and waits for its answer. One listener and one page — there is one browser screen.
 * ponytail: a single listener, not a flow; add fan-out if a second screen needs it.
 */
object BrowserBus {
    const val SETTINGS = "settings"
    const val TABS = "tabs"
    const val OPEN = "open:"

    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var page: BrowserPageHost? = null

    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun post(change: String) {
        val l = listener ?: return
        main.post { l(change) }
    }

    /** Run [id] on the live page and wait (off the main thread) for its answer. */
    fun call(id: String, args: Map<String, String> = emptyMap(), timeoutMs: Long = 10_000): JSONObject {
        val host = page ?: return JSONObject().put("ok", false)
            .put("error", "the browser is not on screen: open it (or tabs/open?url=) first")
        return onMain(id, timeoutMs) { done -> host.act(id, args, done) }
    }

    /** Run [work] on the main thread and wait (off it) for the one answer it gives. */
    fun onMain(what: String, timeoutMs: Long = 10_000, work: (done: (JSONObject) -> Unit) -> Unit): JSONObject {
        val latch = CountDownLatch(1)
        var out: JSONObject? = null
        main.post {
            runCatching { work { if (out == null) { out = it; latch.countDown() } } }
                .onFailure { out = JSONObject().put("ok", false).put("error", "$what: $it"); latch.countDown() }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
            return JSONObject().put("ok", false).put("error", "$what: no answer in ${timeoutMs}ms")
        return out ?: JSONObject().put("ok", false)
    }

    /** The live page's facts (back/forward, reader, …) read on the main thread. */
    fun facts(): Map<String, Boolean> {
        val r = call("_facts")
        return r.optJSONObject("facts")?.let { f -> f.keys().asSequence().associateWith { f.optBoolean(it) } } ?: emptyMap()
    }
}
