package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.notificationcenter.BatteryEstimator.ChargeSource
import com.diegonmarcos.superapp.notificationcenter.BatteryEstimator.DischargeState
import java.util.Locale

/**
 * The "Battery" badge as a pure function of the battery state: [Snapshot] in,
 * [Card] out. No Android type is touched, so the headline, the learning state
 * and every detail line are unit-testable without a device; the service
 * (BatteryBadgeService.kt) only gathers a Snapshot and draws the Card.
 *
 * The HEADLINE is the point: while charging, the time until full; while
 * discharging, the time until empty from the average drain since the last
 * unplug/full charge. Estimates are shown as h:mm and always labelled "est".
 */
object BatteryBadgeModel {

    // android.os.BatteryManager constants, mirrored so this file stays pure.
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5
    private const val PLUG_AC = 1
    private const val PLUG_USB = 2
    private const val PLUG_WIRELESS = 4
    private const val PLUG_DOCK = 8

    data class Snapshot(
        val nowMs: Long,
        /** 0..100, -1 unknown. */
        val levelPct: Int,
        val status: Int,
        val plugged: Int = 0,
        val health: Int = 1,
        val tempC: Double? = null,
        val voltageMv: Int? = null,
        /** Instantaneous current, positive = into the battery (see BatteryEstimator.intoBatteryMa). */
        val currentNowMa: Int? = null,
        val currentAvgMa: Int? = null,
        /** The OEM gauge's cycle count (API 34+); null where the system has none. */
        val cycleCount: Int? = null,
        /** libs:battery's own count derived from charge-counter deltas; null/negative when uncalibrated. */
        val cycleEstimate: Double? = null,
        val chargeCounterUah: Long? = null,
        val capacityMah: Int? = null,
        val capacityIsRated: Boolean = false,
        /** The unplug / plug anchor of the run in progress; 0 / -1 without one. */
        val anchorMs: Long = 0,
        val anchorPct: Int = -1,
        /** BatteryManager.computeChargeTimeRemaining(), -1 when the system cannot say. */
        val systemChargeRemainingMs: Long = -1,
        /** The EMA rate (%/min, toward empty or toward full), 0 when none. */
        val recentPctPerMin: Double = 0.0,
    )

    data class Card(val title: String, val text: String, val expanded: String)

    val Snapshot.charging: Boolean get() = status == STATUS_CHARGING
    val Snapshot.full: Boolean get() = status == STATUS_FULL || (levelPct >= 100 && plugged != 0)
    val Snapshot.pluggedIn: Boolean get() = plugged != 0

    fun card(s: Snapshot): Card = Card(title = title(s), text = collapsed(s), expanded = expanded(s))

    // ── the headline ─────────────────────────────────────────────────────

    fun title(s: Snapshot): String {
        val pct = if (s.levelPct in 0..100) "${s.levelPct}%" else "--"
        return "Battery $pct · ${headline(s)}"
    }

    fun headline(s: Snapshot): String = when {
        s.levelPct !in 0..100 -> "level unknown"
        s.full -> "Fully charged"
        s.charging -> {
            val c = estimateCharge(s)
            if (c.remainingMs != null) "~${fmtHm(c.remainingMs)} to full (est)" else "estimating time to full…"
        }
        s.status == STATUS_NOT_CHARGING && s.pluggedIn -> "Plugged in, not charging"
        else -> {
            val d = estimateDischarge(s)
            when {
                d.state == DischargeState.EMPTY -> "Empty"
                d.avgRemainingMs != null -> "~${fmtHm(d.avgRemainingMs)} left (est)"
                else -> "learning…"
            }
        }
    }

    fun estimateCharge(s: Snapshot) = BatteryEstimator.charge(
        levelPct = s.levelPct, full = s.full, platformRemainingMs = s.systemChargeRemainingMs,
        recentPctPerMin = s.recentPctPerMin, currentMa = s.currentNowMa, capacityMah = s.capacityMah)

    fun estimateDischarge(s: Snapshot) = BatteryEstimator.discharge(
        levelPct = s.levelPct, nowMs = s.nowMs, anchorMs = s.anchorMs, anchorPct = s.anchorPct,
        recentPctPerMin = s.recentPctPerMin)

    // ── the collapsed line ───────────────────────────────────────────────

    fun collapsed(s: Snapshot): String = listOfNotNull(
        statusText(s) + (plugText(s)?.let { " ($it)" } ?: ""),
        s.currentNowMa?.let { fmtMa(it) },
        s.voltageMv?.takeIf { it > 0 }?.let { fmtV(it) },
        s.tempC?.let { fmtTemp(it) },
        healthText(s.health).takeIf { it != "Good" && it != "Unknown" },
    ).joinToString(" · ")

    // ── the expanded body ────────────────────────────────────────────────

    fun expanded(s: Snapshot): String {
        val l = mutableListOf<String>()
        l += "Level: " + if (s.levelPct in 0..100) "${s.levelPct}%" else "unknown"
        l += "Status: ${statusText(s)}" + (plugText(s)?.let { " · $it" } ?: "")
        l += currentLine(s)
        l += "Voltage: " + (s.voltageMv?.takeIf { it > 0 }?.let { fmtV(it) } ?: "--")
        l += "Temperature: " + (s.tempC?.let { fmtTemp(it) } ?: "--")
        l += "Health: ${healthText(s.health)}"
        l += cycleLine(s)
        l += capacityLine(s)
        l += estimateLines(s)
        l += sinceLines(s)
        l += "Estimates (est) are projections, not measurements."
        return l.joinToString("\n")
    }

