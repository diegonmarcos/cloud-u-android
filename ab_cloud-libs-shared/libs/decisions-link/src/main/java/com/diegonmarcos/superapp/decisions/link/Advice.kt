package com.diegonmarcos.superapp.decisions.link

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The shared plumbing of every Jev call site (#882, #883): never block a screen, remember what was suggested,
 * and report what happened to it so a use's evidence gate can open without a manual label.
 *
 * A SITE keeps the call itself (the decisions-use guard reads `DecisionsLink.outcome(ctx, "<use>", ...)` with a
 * string literal at the site) and hands it to [Suggestions.peekOrAsk] / [within] as a lambda; everything around it
 * lives here once:
 *
 *  - [within]: run a blocking engine call on a worker with a deadline; null on timeout = no opinion.
 *  - [Suggestions]: a bounded in-memory memo. A screen reads [Suggestions.peek] (instant); a miss starts ONE
 *    background ask and the next draw sees the answer. Until an answer exists the screen is exactly today's.
 *  - [Pending]: the suggestions made, kept in a file so a verdict can be judged days later: a user's explicit
 *    correction labels it; a suggestion still standing after the window counts as an implicit accept.
 */
object Advice {

    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "jev-advice").apply { isDaemon = true } }

    /** True on the UI thread; false anywhere else, and in a plain JVM test (where there is no Looper). */
    fun onMain(): Boolean = runCatching { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }.getOrDefault(false)

    /**
     * Run [task] with a deadline of [ms]; null when it threw or did not finish (the worker is left to finish alone).
     * NEVER waits on the main thread: there it answers null at once, which is no opinion.
     */
    fun <T : Any> within(ms: Long, task: () -> T?): T? {
        if (onMain()) return null
        return runCatching { pool.submit(Callable { task() }).get(ms, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    fun background(task: () -> Unit) { runCatching { pool.execute { runCatching(task) } } }

    /** A decision's pick and its id (what [DecisionsLink.report] joins on). */
    class Suggestion(val pick: String, val call: String?)

    /** Bounded memo of suggestions with a time to live; thread safe. */
    class Suggestions(private val max: Int = 256) {
        private class Entry(val s: Suggestion?, val until: Long)
        private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > max
        }
        private val flying = HashSet<String>()

        /** The standing suggestion for [key], or null (none yet, none wanted, or expired). Never blocks. */
        @Synchronized fun peek(key: String, now: Long = System.currentTimeMillis()): Suggestion? =
            map[key]?.takeIf { it.until > now }?.s

        /**
         * Peek, and when nothing is known start ONE background [ask] for [key] (null = no opinion, remembered for
         * [ttlMs] too so a dead engine is not asked again for every draw).
         */
        fun peekOrAsk(key: String, ttlMs: Long, now: Long = System.currentTimeMillis(), ask: () -> Suggestion?): Suggestion? {
            synchronized(this) {
                val e = map[key]
                if (e != null && e.until > now) return e.s
                if (!flying.add(key)) return null
            }
            background {
                val got = runCatching(ask).getOrNull()
                synchronized(this) { map[key] = Entry(got, System.currentTimeMillis() + ttlMs); flying.remove(key) }
            }
            return null
        }

        @Synchronized fun put(key: String, s: Suggestion?, ttlMs: Long, now: Long = System.currentTimeMillis()) {
            map[key] = Entry(s, now + ttlMs)
        }
    }

    /** One suggestion awaiting its verdict: [label] is what an implicit accept would report. */
    class Entry(val use: String, val call: String, val label: Any, val at: Long)

    /** The suggestions made, in a small file next to the app's other private files; bounded and tolerant of damage. */
    class Pending(private val file: File, private val max: Int = 200) {
        @Synchronized fun add(e: Entry) {
            val all = load().filter { it.call != e.call } + e
            save(all.takeLast(max))
        }

        /** An explicit correction already labelled [call]: forget it so it is not reported twice. */
        @Synchronized fun drop(call: String) { save(load().filter { it.call != call }) }

        /** Entries older than [windowMs], removed from the file and returned. */
        @Synchronized fun takeDue(now: Long, windowMs: Long): List<Entry> {
            val all = load()
            val due = all.filter { now - it.at >= windowMs }
            if (due.isNotEmpty()) save(all - due.toSet())
            return due
        }

        private fun load(): List<Entry> = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val label = o.opt("label") ?: return@mapNotNull null
                Entry(o.optString("use"), o.optString("call").ifEmpty { return@mapNotNull null }, label, o.optLong("at"))
            }
        }.getOrDefault(emptyList())

        private fun save(list: List<Entry>) {
            runCatching {
                file.parentFile?.mkdirs()
                val a = JSONArray()
                for (e in list) a.put(JSONObject().put("use", e.use).put("call", e.call).put("label", e.label).put("at", e.at))
                file.writeText(a.toString())
            }
        }
    }

    /** The pending file of this app. */
    fun pending(ctx: Context): Pending = Pending(File(ctx.applicationContext.filesDir, "jev/pending.json"))

    /** Default window after which a standing suggestion counts as accepted: a week. */
    const val IMPLICIT_ACCEPT_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Remember [s] for [use]; [label] is what accepting it means (the pick for a choice, true/false for a noul,
     * or a {question: label} object).
     */
    fun remember(ctx: Context, use: String, s: Suggestion, label: Any = s.pick, now: Long = System.currentTimeMillis()) {
        val call = s.call ?: return
        pending(ctx).add(Entry(use, call, label, now))
    }

    /** The user acted: report [label] for [call] now and forget the pending entry. */
    fun explicit(ctx: Context, use: String, call: String?, label: Any) {
        if (call == null) return
        pending(ctx).drop(call)
        background { DecisionsLink.report(ctx.applicationContext, use, call, label) }
    }

    /** Report every suggestion that stood past the window as an implicit accept. Cheap; call from any ask path. */
    fun settleDue(ctx: Context, now: Long = System.currentTimeMillis(), windowMs: Long = IMPLICIT_ACCEPT_MS) {
        val due = pending(ctx).takeDue(now, windowMs)
        if (due.isEmpty()) return
        background { for (e in due) DecisionsLink.report(ctx.applicationContext, e.use, e.call, e.label) }
    }
}
