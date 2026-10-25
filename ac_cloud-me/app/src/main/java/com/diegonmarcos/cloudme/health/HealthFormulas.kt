package com.diegonmarcos.cloudme.health

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Pure evaluation of the declared formulas ([Formulas]) and plan rules ([Plan]). No Android, no state: HealthFormulasTest holds them. */
object HealthFormulas {

    data class Anthro(val lbm: Double, val ffmi: Double, val bmr: Int, val tdee: Int, val ffmiBand: String)

    /** Null when any input is missing or not physical (an empty entry is never turned into a number). */
    fun anthro(f: Formulas, massKg: Double?, heightCm: Double?, bfPct: Double?, activityId: String?): Anthro? {
        if (massKg == null || heightCm == null || bfPct == null) return null
        if (massKg <= 0 || heightCm <= 0 || bfPct < 0 || bfPct >= 100) return null
        val lbm = massKg * (1 - bfPct / 100.0)
        val m = heightCm / 100.0
        val ffmi = lbm / (m * m)
        val bmr = (f.bmrBase + f.bmrPerLbm * lbm).roundToInt()
        val mult = f.activity.firstOrNull { it.id == activityId } ?: f.activity.first { it.id == f.defaultActivity }
        val band = f.ffmiBands.firstOrNull { ffmi < it.max }?.label.orEmpty()
        return Anthro(lbm, ffmi, bmr, (bmr * mult.multiplier).roundToInt(), band)
    }

    data class Sauna(val naclMg: Int, val sodiumMg: Int, val saltTeaspoons: Double)

    fun sauna(f: Formulas, minutes: Int): Sauna {
        val nacl = minutes * f.naclMgPerMin
        return Sauna(nacl.roundToInt(), (nacl * f.naFractionOfNacl).roundToInt(), nacl / 1000.0 / f.saltGPerTeaspoon)
    }

    /** Fluid to drink (mL) for a pre/post sauna body-mass drop in kg: 1 kg lost = 1 L, replaced at the declared ratio. Null when nothing was lost. */
    fun fluidToReplaceMl(f: Formulas, preKg: Double?, postKg: Double?): Int? {
        if (preKg == null || postKg == null) return null
        val lost = preKg - postKg
        return if (lost <= 0) null else (lost * 1000 * f.fluidReplaceRatio).roundToInt()
    }

    /** Piecewise-linear value of [curve] (x ascending) at [x], clamped to its ends. */
    fun interpolate(curve: List<Pair<Double, Double>>, x: Double): Double {
        if (curve.isEmpty()) return 0.0
        if (x <= curve.first().first) return curve.first().second
        if (x >= curve.last().first) return curve.last().second
        for (i in 0 until curve.size - 1) {
            val (x0, y0) = curve[i]
            val (x1, y1) = curve[i + 1]
            if (x in x0..x1) return if (x1 == x0) y0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return curve.last().second
    }

    /** A 24 h series that wraps: the value at [minute] of the day, blending the last point back to the first of the next day. */
    fun circadianAt(points: List<Pair<Double, Double>>, minute: Double): Double {
        if (points.isEmpty()) return 0.0
        val day = 1440.0
        val m = ((minute % day) + day) % day
        val ext = points + listOf(points.first().first + day to points.first().second)
        val x = if (m < points.first().first) m + day else m
        return interpolate(ext, x)
    }

    fun gutFlowPercent(f: Formulas, exertionPctVo2: Double): Double = interpolate(f.hemoCurve, exertionPctVo2)

    data class Gap(val meal: PlanEvent, val trainingTitle: String, val minutes: Int, val ok: Boolean, val needed: Int)

    /** For every meal BEFORE each training event: the minutes to the session and whether it clears the declared gap (a fatty meal needs the longer one). */
    fun mealGaps(f: Formulas, plan: Plan): List<Gap> {
        val out = mutableListOf<Gap>()
        for (t in plan.timeline.filter { it.kind == "training" }) {
            for (m in plan.timeline.filter { it.kind == "meal" && it.minutes < t.minutes }) {
                val gap = t.minutes - m.minutes
                val needed = if (m.fat) f.hemoFatGapMin else f.hemoMinGapMin
                out += Gap(m, t.title, gap, gap >= needed, needed)
            }
        }
        return out
    }

    data class Verdict(val level: String, val title: String, val text: String)

    /** First declared rule whose tag conditions both hold (an empty condition always holds). */
    fun clash(decl: ClashDecl, proteinTags: Set<String>, cofactorTags: Set<String>): Verdict? =
        decl.rules.firstOrNull { r ->
            (r.proteinHasAny.isEmpty() || r.proteinHasAny.any { it in proteinTags }) &&
                (r.cofactorHasAny.isEmpty() || r.cofactorHasAny.any { it in cofactorTags })
        }?.let { Verdict(it.level, it.title, it.text) }

    /** The same rules applied to one plan window: its protein items against everything else eaten with them. */
    fun clashForWindow(decl: ClashDecl, e: PlanEvent): Verdict? {
        if (e.items.isEmpty()) return null
        val protein = e.items.filter { it.role == "protein" }.flatMap { it.tags }.toSet()
        val rest = e.items.filter { it.role != "protein" }.flatMap { it.tags }.toSet()
        return clash(decl, protein, rest)
    }

    data class Need(val item: PlanItem, val perWeek: Double, val packs: Int, val buy: Double)

    /** The weekly shopping list derived from the plan: each item's per-day qty x days per week, rounded UP to whole packs; same id across windows is summed. */
    fun shopping(plan: Plan): List<Need> {
        val total = linkedMapOf<String, Pair<PlanItem, Double>>()
        for (e in plan.timeline) for (i in e.items + e.supplements) {
            val prev = total[i.id]
            total[i.id] = i to ((prev?.second ?: 0.0) + i.qty * plan.daysPerWeek)
        }
        return total.values.map { (item, week) ->
            val packs = ceil(week / item.pack.size - 1e-9).toInt().coerceAtLeast(1)
            Need(item, week, packs, packs * item.pack.size)
        }
    }

    enum class Status { Below, InRange, Above }

    fun biomarkerStatus(b: Biomarker, value: Double): Status = when {
        b.min != null && value < b.min -> Status.Below
        b.max != null && value > b.max -> Status.Above
        else -> Status.InRange
    }

    fun rangeText(b: Biomarker): String = when {
        b.min != null && b.max != null -> "${num(b.min)} to ${num(b.max)} ${b.unit}"
        b.min != null -> "above ${num(b.min)} ${b.unit}"
        b.max != null -> "below ${num(b.max)} ${b.unit}"
        else -> b.unit
    }

    fun num(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)

    /** The next plan event at or after [minuteOfDay], wrapping to tomorrow's first. */
    fun nextEvent(plan: Plan, minuteOfDay: Int): PlanEvent? =
        plan.timeline.filter { it.minutes >= minuteOfDay }.minByOrNull { it.minutes } ?: plan.timeline.minByOrNull { it.minutes }
}
