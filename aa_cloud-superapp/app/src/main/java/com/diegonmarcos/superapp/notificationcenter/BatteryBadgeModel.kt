package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.battery.BatteryReport
import com.diegonmarcos.superapp.battery.BatteryRows
import com.diegonmarcos.superapp.battery.FullSource
import java.util.Locale

/**
 * The "Battery" badge as a pure function of the battery SoT's report:
 * [BatteryReport] in, [Card] out. It computes NOTHING — every number is the
 * report's (libs:battery BatteryTruth), and every row of the expanded body is
 * one of [BatteryRows.popup]'s, the very strings the home-screen battery popup
 * prints. The service (BatteryBadgeService.kt) only asks
 * BatteryRepository.report() and draws the Card.
 *
 * The HEADLINE is the point: while charging, the time until full; while
 * discharging, the time until empty at the average since the last charge (the
 * steadier number), else at the current rate. Estimates are h:mm, labelled "est".
 */
object BatteryBadgeModel {

    data class Card(val title: String, val text: String, val expanded: String)

    fun card(r: BatteryReport): Card = Card(title = title(r), text = collapsed(r), expanded = expanded(r))

    // ── the headline ─────────────────────────────────────────────────────

    fun title(r: BatteryReport): String {
        val pct = if (r.levelPct in 0..100) "${r.levelPct}%" else "--"
        return "Battery $pct · ${headline(r)}"
    }

    fun headline(r: BatteryReport): String {
        val x = r.reading
        return when {
            x.levelPct !in 0..100 -> "level unknown"
            x.full -> "Fully charged"
            x.onPower && x.charging -> when (r.toFullSource) {
                FullSource.SYSTEM, FullSource.RATE -> "~${BatteryRows.fmtHm(r.toFullMs!!)} to full (est)"
                else -> "estimating time to full…"
            }
            x.onPower -> "Plugged in, not charging"
            x.levelPct == 0 -> "Empty"
            else -> r.toEmptyAtAvgMs?.let { "~${BatteryRows.fmtHm(it)} left (est)" }
                ?: r.toEmptyMs?.let { "~${BatteryRows.fmtHm(it)} left (est, now)" }
                ?: "learning…"
        }
    }

    // ── the collapsed line ───────────────────────────────────────────────

    fun collapsed(r: BatteryReport): String {
        val x = r.reading
        return listOfNotNull(
            BatteryRows.state(x),
            BatteryRows.rate(r.ratePctH, r.rateW).takeIf { it != BatteryRows.DASH },
            x.voltageMv?.takeIf { it > 0 }?.let(BatteryRows::fmtV),
            x.tempC?.let(BatteryRows::fmtTemp),
            BatteryRows.healthText(x.health).takeIf { it != "Good" && it != "Unknown" },
        ).joinToString(" · ")
    }

    // ── the expanded body: the popup's rows, then capacity and cycles ────

    fun expanded(r: BatteryReport): String {
        val l = mutableListOf<String>()
        for (sec in BatteryRows.popup(r)) {
            l += "— ${sec.title} —"
            for (row in sec.rows) l += line(row)
        }
        l += capacityLine(r)
        l += cycleLine(r)
        l += "Estimates (est) are projections, not measurements."
        return l.joinToString("\n")
    }

    /** One row exactly as the popup prints it. */
    fun line(row: BatteryRows.Row): String = "${row.label}: ${row.value}"

    private fun capacityLine(r: BatteryReport): String {
        val counter = r.reading.counterUah?.takeIf { it > 0 }?.let { "${it / 1000} mAh" }
        val cap = r.capacityMah?.let { "$it mAh" + if (!r.capacityFromCounter) " (rated)" else "" }
        return when {
            counter != null && cap != null -> "Charge: $counter of $cap"
            counter != null -> "Charge: $counter"
            cap != null -> "Capacity: $cap"
            else -> "Charge: not available"
        }
    }

    private fun cycleLine(r: BatteryReport): String = when {
        r.systemCycles != null -> "Cycles: ${r.systemCycles}"
        r.cycleEstimate != null -> String.format(Locale.US, "Cycles: ~%.1f (est, counted since install)", r.cycleEstimate)
        else -> "Cycles: not available"
    }
}
