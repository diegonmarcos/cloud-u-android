package com.diegonmarcos.cloudcalc

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1329 the LaTeX page's rules, all pure so the JVM suite holds them without a device: the declaration
 * (build.json::latex), the formula catalog (data/assets/latex/formulas.json, generated from the owner's markdown), search and
 * category filtering, the user's "Mine" formulas and favourites (their stored JSON), the symbol palette's cursor-aware
 * insertion, and the LaTeX -> engine-expression conversion behind Evaluate. Nothing here draws; ui/LatexScreens.kt does.
 */
object Latex {
    data class Symbol(val label: String, val insert: String)
    data class Palette(val id: String, val label: String, val symbols: List<Symbol>)
    data class Category(val id: String, val label: String)
    /** build.json::latex.eval: what Evaluate may convert. */
    data class EvalRules(val commands: Map<String, String>, val ignored: Set<String>, val constants: Set<String>)
    data class Config(
        val viewer: String,
        val script: String,
        val autoRender: String,
        val style: String,
        val fontsDir: String,
        val bridge: String,
        val catalog: String,
        val mine: Category,
        val favourites: Category,
        val allLabel: String,
        val debounceMs: Long,
        val historyKind: String,
        val shareDir: String,
        val shareName: String,
        val shareMime: String,
        /** The light palette (JSON for viewer.js setTheme) a shared image is drawn in, whatever the app theme. */
        val shareTheme: String,
        val palette: List<Palette>,
        val eval: EvalRules,
    )

    /** One library entry. [number] is the owner's numbering (0 for a user's formula); [mine] marks a saved one. */
    data class Formula(
        val id: String,
        val number: Int,
        val title: String,
        val category: String,
        val tex: String,
        val description: String,
        val mine: Boolean = false,
    ) {
        /** "N. Title" as the list shows it; a user's formula has no number. */
        val heading: String get() = if (number > 0) "$number. $title" else title
    }

    data class Catalog(val categories: List<Category>, val formulas: List<Formula>)

    fun parseConfig(json: String): Config {
        val o = JSONObject(json)
        val r = o.getJSONObject("renderer")
        val sh = o.getJSONObject("share")
        val ev = o.getJSONObject("eval")
        return Config(
            viewer = r.getString("viewer"), script = r.getString("script"), autoRender = r.getString("auto_render"),
            style = r.getString("style"), fontsDir = r.getString("fonts_dir"), bridge = r.getString("bridge"),
            catalog = o.getString("catalog"),
            mine = category(o.getJSONObject("mine_category")), favourites = category(o.getJSONObject("favourites_category")),
            allLabel = o.getString("all_label"),
            debounceMs = o.optLong("preview_debounce_ms", 250L),
            historyKind = o.getString("history_kind"),
            shareDir = sh.getString("cache_dir"), shareName = sh.getString("image_name"), shareMime = sh.getString("mime"), shareTheme = sh.getJSONObject("image_theme").toString(),
            palette = o.getJSONArray("palette").objects().map { g ->
                Palette(g.getString("id"), g.getString("label"), g.getJSONArray("symbols").objects().map { Symbol(it.getString("label"), it.getString("insert")) })
            },
            eval = EvalRules(
                ev.getJSONObject("commands").let { c -> c.keys().asSequence().associateWith { c.getString(it) } },
                ev.getJSONArray("ignored").strings().toSet(),
                ev.getJSONArray("constants").strings().toSet(),
            ),
        )
    }

    /** The declaration gradle baked. */
    val config: Config by lazy { parseConfig(String(java.util.Base64.getDecoder().decode(BuildConfig.LATEX_CONFIG_B64), Charsets.UTF_8)) }

    private fun category(o: JSONObject) = Category(o.getString("id"), o.getString("label"))

    fun parseCatalog(json: String): Catalog {
        val o = JSONObject(json)
        return Catalog(
            o.getJSONArray("categories").objects().map { category(it) },
            o.getJSONArray("formulas").objects().map {
                Formula(it.getString("id"), it.optInt("number", 0), it.getString("title"), it.getString("category"), it.getString("latex"), it.optString("description"))
            },
        )
    }

    /** What the catalog is wrong about, or an empty list: the load-time guard the JVM suite also runs. */
    fun problems(c: Catalog): List<String> {
        val bad = mutableListOf<String>()
        if (c.formulas.map { it.id }.toSet().size != c.formulas.size) bad += "duplicate formula ids"
        c.formulas.forEach { f ->
            if (f.tex.isBlank()) bad += "${f.id}: empty LaTeX"
            if (c.categories.none { it.id == f.category }) bad += "${f.id}: unknown category ${f.category}"
        }
        return bad
    }

    /** The library's chips: All, Favourites, the catalog's categories in order, then Mine. */
    fun chips(c: Catalog, cfg: Config): List<Category> =
        listOf(Category("", cfg.allLabel), cfg.favourites) + c.categories + cfg.mine

    /**
     * The formulas a chip and a search show. [chip] "" = all, the favourites id = [favourites], the mine id = [mine] only;
     * [query] matches the title, the number, the description and the LaTeX source, case-insensitively, every word.
     */
    fun filter(c: Catalog, mine: List<Formula>, favourites: Set<String>, chip: String, query: String, cfg: Config): List<Formula> {
        val all = c.formulas + mine
        val base = when (chip) {
            "" -> all
            cfg.favourites.id -> all.filter { it.id in favourites }
            cfg.mine.id -> mine
            else -> all.filter { it.category == chip }
        }
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return base
        return base.filter { f ->
            val hay = (f.heading + " " + f.description + " " + f.tex).lowercase()
            words.all { it in hay }
        }
    }

    // ── My formulas and favourites: stored as JSON in the app's preferences ──────────────────────────────────────

    fun encodeMine(list: List<Formula>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("latex", it.tex).put("description", it.description)) }
    }.toString()

    fun decodeMine(json: String?, cfg: Config): List<Formula> = runCatching {
        JSONArray(json ?: "[]").objects().map {
            Formula(it.getString("id"), 0, it.getString("title"), cfg.mine.id, it.getString("latex"), it.optString("description"), mine = true)
        }
    }.getOrDefault(emptyList())

    fun encodeFavourites(ids: Set<String>): String = JSONArray(ids.sorted()).toString()
    fun decodeFavourites(json: String?): Set<String> = runCatching { JSONArray(json ?: "[]").strings().toSet() }.getOrDefault(emptySet())

    /** A fresh id for a saved formula, unique among [taken]. */
    fun newMineId(taken: Collection<String>, nowMs: Long): String {
        var t = nowMs
        while ("mine-$t" in taken) t++
        return "mine-$t"
    }

    // ── the editor's symbol palette ─────────────────────────────────────────────────────────────────────────────

    const val CURSOR = "\$0"

    data class Edited(val text: String, val cursor: Int)

    /**
     * Insert palette [snippet] into [text] over the selection [start]..[end]. The snippet's `$0` marks where the cursor
     * lands; a selected text goes there (it is wrapped), else the cursor ends after the snippet.
     */
    fun insertAt(text: String, start: Int, end: Int, snippet: String): Edited {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        val selected = text.substring(a, b)
        val at = snippet.indexOf(CURSOR)
        val body = snippet.replace(CURSOR, selected)
        val cursor = if (at < 0) a + snippet.length else a + at + selected.length
        return Edited(text.substring(0, a) + body + text.substring(b), cursor)
    }

    // ── Evaluate: LaTeX -> the engine's expression syntax ─────────────────────────────────────────────────────

    sealed class Conversion {
        data class Ok(val expr: String) : Conversion()
        data class Unsupported(val reason: String) : Conversion()
    }

    private class Stop(val reason: String) : Exception(reason)

    private sealed class Tok { data class Cmd(val name: String) : Tok(); data class Ch(val c: Char) : Tok() }

    private fun lex(tex: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < tex.length) {
            val c = tex[i]
            when {
                c == '\\' -> {
                    var j = i + 1
                    if (j < tex.length && tex[j].isLetter()) { while (j < tex.length && tex[j].isLetter()) j++ } else if (j < tex.length) j++
                    out += Tok.Cmd(tex.substring(i, j)); i = j
                }
                c.isWhitespace() -> i++
                else -> { out += Tok.Ch(c); i++ }
            }
        }
        return out
    }

    /**
     * Convert [tex] (frac, sqrt, ^, \pi, \cdot, functions, parentheses, numbers) to the expression the engine parses, or say why
     * not. A variable, a subscript, a sum or a relation is reported, never guessed.
     */
    fun toExpression(tex: String, rules: EvalRules): Conversion {
        val toks = lex(tex)
        if (toks.isEmpty()) return Conversion.Unsupported("nothing to evaluate")
        return try {
            val p = Parser(toks, rules)
            val e = p.sequence(null)
            if (p.pos != toks.size) throw Stop("unbalanced braces")
            val expr = e.trim()
            if (expr.isEmpty()) throw Stop("nothing to evaluate")
            Conversion.Ok(expr)
        } catch (s: Stop) {
            Conversion.Unsupported(s.reason)
        }
    }

    private class Parser(val t: List<Tok>, val rules: EvalRules) {
        var pos = 0

        /** Append [piece], keeping "2 pi" and "sin 2" apart from a glued token. */
        private fun emit(sb: StringBuilder, piece: String) {
            if (piece.isEmpty()) return
            val prev = sb.lastOrNull()
            if (prev != null && prev.isLetterOrDigit() && piece.first().isLetterOrDigit() && !(prev.isDigit() && piece.first().isDigit())) sb.append(' ')
            sb.append(piece)
        }

        /** Tokens until [close] (consumed) or the end. */
        fun sequence(close: Char?): String {
            val sb = StringBuilder()
            while (pos < t.size) {
                val k = t[pos]
                if (k is Tok.Ch && k.c == close) { pos++; return sb.toString() }
                if (k is Tok.Ch && k.c == '}') throw Stop("unbalanced braces")
                pos++
                emit(sb, one(k, pos >= t.size))
            }
            if (close != null) throw Stop("unbalanced braces")
            return sb.toString()
        }

        /** One argument: a {group} or the single next token. */
        private fun arg(): String {
            if (pos >= t.size) throw Stop("missing argument")
            val k = t[pos]
            if (k is Tok.Ch && k.c == '{') { pos++; val s = sequence('}'); if (s.isBlank()) throw Stop("empty group"); return s }
            pos++
            return one(k, false)
        }

        private fun one(k: Tok, last: Boolean): String = when (k) {
            is Tok.Ch -> when {
                k.c.isDigit() || k.c == '.' || k.c in "+-*/()!%," -> k.c.toString()
                k.c == '[' -> "("
                k.c == ']' -> ")"
                k.c == '{' -> { val s = sequence('}'); if (s.isBlank()) throw Stop("empty group"); "($s)" }
                k.c == '^' -> "^(" + arg() + ")"
                k.c == '_' -> throw Stop("a subscript is not a number")
                k.c == '=' -> if (last) "" else throw Stop("an equation is not a number")
                k.c.isLetter() -> if (k.c.toString() in rules.constants) k.c.toString() else throw Stop("the variable ${k.c} has no value")
                else -> throw Stop("'${k.c}' is not supported")
            }
            is Tok.Cmd -> when {
                k.name in rules.ignored -> ""
                k.name == "\\frac" || k.name == "\\dfrac" || k.name == "\\tfrac" -> { val a = arg(); val b = arg(); "(($a)/($b))" }
                k.name == "\\sqrt" -> {
                    if (pos < t.size && (t[pos] as? Tok.Ch)?.c == '[') {
                        pos++
                        val n = StringBuilder()
                        while (pos < t.size && (t[pos] as? Tok.Ch)?.c != ']') { emit(n, one(t[pos], false)); pos++ }
                        if (pos >= t.size) throw Stop("unbalanced brackets")
                        pos++
                        "root(" + arg() + ", $n)"
                    } else "sqrt(" + arg() + ")"
                }
                k.name in rules.commands -> rules.commands.getValue(k.name)
                else -> throw Stop("${k.name} is not supported")
            }
        }
    }

    /** The history entry an editor action leaves: kind [Config.historyKind], the source as the expression, the result (or ""). */
    fun entry(modeId: String, tex: String, result: String, cfg: Config): Logic.Entry = Logic.Entry(modeId, tex, result, kind = cfg.historyKind)

    fun isLatexEntry(e: Logic.Entry, cfg: Config): Boolean = e.kind == cfg.historyKind

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
