package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject

/**
 * An evidence gate (#884, the host gate's `gate`): a use that can lose data, money or access stays
 * observe-only (scored and journaled, answered `gate_closed` = no opinion) until the journal shows the uses
 * in [after] were RIGHT: at least [minLabelled] of their confident answers have an outcome, and at least
 * [minPrecision] of those matched it. A use that lists itself in [after] waits on its own evidence.
 */
data class GateDecl(val after: List<String>, val minLabelled: Int, val minPrecision: Double)

/** Precision from the journal alone: answers carry `call` + `picks`, outcomes carry `call` + `label`. */
object Evidence {

    /** (labelled, correct) over [use]'s confident answers that have an outcome. The latest outcome of a call wins. */
    fun precision(lines: List<String>, use: String): Pair<Int, Int> {
        val picks = HashMap<String, JSONObject>()
        val labels = HashMap<String, Any>()
        for (raw in lines) {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            if (o.optString("use") != use) continue
            val call = o.optString("call").ifEmpty { null } ?: continue
            when (o.optString("outcome")) {
                "answered" -> o.optJSONObject("picks")?.takeIf { it.length() > 0 }?.let { picks[call] = it }
                "outcome" -> o.opt("label")?.let { labels[call] = it }
            }
        }
        var labelled = 0
        var correct = 0
        for ((call, label) in labels) {
            val mine = picks[call] ?: continue
            for (q in mine.keys()) {
                val want = if (label is JSONObject) label.opt(q) else if (mine.length() == 1) label else null
                if (want == null) continue
                labelled++
                if (want == mine.get(q)) correct++
            }
        }
        return labelled to correct
    }

    fun open(gate: GateDecl, lines: List<String>): Boolean = gate.after.all { use ->
        val (labelled, correct) = precision(lines, use)
        labelled >= gate.minLabelled && correct.toDouble() / labelled >= gate.minPrecision
    }
}
