package com.diegonmarcos.superapp.battery

import java.util.Locale
import kotlin.math.abs

/**
 * The words and units of the battery SoT: a [BatteryReport] in, labelled rows
 * out, pure. The popup, the badge and Configs › About › Battery all print
 * these, so a number reads the same wherever it appears, and every RATE is
 * shown in both units, %/h and W, together ([rate]).
 */
object BatteryRows {

    data class Row(val label: String, val value: String)
    data class Section(val id: String, val title: String, val rows: List<Row>)

    const val NOW = "now"
    const val SINCE = "since"
    const val DASH = "—"

    /** The popup: Now, then Since last charge (Since plugged in while on power). */
    fun popup(r: BatteryReport): List<Section> = listOf(now(r), since(r))

    // ── Now ──────────────────────────────────────────────────────────────

    fun now(r: BatteryReport): Section {
        val x = r.reading
        val rows = mutableListOf(
            Row("Level", if (x.levelPct in 0..100) "${x.levelPct}%" else DASH),
            Row("State", state(x)),
            Row("Current", r.smoothedMa?.let { fmtMa(it) + if (r.currentNowMa != null) " (smoothed)" else "" } ?: DASH),
            Row("Power", r.powerW?.let(::fmtW) ?: DASH),
            Row("Rate", rate(r.ratePctH, r.rateW) + when (r.rateSource) {
                RateSource.LEVEL -> " (level slope)"
                else -> ""
            }),
        )
        rows += if (x.onPower) Row("To full", toFull(r)) else Row("To empty", toEmpty(r.toEmptyMs, "current rate"))
        rows += Row("Voltage", x.voltageMv?.takeIf { it > 0 }?.let(::fmtV) ?: DASH)
        rows += Row("Temperature", x.tempC?.let(::fmtTemp) ?: DASH)
        rows += Row("Health", healthText(x.health))
        return Section(NOW, "Now", rows)
    }

    fun toFull(r: BatteryReport): String = when (r.toFullSource) {
        FullSource.FULL -> "Full"
        FullSource.SYSTEM -> "~${fmtHm(r.toFullMs!!)} (est, system)"
        FullSource.RATE -> "~${fmtHm(r.toFullMs!!)} (est, current rate)"
        FullSource.NONE -> if (r.reading.charging) "estimating…" else "not charging"
    }

    fun toEmpty(ms: Long?, basis: String): String = ms?.let { "~${fmtHm(it)} (est, $basis)" } ?: "learning…"

    // ── Since last charge / since plugged in ─────────────────────────────

    fun since(r: BatteryReport): Section {
        val x = r.reading
        val c = r.chargeNow
        if (x.onPower) {
            if (c == null) return Section(SINCE, "Since plugged in", listOf(Row("Plugged", "just now")))
            val elapsed = (x.nowMs - c.startTs).coerceAtLeast(0L)
            return Section(SINCE, "Since plugged in", listOf(
                Row("Plugged", "${fmtHm(elapsed)} ago · ${plugText(c.plugged) ?: "power"} (at ${c.startPct}%)"),
                Row("Gained", "+${(x.levelPct - c.startPct).coerceAtLeast(0)}%"),
                Row("Average", rate(r.chargeAvgPctH, r.chargeAvgW)),
                Row("To full at avg", r.toFullAtAvgMs?.let { if (it == 0L) "Full" else "~${fmtHm(it)} (est)" } ?: "learning…"),
            ))
        }
        val s = r.since ?: return Section(SINCE, "Since last charge", listOf(Row("Unplugged", "no unplug recorded yet")))
        val topUp = if (s.topUps > 0) " · topped up +${s.topUpPct}% ×${s.topUps}" else ""
        val rows = mutableListOf(
            Row("Unplugged", "${fmtHm(s.wallMs)} ago (at ${s.fromPct}%)" + if (s.approximate) " · history start" else ""),
            Row("Used", "${s.usedPct}% in ${fmtHm(s.onBatteryMs)} on battery$topUp"),
            Row("Average", if (s.avgPctPerHour == null) "learning… (needs ${BatteryHistory.MIN_USED_PCT}% used or " +
                "${BatteryHistory.MIN_ELAPSED_MS / 60_000} min)" else rate(s.avgPctPerHour, r.sinceAvgW)),
            Row("To empty at avg", toEmpty(r.toEmptyAtAvgMs, "avg since charge")),
        )
        rows += screenRows(s)
        return Section(SINCE, "Since last charge", rows)
    }

