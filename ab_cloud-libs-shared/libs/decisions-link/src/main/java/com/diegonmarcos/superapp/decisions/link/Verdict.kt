package com.diegonmarcos.superapp.decisions.link

import org.json.JSONObject

/** One question's answer as the engine read it (the host gate's `jev-gate decide` prints the same shape). */
class Answer(
    /** noul | choice | score. */
    val type: String,
    /** P(yes) of a noul, P(pick) of a choice, P(level) of a score. */
    val p: Double,
    /** The use's threshold applied: P(pick or level), or max(P, 1-P) for a noul. */
    val confident: Boolean,
    /** A noul's yes/no; null for the others. */
    val value: Boolean?,
    /** A choice's option key; null for the others. */
    val pick: String?,
    /** A score's best level; null for the others. */
    val level: String?,
    val probabilities: Map<String, Double>,
)

/**
 * What an app may do with an answer, by the class its use was declared with. A Jev answer only reorders,
 * pre-selects, re-ranks or suggests: the app's own path is the fallback and runs whenever there is no
 * Verdict, or the answer is not [Answer.confident].
 *
 *  - user_facing: [mayPreselect]: the answer may pre-select or re-rank what the user sees;
 *  - background: the engine cached it and will not re-ask for the use's ttl;
 *  - gating: [adviceOnly]: a suggestion, or a confirm in place of a refusal, never an automatic action.
 */
class Verdict(
    val use: String,
    val cls: String,
    val cached: Boolean,
    val threshold: Double,
    val adviceOnly: Boolean,
    val answers: Map<String, Answer>,
) {
    val mayPreselect: Boolean get() = cls == USER_FACING && !adviceOnly

    /** The confident noul answer of [question], else null: unsure is no answer. */
    fun yes(question: String): Boolean? = answers[question]?.takeIf { it.type == "noul" && it.confident }?.value

    /** The confident pick of [question] when it is one of [allowed] (and only then), else null. */
    fun pick(question: String, allowed: Set<String>): String? =
        answers[question]?.takeIf { it.type == "choice" && it.confident }?.pick?.takeIf { it in allowed }

    /** The options of a choice or score [question], most probable first. */
    fun ranked(question: String): List<Pair<String, Double>> =
        answers[question]?.probabilities?.entries?.sortedByDescending { it.value }?.map { it.key to it.value }.orEmpty()

    companion object {
        const val USER_FACING = "user_facing"
        const val BACKGROUND = "background"
        const val GATING = "gating"
    }
}

/** An answered call, or "no opinion" with the engine's reason. */
sealed class Outcome {
    class Answered(val verdict: Verdict) : Outcome()
    class NoOpinion(val reason: String) : Outcome()

    val verdictOrNull: Verdict? get() = (this as? Answered)?.verdict

    companion object {
        const val NO_ENGINE = "no_engine"
        const val MALFORMED = "malformed"

        /** Read the engine's reply without trusting its shape: it crossed a process. */
        fun parse(reply: JSONObject): Outcome {
            if (!reply.optBoolean("ok", false)) return NoOpinion(reply.optString("reason").ifEmpty { MALFORMED })
            val results = reply.optJSONObject("results") ?: return NoOpinion(MALFORMED)
            val answers = LinkedHashMap<String, Answer>()
            for (id in results.keys()) {
                answers[id] = answer(results.optJSONObject(id) ?: return NoOpinion(MALFORMED)) ?: return NoOpinion(MALFORMED)
            }
            if (answers.isEmpty()) return NoOpinion(MALFORMED)
            val use = reply.optString("use")
            val cls = reply.optString("class")
            if (use.isEmpty() || cls.isEmpty()) return NoOpinion(MALFORMED)
            return Answered(Verdict(use, cls, reply.optBoolean("cached", false), reply.optDouble("threshold", 1.0),
                reply.optBoolean("advice_only", false), answers))
        }

        private fun answer(o: JSONObject): Answer? {
            val type = o.optString("type")
            val p = o.opt("p") as? Number ?: return null
            if (p.toDouble() !in 0.0..1.0) return null
            val probs = LinkedHashMap<String, Double>()
            o.optJSONObject("probabilities")?.let { po ->
                for (k in po.keys()) probs[k] = (po.opt(k) as? Number)?.toDouble() ?: return null
            }
            return when (type) {
                "noul" -> Answer(type, p.toDouble(), o.optBoolean("confident"), o.optBoolean("value"), null, null, probs)
                "choice" -> Answer(type, p.toDouble(), o.optBoolean("confident"), null, o.optString("pick").ifEmpty { return null }, null, probs)
                "score" -> Answer(type, p.toDouble(), o.optBoolean("confident"), null, null, o.optString("level").ifEmpty { return null }, probs)
                else -> null
            }
        }
    }
}
