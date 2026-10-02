package com.diegonmarcos.cloudcalc.jev

import com.diegonmarcos.superapp.decisions.Decision
import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.decisions.Option
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jev mode's router. A decision model cannot compute: it only scores options. So the router asks
 * it ONE choice question over the declared tools (+ "none"), and on a confident pick turns the
 * request into a [Plan] the app's own engines execute — libqalculate for the maths, the Clock
 * engine for a timer. Numbers are read from the request text here; when a form has several
 * fields, a second choice question per field asks which number is which. Every failure (no
 * token, network, HTTP, a malformed answer) is an [Outcome] with a fallback plan, never a throw,
 * so the calculator keeps working offline.
 */
object JevRouter {
    sealed interface Plan {
        /** Evaluate [text] with mode [mode]'s engine options. */
        data class Expression(val mode: String, val text: String) : Plan
        /** Fill form [form] of mode [mode] with [values] (field id → text) and evaluate its outputs. */
        data class Form(val mode: String, val form: String, val values: Map<String, String>, val slots: Decision?) : Plan
        data class Timer(val seconds: Long, val label: String) : Plan
        data class Open(val mode: String) : Plan
    }

    enum class Kind { ROUTED, CANDIDATES, NONE, FAILED }

    data class Outcome(
        val request: String,
        val kind: Kind,
        val decision: Decision,
        /** The confident pick (ROUTED); null otherwise. */
        val tool: JevConfig.Tool?,
        /** Every routing option with its probability, most probable first. */
        val options: List<Option>,
        /** CANDIDATES: the tools the user may pick from, most probable first. */
        val candidates: List<JevConfig.Tool>,
        /** The declared extra questions' answers, by question id. */
        val extras: Map<String, List<Option>>,
        /** FAILED: what the app runs instead (route.on_error), or null for "say so". */
        val fallback: Plan?,
        /** FAILED: why, in words; "" otherwise. */
        val reason: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("request", request).put("kind", kind.name.lowercase())
            .put("tool", tool?.id ?: JSONObject.NULL)
            .put("options", Decisions.optionsJson(options))
            .put("candidates", JSONArray(candidates.map { it.id }))
            .put("extras", JSONObject().apply { extras.forEach { (k, v) -> put(k, Decisions.optionsJson(v)) } })
            .put("reason", reason)
            .put("decision", decision.toJson())
    }

    const val ROUTE = "route"

    /** The routing question over the declared tools plus "none", and the declared extras. */
    fun questions(cfg: JevConfig): Map<String, JevConfig.Question> {
        val r = cfg.route
        val criteria = r.tools.associate { it.id to it.criterion } + (JevConfig.NONE to r.noneCriterion)
        return mapOf(ROUTE to JevConfig.Question(ROUTE, ROUTE, JevConfig.CHOICE, r.instructions, criteria)) +
            r.extra.associateBy { it.id }
    }

