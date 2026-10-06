package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Decisions
import org.json.JSONObject

/**
 * The caller's questions checked before anything is sent, and the Decisions answer read back into one
 * shape the callers share (the host gate's `jev-gate decide` prints the same one):
 *
 *   noul    {"type":"noul","p":0.93,"value":true,"confident":true}
 *   choice  {"type":"choice","pick":"a","p":0.8,"confident":true,"probabilities":{...}}
 *   score   {"type":"score","level":"high","p":0.7,"confident":false,"probabilities":{...}}
 *
 * `confident` is the use's threshold applied: P(pick) for a choice or score level, max(P, 1-P) for a noul.
 * An answer that is missing, mistyped, picks outside the caller's options or carries a probability
 * outside [0, 1] is not read at all: the whole call is `malformed` and the caller's own path runs.
 */
object Verdicts {
    const val MAX_QUESTIONS = 8
    private val TYPES = setOf(Decisions.NOUL, Decisions.CHOICE, Decisions.SCORE)

    /** Null when the questions are well formed and inside the use's `allowed` options, else the reason. */
    fun check(questions: JSONObject?, use: UseDecl): String? {
        if (questions == null || questions.length() !in 1..MAX_QUESTIONS) return "bad_questions"
        for (id in questions.keys()) {
            val q = questions.optJSONObject(id) ?: return "bad_questions"
            if (q.optString("type") !in TYPES || q.optString("instructions").isEmpty()) return "bad_questions"
            if (q.optString("type") == Decisions.CHOICE) {
                val crit = q.optJSONObject("criteria") ?: return "bad_questions"
                if (crit.length() < 2) return "bad_questions"
                if (use.allowed.isNotEmpty() && crit.keys().asSequence().any { it !in use.allowed }) return "option_not_allowed"
            }
        }
        return null
    }

    /** The per-question results, or null when any answer cannot be read. */
    fun read(answers: JSONObject, questions: JSONObject, threshold: Double): JSONObject? {
        val out = JSONObject()
        for (id in questions.keys()) {
            val a = answers.optJSONObject(id) ?: return null
            val type = questions.getJSONObject(id).getString("type")
            if (type == Decisions.NOUL) {
                val p = a.opt("noul")
                if (!Decisions.isP(p)) return null
                val v = (p as Number).toDouble()
                out.put(id, JSONObject().put("type", type).put("p", v).put("value", v >= 0.5)
                    .put("confident", maxOf(v, 1 - v) >= threshold))
                continue
            }
            val probs = a.optJSONObject("probabilities") ?: return null
            val keys = probs.keys().asSequence().toList()
            if (keys.isEmpty() || !keys.all { Decisions.isP(probs.opt(it)) }) return null
            if (type == Decisions.CHOICE) {
                val pick = a.opt("choice") as? String ?: return null
                if (!probs.has(pick) || !questions.getJSONObject(id).getJSONObject("criteria").has(pick)) return null
                val p = probs.getDouble(pick)
                out.put(id, JSONObject().put("type", type).put("pick", pick).put("p", p)
                    .put("confident", p >= threshold).put("probabilities", probs))
            } else {
                val level = keys.maxByOrNull { probs.getDouble(it) }!!
                val p = probs.getDouble(level)
                out.put(id, JSONObject().put("type", type).put("level", level).put("p", p)
                    .put("confident", p >= threshold).put("probabilities", probs))
            }
        }
        return out
    }
}
