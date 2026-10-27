package com.diegonmarcos.superapp.bluetooth

enum class LogKind { Sys, Tx, Rx, Notify, Error }

data class LogEntry(val timeMs: Long, val kind: LogKind, val text: String)

/** The ATT-level console (Debug tab): a bounded ring, newest last, exportable as text. Thread-safe. */
class BleLog(private val capacity: Int = 500, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val entries = ArrayDeque<LogEntry>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun add(kind: LogKind, text: String) {
        synchronized(entries) {
            entries.addLast(LogEntry(clock(), kind, text))
            while (entries.size > capacity) entries.removeFirst()
        }
        listeners.forEach { it() }
    }

    fun snapshot(): List<LogEntry> = synchronized(entries) { entries.toList() }
    fun clear() { synchronized(entries) { entries.clear() }; listeners.forEach { it() } }
    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun removeOnChange(l: () -> Unit) { listeners.remove(l) }

    /** `HH:MM:SS.mmm TX text`, one entry per line, filtered to [kinds] when given. */
    fun export(kinds: Set<LogKind>? = null): String =
        snapshot().filter { kinds == null || it.kind in kinds }.joinToString("\n") { line(it) }

    companion object {
        fun line(e: LogEntry): String {
            val t = e.timeMs
            val s = t / 1000 % 86400
            return "%02d:%02d:%02d.%03d %-6s %s".format(s / 3600, s / 60 % 60, s % 60, t % 1000, e.kind.name.uppercase(), e.text)
        }
    }
}
