package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject

/** One small JSON document the engine keeps (the ledger, the answer cache, the consent flags). */
interface KvStore {
    /** The stored document, or null when there is none or it cannot be read: callers start empty. */
    fun read(): JSONObject?

    /** Replace the document. A failed write is swallowed: losing a counter must never fail a call. */
    fun write(doc: JSONObject)
}

/** The journal's lines, newest last. */
interface LineSink {
    fun append(line: String)
    fun tail(n: Int): List<String>

    /** Keep only the newest [keep] lines. */
    fun trim(keep: Int)
}

/** The phone's state as far as a Decisions call is concerned. */
interface Env {
    fun online(): Boolean
    fun metered(): Boolean
    fun batterySaver(): Boolean
}

class MemoryStore(private var doc: JSONObject? = null) : KvStore {
    override fun read(): JSONObject? = doc?.let { JSONObject(it.toString()) }
    override fun write(doc: JSONObject) { this.doc = JSONObject(doc.toString()) }
}

class MemorySink : LineSink {
    val lines = ArrayList<String>()
    override fun append(line: String) { lines.add(line) }
    override fun tail(n: Int): List<String> = lines.takeLast(n)
    override fun trim(keep: Int) { while (lines.size > keep) lines.removeAt(0) }
}
