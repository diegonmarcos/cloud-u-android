package com.diegonmarcos.cloudcalc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Every rule a mode applies that is not drawing — pure functions, so the JVM suite holds each
 * mode to its golden behaviour without a device or an engine.
 */
object Logic {
    /** The token under the cursor at the end of [text], for autocomplete: letters/digits/_ only. */
    fun lastWord(text: String): String = text.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        .let { if (it.firstOrNull()?.isLetter() == true) it else "" }

    private val PLACEHOLDER = Regex("""\{(\w+)\}""")

    /** The field ids a form expression names, in order. */
    fun placeholders(template: String): List<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()

    /** A form expression with every {field} replaced by that field's trimmed value. */
    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { values[it.groupValues[1]]?.trim().orEmpty() }

    /**
     * What the engine parses: the pretty operator glyphs a keyboard, a paste or a reused entry may carry
     * (÷ ∕ ／ ⁄ for division, × ✕ ⋅ · for multiplication, − for minus) become / * -. The text on screen is
     * left as typed; only what is sent to the engine is normalised.
     */
    fun normalize(expr: String): String = buildString(expr.length) {
        expr.forEach { c -> append(when (c) {
            '÷', '∕', '／', '⁄' -> '/'
            '×', '✕', '⋅', '·' -> '*'
            '−' -> '-'
            else -> c
        }) }
    }

    /** The converter's expression: libqalculate's own `to` conversion. */
    fun convert(value: String, from: String, to: String): String = "(${normalize(value).trim()}) $from to $to"

    /** A mode's eval options with [overrides] applied (a chip pick, a base column). */
    fun options(base: String, overrides: Map<String, Int>): String =
        JSONObject(base.ifBlank { "{}" }).apply { overrides.forEach { (k, v) -> put(k, v) } }.toString()

    data class Result(val ok: Boolean, val text: String, val messages: List<String>, val error: String)

    /** The engine's eval answer, or its {"error"} when the engine is not ready. */
    fun result(json: String): Result {
        val o = runCatching { JSONObject(json) }.getOrElse { return Result(false, "", emptyList(), json) }
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        return Result(
            ok = o.optBoolean("ok", false),
            text = o.optString("result"),
            messages = (0 until msgs.length()).map { msgs.getJSONObject(it).optString("text") },
            error = o.optString("error"),
        )
    }

    /** The current value of eval option [key]: an override, else the mode's own, else [def]. */
    fun optionValue(options: String, key: String, def: Int): Int =
        runCatching { JSONObject(options.ifBlank { "{}" }).optInt(key, def) }.getOrDefault(def)

    data class Item(val name: String, val title: String, val kind: String, val category: String)

    /** The engine's complete/items rows; an {"error"} answer is no rows. */
    fun items(json: String): List<Item> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Item(o.getString("name"), o.optString("title"), o.optString("kind"), o.optString("category")) } }
    }.getOrDefault(emptyList())

    data class Plot(val xs: List<Double>, val ys: List<Double?>, val error: String)

    /** The engine's plot answer: a y that is not a finite real arrives as null and stays a gap. */
    fun plot(json: String): Plot {
        val o = runCatching { JSONObject(json) }.getOrElse { return Plot(emptyList(), emptyList(), json) }
        val xs = o.optJSONArray("x") ?: JSONArray()
        val ys = o.optJSONArray("y") ?: JSONArray()
        return Plot(
            (0 until xs.length()).map { xs.optDouble(it) },
            (0 until ys.length()).map { if (ys.isNull(it)) null else ys.optDouble(it).takeIf { v -> v.isFinite() } },
            o.optString("error"),
        )
    }

    /** A kept result; [decision] is a follow-up question's JSON (#770 Addendum A), "" when none was asked. */
    data class Entry(val mode: String, val expr: String, val result: String, val decision: String = "", val ts: Long = 0L)

    /** Newest first, every press kept (a timestamp tells two presses apart), at most [max] kept. */
    fun remember(history: List<Entry>, e: Entry, max: Int): List<Entry> = (listOf(e) + history).take(max)

    fun encode(history: List<Entry>): String = JSONArray().apply {
        history.forEach { e ->
            put(JSONObject().put("mode", e.mode).put("expr", e.expr).put("result", e.result).apply { if (e.decision.isNotEmpty()) put("decision", e.decision); if (e.ts != 0L) put("ts", e.ts) })
        }
    }.toString()

    fun decode(json: String?): List<Entry> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getString("mode"), o.getString("expr"), o.getString("result"), o.optString("decision"), o.optLong("ts", 0L)) } }
    }.getOrDefault(emptyList())
}
