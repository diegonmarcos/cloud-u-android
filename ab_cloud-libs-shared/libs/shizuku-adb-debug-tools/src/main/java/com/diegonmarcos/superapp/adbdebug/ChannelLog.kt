package com.diegonmarcos.superapp.adbdebug

/**
 * The channel's event log: a bounded in-memory ring (probe results, connects, failures, commands'
 * exit codes). Nothing is persisted, and nothing secret is ever kept: every line is passed through
 * [redact] BEFORE it is stored, so a token that reaches [add] is gone from the buffer, from copy and
 * from the screen alike.
 */
class ChannelLog(private val capacity: Int = 300, private val clock: () -> Long = System::currentTimeMillis) {

    data class Entry(val at: Long, val tag: String, val text: String)

    private val ring = ArrayDeque<Entry>()
    private val lock = Any()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun add(tag: String, text: String) {
        val e = Entry(clock(), tag, redact(text).replace('\n', ' ').take(MAX_LINE))
        synchronized(lock) {
            ring.addLast(e)
            while (ring.size > capacity) ring.removeFirst()
        }
        listeners.forEach { runCatching { it() } }
    }

    fun entries(): List<Entry> = synchronized(lock) { ring.toList() }
    val size: Int get() = synchronized(lock) { ring.size }

    fun clear() {
        synchronized(lock) { ring.clear() }
        listeners.forEach { runCatching { it() } }
    }

    /** The log as copyable text, oldest first, one line per event. */
    fun asText(): String = entries().joinToString("\n") { "${stamp(it.at)} [${it.tag}] ${it.text}" }

    /** Told after every change, so the page can redraw. */
    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    companion object {
        const val MAX_LINE = 400

        /** The process-wide log every part of the channel writes to and the page shows. */
        val shared = ChannelLog()

        private const val MASK = "[redacted]"

        // Order matters: the labelled forms first (they keep the label), then bare token shapes.
        private val BEARER = Regex("""(?i)\bbearer\s+[^\s,;]+""")
        private val LABELLED = Regex("""(?i)\b(token|secret|password|passwd|api[_-]?key|authorization|auth|credential|pairing[ _-]?code)\b(\s*[:=]\s*)[^\s,;]+""")
        private val CODE6 = Regex("""(?i)\b(code)\b([^\n]{0,12}?)\b\d{6}\b""")
        private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
        private val PREFIXED = Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9_-]{16,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{12,})""")
        private val HEX = Regex("""(?<![0-9A-Za-z])[0-9a-fA-F]{24,}(?![0-9A-Za-z])""")
        private val LONG = Regex("""(?<![A-Za-z0-9_+=-])[A-Za-z0-9_+=-]{32,}(?![A-Za-z0-9_+=-])""")

        /** Strip anything token-shaped from [s]. Ordinary diagnostics (uid, port, exit code, package names) survive. */
        fun redact(s: String): String {
            var t = s
            t = BEARER.replace(t, "Bearer $MASK")
            t = LABELLED.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
            t = CODE6.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
            t = JWT.replace(t, MASK)
            t = PREFIXED.replace(t, MASK)
            t = HEX.replace(t, MASK)
            t = LONG.replace(t, MASK)
            return t
        }

        private fun stamp(ms: Long): String {
            val sec = (ms / 1000) % 86_400
            return "%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)
        }
    }
}