    private fun currentLine(s: Snapshot): String {
        val now = s.currentNowMa?.let { fmtMa(it) } ?: "--"
        val avg = s.currentAvgMa?.let { fmtMa(it) } ?: "--"
        return "Current: now $now · avg $avg"
    }

    private fun cycleLine(s: Snapshot): String = when {
        s.cycleCount != null && s.cycleCount > 0 -> "Cycles: ${s.cycleCount}"
        s.cycleEstimate != null && s.cycleEstimate >= 0.0 ->
            String.format(Locale.US, "Cycles: ~%.1f (est, counted since install)", s.cycleEstimate)
        else -> "Cycles: not available"
    }

    private fun capacityLine(s: Snapshot): String {
        val counter = s.chargeCounterUah?.takeIf { it > 0 }?.let { "${it / 1000} mAh" }
        val cap = s.capacityMah?.takeIf { it > 0 }?.let { "$it mAh" + if (s.capacityIsRated) " (rated)" else "" }
        return when {
            counter != null && cap != null -> "Charge: $counter of $cap"
            counter != null -> "Charge: $counter"
            cap != null -> "Capacity: $cap"
            else -> "Charge: not available"
        }
    }

    private fun estimateLines(s: Snapshot): List<String> {
        if (s.levelPct !in 0..100) return emptyList()
        if (s.full) return listOf("To full: 0:00")
        if (s.charging) {
            val c = estimateCharge(s)
            val src = when (c.source) {
                ChargeSource.SYSTEM -> "system estimate"
                ChargeSource.RECENT_RATE -> "recent charge rate"
                ChargeSource.CURRENT -> "from the current"
                else -> null
            }
            return listOf(
                "To full: " + (c.remainingMs?.let { "~${fmtHm(it)} (est, $src)" } ?: "learning…"),
                "Charge rate: " + (c.pctPerHour?.let { fmtRate(it) } ?: "--"),
            )
        }
        if (s.status == STATUS_NOT_CHARGING && s.pluggedIn) return emptyList()
        val d = estimateDischarge(s)
        val avg = when (d.state) {
            DischargeState.READY -> "~${fmtHm(d.avgRemainingMs!!)} at ${fmtRate(d.avgPctPerHour!!)} (est, avg since charge)"
            DischargeState.LEARNING -> "learning… (needs ${BatteryEstimator.MIN_USED_PCT}% used or ${BatteryEstimator.MIN_ELAPSED_MS / 60_000} min)"
            DischargeState.NO_ANCHOR -> "learning… (no unplug seen yet)"
            DischargeState.EMPTY -> "0:00"
        }
        val out = mutableListOf("To empty: $avg")
        if (d.recentRemainingMs != null && d.recentPctPerHour != null) {
            out += "Right now: ~${fmtHm(d.recentRemainingMs)} at ${fmtRate(d.recentPctPerHour)} (est, recent rate)"
        }
        return out
    }

    private fun sinceLines(s: Snapshot): List<String> {
        if (s.anchorMs <= 0L || s.anchorPct !in 0..100 || s.levelPct !in 0..100) return emptyList()
        val elapsed = (s.nowMs - s.anchorMs).coerceAtLeast(0L)
        return if (s.charging || s.full) {
            val gained = (s.levelPct - s.anchorPct).coerceAtLeast(0)
            listOf("Plugged in: ${fmtHm(elapsed)} ago · +$gained% since then")
        } else {
            val used = (s.anchorPct - s.levelPct).coerceAtLeast(0)
            listOf("Since last charge: ${fmtHm(elapsed)} · $used% used (from ${s.anchorPct}%)")
        }
    }

    // ── words ────────────────────────────────────────────────────────────

    fun statusText(s: Snapshot): String = when (s.status) {
        STATUS_CHARGING -> "Charging"
        STATUS_DISCHARGING -> "Discharging"
        STATUS_NOT_CHARGING -> "Not charging"
        STATUS_FULL -> "Full"
        else -> "Unknown"
    }

    fun plugText(s: Snapshot): String? = when {
        s.plugged and PLUG_WIRELESS != 0 -> "wireless"
        s.plugged and PLUG_AC != 0 -> "AC"
        s.plugged and PLUG_USB != 0 -> "USB"
        s.plugged and PLUG_DOCK != 0 -> "dock"
        s.plugged != 0 -> "plugged"
        else -> null
    }

    fun healthText(h: Int): String = when (h) {
        2 -> "Good"
        3 -> "Overheat"
        4 -> "Dead"
        5 -> "Over voltage"
        6 -> "Failure"
        7 -> "Cold"
        else -> "Unknown"
    }

    /** Milliseconds as h:mm, rounded to the nearest minute; "--" for nonsense. */
    fun fmtHm(ms: Long): String {
        if (ms < 0) return "--"
        val min = (ms + 30_000L) / 60_000L
        if (min >= 100 * 60) return "99:59+"
        return String.format(Locale.US, "%d:%02d", min / 60, min % 60)
    }

    fun fmtMa(ma: Int): String = (if (ma > 0) "+" else "") + "$ma mA"
    fun fmtV(mv: Int): String = String.format(Locale.US, "%.2f V", mv / 1000.0)
    fun fmtTemp(c: Double): String = String.format(Locale.US, "%.1f °C", c)
    fun fmtRate(pctPerHour: Double): String = String.format(Locale.US, "%.1f %%/h", pctPerHour)
}