    /** The state a request is sent as: `{"request": …}`, or content parts when an image rides along. */
    fun state(request: String, imageDataUrl: String? = null): Any =
        if (imageDataUrl == null) JSONObject().put("request", request)
        else JSONArray()
            .put(JSONObject().put("type", "text").put("text", request))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))

    fun route(cfg: JevConfig, http: Http, token: String?, model: String, request: String): Outcome =
        interpret(cfg, request, Decisions.call(cfg, http, token, model, state(request), questions(cfg)))

    /** Turn a routing [Decision] into what the app does next. */
    fun interpret(cfg: JevConfig, request: String, d: Decision): Outcome {
        val r = cfg.route
        val ids = r.tools.map { it.id }.toSet() + JevConfig.NONE
        val pick = Decisions.pick(d.answers?.optJSONObject(ROUTE), ids)
        val options = d.options(ROUTE)
        val extras = r.extra.associate { it.id to d.options(it.id) }
        fun outcome(kind: Kind, tool: JevConfig.Tool? = null, cands: List<JevConfig.Tool> = emptyList(), fb: Plan? = null, why: String = "") =
            Outcome(request, kind, d, tool, options, cands, extras, fb, why)
        if (pick == null) {
            val fb = if (r.onError == "expression") Plan.Expression(r.fallbackMode, strip(request, r.strip)) else null
            return outcome(Kind.FAILED, fb = fb, why = d.error.ifBlank { "the answer has no usable pick" })
        }
        if (pick.p >= r.threshold) {
            return if (pick.key == JevConfig.NONE) outcome(Kind.NONE) else outcome(Kind.ROUTED, tool = r.tools.first { it.id == pick.key })
        }
        val cands = options.filter { it.key != JevConfig.NONE && it.p >= r.candidateMinP }
            .take(r.candidates).mapNotNull { o -> r.tools.firstOrNull { it.id == o.key } }
        return outcome(Kind.CANDIDATES, cands = cands)
    }

    // ── reading the request ─────────────────────────────────────────────────────────────────

    private val NUMBER = Regex("""(?<![\w.])-?\d+(?:\.\d+)?""")

    /** The numbers a request names, in order ("3", "-2.5"); a comma separates, it is never a decimal. */
    fun numbers(text: String): List<String> = NUMBER.findAll(text).map { it.value }.toList()

    /** The request with the declared lead-in phrases ("convert", "what's") and a trailing "?" removed. */
    fun strip(text: String, patterns: List<String>): String =
        patterns.fold(text.trim()) { t, p -> t.replace(Regex(p, RegexOption.IGNORE_CASE), "").trim() }.trimEnd('?', ' ')

    /** "1 h 30 min", "10 min", "90s" → seconds, by the declared units; null when none is named. */
    fun duration(text: String, units: Map<String, Int>): Long? {
        val byLength = units.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        if (byLength.isEmpty()) return null
        val rx = Regex("""(\d+(?:\.\d+)?)\s*($byLength)\b""", RegexOption.IGNORE_CASE)
        val hits = rx.findAll(text).toList()
        if (hits.isEmpty()) return null
        return hits.sumOf { m -> m.groupValues[1].toDouble() * units.getValue(units.keys.first { it.equals(m.groupValues[2], true) }) }.toLong()
    }

    /** Field ids paired with the request's numbers in declared order; a field with none keeps its default. */
    fun positional(fields: List<Pair<String, String>>, numbers: List<String>): Map<String, String> =
        fields.mapIndexed { i, (id, def) -> id to (numbers.getOrNull(i) ?: def) }.toMap()

    /**
     * Which number is which field: one choice question per field over the numbers found (+ none).
     * [fields] is (id, label, default). A field whose pick is "none", unsure (below
     * route.slot_threshold) or unusable keeps its default; a failed call falls back to [positional].
     */
    fun slots(cfg: JevConfig, http: Http, token: String?, model: String, request: String, fields: List<Triple<String, String, String>>): Pair<Map<String, String>, Decision?> {
        val nums = numbers(request)
        val defaults = fields.map { it.first to it.third }
        if (fields.size < 2 || nums.isEmpty()) return positional(defaults, nums) to null
        val criteria = nums.mapIndexed { i, n -> "n${i + 1}" to "the number $n (number ${i + 1} in the request)" }.toMap() +
            (JevConfig.NONE to "The request gives no value for it.")
        val qs = fields.associate { (id, label, _) ->
            id to JevConfig.Question(id, label, JevConfig.CHOICE, cfg.route.slotInstructions.replace("{field}", label), criteria)
        }
        val d = Decisions.call(cfg, http, token, model, state(request), qs)
        if (!d.ok) return positional(defaults, nums) to d
        val values = fields.associate { (id, _, def) ->
            val pick = Decisions.pick(d.answers?.optJSONObject(id), criteria.keys)
            val n = pick?.takeIf { it.p >= cfg.route.slotThreshold && it.key != JevConfig.NONE }?.key?.removePrefix("n")?.toIntOrNull()
            id to (n?.let { nums[it - 1] } ?: def)
        }
        return values to d
    }

    /**
     * What a picked [tool] does with [request]. [fields] is the tool's form fields as (id, label,
     * default) — the app reads them from build.json::ui.modes; empty for every other action.
     */
    fun plan(cfg: JevConfig, http: Http, token: String?, model: String, tool: JevConfig.Tool, request: String, fields: List<Triple<String, String, String>>): Plan =
        when (tool.action) {
            "form" -> slots(cfg, http, token, model, request, fields).let { (v, d) -> Plan.Form(tool.mode, tool.form, v, d) }
            "timer" -> Plan.Timer(duration(request, cfg.route.durationUnits) ?: 0, "")
            "open" -> Plan.Open(tool.mode)
            else -> Plan.Expression(tool.mode, strip(request, cfg.route.strip))
        }

    // ── a question about a result (Addendum A) ──────────────────────────────────────────────

    /** The state a follow-up question scores: the mode, what was computed and what came out. */
    fun resultState(mode: String, calculation: String, result: String, context: String): JSONObject =
        JSONObject().put("mode", mode).put("calculation", calculation).put("result", result).apply {
            if (context.isNotBlank()) put("context", context)
        }

    /**
     * The user's own question: noul (yes/no) unless options are given — "a | b | c" is a choice,
     * and the same with type score is an ordered scale, lowest first.
     */
    fun freeQuestion(text: String, type: String, options: String): JevConfig.Question {
        val opts = options.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when {
            type == JevConfig.SCORE && opts.size >= 2 -> JevConfig.Question("free", text, JevConfig.SCORE, text, levels = opts)
            type == JevConfig.CHOICE && opts.size >= 2 -> JevConfig.Question("free", text, JevConfig.CHOICE, text, opts.associateWith { it })
            else -> JevConfig.Question("free", text, JevConfig.NOUL, text)
        }
    }

    fun ask(cfg: JevConfig, http: Http, token: String?, model: String, q: JevConfig.Question, state: JSONObject): Decision =
        Decisions.call(cfg, http, token, model, state, mapOf(q.id to q))

    // ── what is this? (#772) ─────────────────────────────────────────────────────────────────

    /** The choice over jev.identify.[kind]'s classes, plus its declared extra questions. */
    fun identifyQuestions(cfg: JevConfig, kind: String): Map<String, JevConfig.Question> {
        val id = cfg.identify[kind] ?: throw IllegalArgumentException("jev.identify.$kind is not declared")
        val c = JevConfig.IDENTIFY_CLASS
        return mapOf(c to JevConfig.Question(c, c, JevConfig.CHOICE, id.instructions, id.classes)) + id.extra.associateBy { it.id }
    }

    /**
     * What the model is told: `{"measured": …}`; with an image (a spectrogram, a photo) the same
     * text and the image as content parts, for a model whose catalogue entry takes images.
     */
    fun identifyState(measured: JSONObject, imageDataUrl: String?): Any =
        if (imageDataUrl == null) JSONObject().put("measured", measured)
        else JSONArray()
            .put(JSONObject().put("type", "text").put("text", "measured: $measured"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))

    fun identify(cfg: JevConfig, http: Http, token: String?, model: String, kind: String, measured: JSONObject, imageDataUrl: String? = null): Decision =
        Decisions.call(cfg, http, token, model, identifyState(measured, imageDataUrl), identifyQuestions(cfg, kind))
}
