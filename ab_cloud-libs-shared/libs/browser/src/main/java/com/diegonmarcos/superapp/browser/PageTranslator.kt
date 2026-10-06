package com.diegonmarcos.superapp.browser

/**
 * #886 drives one in-place page translation: collect a batch of text nodes from the page, translate
 * it with the chosen [PageTranslate.Backend] OFF the main thread, write it back, and go round again
 * until nothing is left, so a long page is translated progressively, from where the reader is,
 * rather than in one request. Toggling off restores every original node. Pure of android: the page
 * and the threads arrive through [Io], so [PageTranslateTest] runs the whole loop against a fake page.
 *
 * A translation belongs to ONE document: [abandon] (a navigation) ends it without touching the new
 * page, and a batch that comes back after [restore] or [abandon] is dropped by its job number.
 */
class PageTranslator(private val io: Io, private val onState: (State) -> Unit) {

    interface Io {
        /** Main thread: run translate_collect.js; [done] gets its raw answer. */
        fun collect(maxChars: Int, done: (String?) -> Unit)
        /** Main thread: run translate_apply.js with [mapJson]. */
        fun apply(mapJson: String, done: (String?) -> Unit)
        /** Main thread: run translate_restore.js. */
        fun restore(done: (String?) -> Unit)
        /** Run [work] off the main thread (the binder blocks). */
        fun background(work: () -> Unit)
        /** Run [work] on the main thread. */
        fun main(work: () -> Unit)
    }

    sealed class State {
        object Idle : State()
        data class Working(val translated: Int, val remaining: Int) : State()
        data class Done(val translated: Int) : State()
        data class Failed(val reason: String, val translated: Int) : State()
    }

    var state: State = State.Idle
        private set

    /** True from [start] until [restore] / [abandon]: the page is (being) shown translated. */
    var active: Boolean = false
        private set

    private var job = 0
    private var running = false
    private var backend: PageTranslate.Backend? = null
    private var tag = "en"
    private var count = 0

    private fun set(s: State) { state = s; onState(s) }

    fun start(backend: PageTranslate.Backend, targetTag: String) {
        job++
        this.backend = backend; tag = targetTag; count = 0
        active = true; running = true
        set(State.Working(0, 0))
        step(job)
    }

    /** New content appeared (the reader scrolled, the page loaded more): translate what is unclaimed. */
    fun more() {
        if (!active || running) return
        running = true
        step(job)
    }

    /** Toggle off: every translated node gets its original text back. */
    fun restore() {
        job++; running = false; active = false; count = 0
        io.restore { set(State.Idle) }
    }

    /** The document went away: forget the job without touching whatever page is there now. */
    fun abandon() {
        job++; running = false; active = false; count = 0
        if (state != State.Idle) set(State.Idle)
    }

    private fun step(j: Int) {
        val be = backend ?: return
        io.collect(be.maxChars) { raw ->
            if (j != job) return@collect
            val c = PageTranslate.parseCollect(raw)
            if (c.items.isEmpty()) { running = false; set(State.Done(count)); return@collect }
            set(State.Working(count, c.remaining))
            io.background {
                val results = LinkedHashMap<Int, String>()
                var error: String? = null
                for (b in PageTranslate.batches(c.items, be.maxChars, be.maxItems)) {
                    val o = be.translate(b, tag)
                    results.putAll(o.results)
                    if (o.error != null) { error = o.error; break }
                }
                io.main {
                    if (j != job) return@main
                    val finish = {
                        count += results.size
                        when {
                            error != null && count == 0 -> {
                                // Nothing was written: release the claimed nodes so a retry starts clean.
                                active = false; running = false
                                io.restore { set(State.Failed(error, 0)) }
                            }
                            error != null -> { running = false; set(State.Failed(error, count)) }
                            else -> step(j)
                        }
                    }
                    if (results.isEmpty()) finish() else io.apply(PageTranslate.applyMap(results)) { finish() }
                }
            }
        }
    }
}
