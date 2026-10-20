package com.diegonmarcos.superapp.notificationcenter

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import com.diegonmarcos.superapp.decisions.link.Verdict
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #883 (Jev wave 2, spec section 3 item 3): may a FRESH alert make a sound, or is it not worth interrupting
 * for? The decision model only SUGGESTS: a confident "not worth it" makes the alert arrive silently (still in
 * the shade and on the Alerts page, never dropped, never muted), and ANYTHING else (no engine installed, no
 * consent, offline, over budget, a timeout past [WAIT_MS], the use's evidence gate still closed, an unsure
 * answer) leaves the alert exactly as today: it rings. The app holds no key; it asks the decisions engine
 * through libs:decisions-link and the engine answers "no opinion" for every refusal.
 *
 * OUTCOME, so the use's gate can open without a manual label: an alert the user swipes away within
 * [QUICK_DISMISS_MS] of its arrival was not worth the interruption; that is reported to the engine for the
 * decision's call id ([settle]). Only quick dismissals are observed, so the evidence is conservative.
 */
object JevNotify {

    const val WAIT_MS = 1500L
    const val QUICK_DISMISS_MS = 2 * 60_000L
    private const val MAX_CALLS = 200

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "jev-notify").apply { isDaemon = true } }

    internal fun questions(): JSONObject = JSONObject().put(
        "worth", JSONObject().put("type", "noul").put(
            "instructions",
            "A phone alert raised by app `app` with severity `severity` and title `title`, at hour `hour` of the day, " +
                "is worth interrupting the person with a sound right now.",
        ),
    )

    /** Names the alert, never its body text. */
    internal fun state(a: AlertStore.Alert, hour: Int): JSONObject =
        JSONObject().put("app", a.app).put("severity", a.severity).put("title", a.title).put("hour", hour)

    /** True only for a confident "not worth it"; no verdict, unsure or "worth it" = today's behaviour (it rings). */
    internal fun quiet(v: Verdict?): Boolean = v?.yes("worth") == false

    /** The observed label: a quick swipe-away means it was not worth a sound; nothing else is observed. */
    internal fun worthLabel(ageMs: Long): Boolean? = if (ageMs in 0 until QUICK_DISMISS_MS) false else null

    /** Ids of the alerts to show without a sound. Blocking up to [WAIT_MS]; never call on the main thread. */
    fun quietIds(ctx: Context, fresh: AlertStore.Alert?): Set<String> {
        if (fresh == null) return emptySet()
        val app = ctx.applicationContext
        val task = pool.submit(Callable {
            val o = DecisionsLink.outcome(app, "alert_interrupt", state(fresh, Calendar.getInstance().get(Calendar.HOUR_OF_DAY)), questions())
            o.callOrNull?.let { remember(fresh.id, it) }
            quiet(o.verdictOrNull)
        })
        val quiet = runCatching { task.get(WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        return if (quiet) setOf(fresh.id) else emptySet()
    }

    /** [alert] was swiped away at [now]: report what that says about the decision made for it. */
    fun settle(ctx: Context, alert: AlertStore.Alert?, now: Long = System.currentTimeMillis()) {
        val a = alert ?: return
        val app = ctx.applicationContext
        val call = recall(a.id) ?: return
        forget(a.id)
        val label = worthLabel(now - a.ts) ?: return
        pool.execute { DecisionsLink.report(app, "alert_interrupt", call, JSONObject().put("worth", label)) }
    }

    /** alert id -> the decision's call id. In memory and bounded: a call lost with the process is a missing label, not a wrong one. */
    private val calls = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_CALLS
    }

    @Synchronized private fun remember(alertId: String, call: String) { calls[alertId] = call }

    @Synchronized private fun recall(alertId: String): String? = calls[alertId]

    @Synchronized private fun forget(alertId: String) { calls.remove(alertId) }
}
