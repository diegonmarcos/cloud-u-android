package com.diegonmarcos.cloudcalc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Every rule a mode applies that is not drawing — pure functions, so the JVM suite holds each
 * mode to its golden behaviour without a device or an engine.
 */
object Logic {
    /** A keypad press on [text]: AC clears, DEL drops one character, = leaves it, else types. */
    fun press(text: String, key: Declarations.Key): String = when (key.action) {
        Declarations.Action.CLEAR -> ""
        Declarations.Action.DELETE -> text.dropLast(1)
        Declarations.Action.EVALUATE -> text
        null -> text + key.insert
    }

    /** The token under the cursor at the end of [text], for autocomplete: letters/digits/_ only. */
    fun lastWord(text: String): String = text.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        .let { if (it.firstOrNull()?.isLetter() == true) it else "" }

    /** Replace the last word of [text] with the completion [name]. */
    fun complete(text: String, name: String): String = text.dropLast(lastWord(text).length) + name

    private val PLACEHOLDER = Regex("""\{(\w+)\}""")

    /** The field ids a form expression names, in order. */
    fun placeholders(template: String): List<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()

    /** A form expression with every {field} replaced by that field's trimmed value. */
    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { values[it.groupValues[1]]?.trim().orEmpty() }

    /** The converter's expression: libqalculate's own `to` conversion. */
    fun convert(value: String, from: String, to: String): String = "(${value.trim()}) $from to $to"

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

    data class Entry(val mode: String, val expr: String, val result: String)

    /** Newest first, a repeat of the newest entry is not stored twice, at most [max] kept. */
    fun remember(history: List<Entry>, e: Entry, max: Int): List<Entry> =
        (if (history.firstOrNull() == e) history else listOf(e) + history).take(max)

    fun encode(history: List<Entry>): String = JSONArray().apply {
        history.forEach { put(JSONObject().put("mode", it.mode).put("expr", it.expr).put("result", it.result)) }
    }.toString()

    fun decode(json: String?): List<Entry> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getString("mode"), o.getString("expr"), o.getString("result")) } }
    }.getOrDefault(emptyList())
}