    /** Screen on vs off since the charge, with each one's own drain; "—" until the recorder saw the screen. */
    fun screenRows(s: SinceCharge?): List<Row> {
        if (s == null || !s.screenMeasured) return listOf(Row("Screen on / off", DASH))
        fun one(ms: Long, pct: Int): String {
            val h = ms / 3_600_000.0
            val r = if (h > 0.05) String.format(Locale.US, " · %.1f %%/h", pct / h) else ""
            return "${fmtHm(ms)} · $pct%$r"
        }
        return listOf(Row("Screen on", one(s.screenOnMs, s.screenOnPct)), Row("Screen off", one(s.screenOffMs, s.screenOffPct)))
    }

    // ── the stats page's extra sections ──────────────────────────────────

    fun capacity(r: BatteryReport, designMah: Int?, sysfsFullMah: Int?, sysfsCycles: Int?): Section {
        val est = if (r.capacityFromCounter) r.capacityMah else null
        val design = designMah ?: r.ratedMah
        val health = if (est != null && design != null && design > 0) " (${est * 100 / design}% of design)" else ""
        return Section("capacity", "Capacity", listOf(
            Row("Estimated full", est?.let { "$it mAh (charge counter ÷ level)$health" } ?: DASH),
            Row("Design", design?.let { "$it mAh" } ?: DASH),
            Row("Full (gauge, sysfs)", sysfsFullMah?.let { "$it mAh" } ?: DASH),
            Row("Charge now", r.reading.counterUah?.takeIf { it > 0 }?.let { "${it / 1000} mAh" } ?: DASH),
            Row("Cycles (system)", (r.systemCycles ?: sysfsCycles)?.toString() ?: DASH),
            Row("Cycles (counted)", r.cycleEstimate?.let { String.format(Locale.US, "~%.1f since install", it) } ?: "$DASH (charge to 100% once)"),
            Row("Full at last 100%", r.peakFullUah.takeIf { it > 0 }?.let { "${it / 1000} mAh" } ?: DASH),
            Row("Charged since install", r.cumulativeChargedUah.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Ah", it / 1_000_000.0) } ?: DASH),
            Row("Technology", r.reading.technology?.takeIf { it.isNotBlank() } ?: DASH),
            Row("Health", healthText(r.reading.health)),
        ))
    }

    fun spreads(temp: Spread?, volt: Spread?, window: String): Section = Section("spread", "Temperature & voltage ($window)", listOf(
        Row("Temperature", temp?.let { String.format(Locale.US, "min %.1f · avg %.1f · max %.1f °C", it.min, it.avg, it.max) } ?: DASH),
        Row("Voltage", volt?.let { String.format(Locale.US, "min %.2f · avg %.2f · max %.2f V", it.min, it.avg, it.max) } ?: DASH),
    ))

    /** One discharge-cycle row: start→end %, duration, avg %/h and W, max temperature. */
    fun cycleRow(s: BatterySession): List<String> = listOf(
        "${s.startPct}→${s.endPct}%" + if (s.ongoing) " …" else "",
        fmtHm(s.durationMs),
        rate(s.pctPerHour, s.avgW),
        s.maxTempC?.let { String.format(Locale.US, "%.1f°", it) } ?: DASH,
    )

    /** One charge-session row: from–to %, source, time, avg W in. */
    fun chargeRow(s: BatterySession): List<String> = listOf(
        "${s.startPct}→${s.endPct}%" + if (s.ongoing) " …" else "",
        plugText(s.plugged) ?: DASH,
        fmtHm(s.durationMs),
        s.avgW?.let(::fmtW) ?: DASH,
    )

    // ── power in / out, the charger ──────────────────────────────────────

    fun powerFlow(f: PowerFlow): Section = Section("flow", "Power in / out", listOf(
        Row("Net (battery)", f.netW?.let { fmtW(it) + if (it >= 0) " → battery" else " ← battery" } ?: DASH),
        Row("Phone consumption", f.consumptionW?.let {
            String.format(Locale.US, "%.2f W", it) + when (f.consumptionSource) {
                "modeled" -> " (est)"; "charger" -> " (charger − battery)"; else -> ""
            }
        } ?: if (f.consumptionSource == "unknown" && f.onPower) "$DASH (collecting)" else DASH),
        Row("Actual in", when {
            !f.onPower -> "0 W (on battery)"
            f.inW == null -> DASH
            f.inEstimated -> String.format(Locale.US, "≈ %.2f W (est)", f.inW)
            else -> String.format(Locale.US, "%.2f W (charger, live)", f.inW)
        }),
    ))

    /** The charger row: live input when sysfs gives it, else the negotiated max; "—" off power or unreadable. */
    fun charger(r: BatteryReport): String {
        val src = r.chargerSource ?: plugText(r.reading.plugged) ?: DASH
        return when {
            r.chargerLiveW != null -> String.format(Locale.US, "%.1f W (%s) live", r.chargerLiveW, src)
            r.chargerMaxW != null -> String.format(Locale.US, "%.1f W (%s) max", r.chargerMaxW, src)
            else -> DASH
        }
    }

    // ── words ────────────────────────────────────────────────────────────

    fun state(x: BatteryReading): String {
        val st = statusText(x.status)
        val src = plugText(x.plugged)
        return if (src != null) "$st · $src" else if (x.discharging) "$st (on battery)" else st
    }

    fun statusText(status: Int): String = when (status) {
        BatteryMath.STATUS_CHARGING -> "Charging"
        BatteryMath.STATUS_DISCHARGING -> "Discharging"
        BatteryMath.STATUS_NOT_CHARGING -> "Not charging"
        BatteryMath.STATUS_FULL -> "Full"
        else -> "Unknown"
    }

    fun plugText(plugged: Int): String? = when {
        plugged and BatteryMath.PLUG_WIRELESS != 0 -> "wireless"
        plugged and BatteryMath.PLUG_AC != 0 -> "AC"
        plugged and BatteryMath.PLUG_USB != 0 -> "USB"
        plugged and BatteryMath.PLUG_DOCK != 0 -> "dock"
        plugged != 0 -> "plugged"
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

    // ── units ────────────────────────────────────────────────────────────

    /** A rate, ALWAYS in both units: "−7.8 %/h · −1.26 W"; a missing unit is "—", never dropped. */
    fun rate(pctH: Double?, w: Double?): String {
        if (pctH == null && w == null) return DASH
        val p = pctH?.let { signed(it, "%.1f") + " %/h" } ?: "$DASH %/h"
        val ww = w?.let(::fmtW) ?: "$DASH W"
        return "$p · $ww"
    }

    fun fmtW(w: Double): String = signed(w, "%.2f") + " W"
    fun fmtMa(ma: Double): String = signed(ma, "%.0f") + " mA"
    fun fmtV(mv: Int): String = String.format(Locale.US, "%.2f V", mv / 1000.0)
    fun fmtTemp(c: Double): String = String.format(Locale.US, "%.1f °C", c)

    /** "+1.2" / "−1.2" / "0.0": the SoT's sign shown with a real minus. */
    private fun signed(v: Double, fmt: String): String {
        val body = String.format(Locale.US, fmt, abs(v))
        val zero = body.all { it == '0' || it == '.' }
        return when {
            zero -> body
            v > 0 -> "+$body"
            else -> "−$body"
        }
    }

    /** Milliseconds as h:mm, rounded to the minute; "—" for nonsense. */
    fun fmtHm(ms: Long): String {
        if (ms < 0) return DASH
        val min = (ms + 30_000L) / 60_000L
        if (min >= 100 * 60) return "99:59+"
        return String.format(Locale.US, "%d:%02d", min / 60, min % 60)
    }
}
