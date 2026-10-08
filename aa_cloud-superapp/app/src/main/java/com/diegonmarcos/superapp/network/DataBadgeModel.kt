package com.diegonmarcos.superapp.network

import java.util.Locale

/**
 * The "Data" badge as a pure function of data-usage figures: [Snapshot] in,
 * [Card] out. The figures come from libs:datamanager's DataUsageProvider (the
 * Data Manager engine behind Configs > About > Data Usage); nothing here, and
 * nothing in the service, queries NetworkStatsManager itself. No Android types,
 * so labels, actions and the permission-missing state are unit-testable.
 */
object DataBadgeModel {

    /** Bytes of one window split by transport. */
    data class Window(val mobile: Long = 0, val wifi: Long = 0) {
        val total: Long get() = mobile + wifi
    }

    data class App(val label: String, val bytes: Long)

    /** One active SIM / eSIM profile and its mobile data this month. */
    data class Sim(
        val slot: Int,
        val carrier: String,
        val monthBytes: Long,
        /** False when the engine could not measure this SIM on its own (Android 10+ hides the
         *  subscriber id) and [monthBytes] is the whole mobile total shared by every SIM. */
        val exact: Boolean,
        /** Its mobile bytes over the last 30 days (0 = no history). */
        val last30Bytes: Long = 0,
    )

    data class Snapshot(
        /** PACKAGE_USAGE_STATS (Usage Access) granted. */
        val hasAccess: Boolean,
        /** READ_PHONE_STATE granted: without it there is no SIM list, so no per-SIM split. */
        val hasPhone: Boolean = true,
        /** Active subscriptions only: an inactive SIM of a dual-SIM phone is not listed. */
        val sims: List<Sim> = emptyList(),
        /** Calendar month: ms since its first midnight, and its full length; 0 = unknown. */
        val monthElapsedMs: Long = 0,
        val monthLengthMs: Long = 0,
        /** The month's last day as shown to the owner, e.g. "Oct 31". */
        val monthEndLabel: String = "",
        /** Device-wide bytes over the last 30 days, and that window's length (30 days unless the caller says). */
        val last30: Window = Window(),
        val last30WindowMs: Long = 30 * DAY_MS,
        val today: Window = Window(),
        /** The month so far (the engine's startOfMonth window). */
        val month: Window = Window(),
        /** Biggest apps of the month, already sorted; the model shows [TOP_APPS]. */
        val topApps: List<App> = emptyList(),
    )

    enum class Act { GRANT, GRANT_PHONE, REFRESH, OPEN }

    data class Action(val act: Act, val label: String)

    data class Card(val title: String, val text: String, val expanded: String, val actions: List<Action>)

    const val TOP_APPS = 5

    fun card(s: Snapshot) = Card(title(s), collapsed(s), expanded(s), actions(s))

    /** Headline: the month's estimated mobile usage, both ways, for the total (the SIMs together). */
    fun title(s: Snapshot) =
        if (!s.hasAccess) "Data \u00b7 Usage access needed" else estLine(s.month.mobile, s.last30.mobile, s)

    /** First line under it: the same two estimates per SIM when the split exists, else today's usage. */
    fun collapsed(s: Snapshot): String = when {
        !s.hasAccess -> "Grant Usage Access to see data usage"
        perSim(s) && s.sims.size > 1 ->
            s.sims.joinToString(" \u00b7 ") { "${simName(it)} ${estPair(it.monthBytes, it.last30Bytes, s)}" }
        else -> "Today ${split(s.today)}"
    }

    fun expanded(s: Snapshot): String {
        if (!s.hasAccess) return "Data usage is read from the system's network statistics, which needs the " +
            "Usage Access special permission. Tap Grant access, pick Cloud SuperApp and switch it on."
        val l = mutableListOf<String>()
        l += simLines(s)
        l += "Today: ${bytes(s.today.total)} (${split(s.today)})"
        l += "This month: ${bytes(s.month.total)} (${split(s.month)})"
        l += "Wi-Fi this month: ${bytes(s.month.wifi)}"
        if (s.topApps.isEmpty()) l += "Top apps: none yet"
        else {
            l += "Top apps this month:"
            s.topApps.take(TOP_APPS).forEachIndexed { i, a -> l += "${i + 1}. ${a.label} \u00b7 ${bytes(a.bytes)}" }
        }
        return l.joinToString("\n")
    }

    fun actions(s: Snapshot): List<Action> = when {
        !s.hasAccess -> listOf(Action(Act.GRANT, "Grant access"), Action(Act.OPEN, "Data Manager"))
        !s.hasPhone -> listOf(Action(Act.GRANT_PHONE, "Grant phone"), Action(Act.REFRESH, "Refresh"), Action(Act.OPEN, "Data Manager"))
        else -> listOf(Action(Act.REFRESH, "Refresh"), Action(Act.OPEN, "Data Manager"))
    }

    // ── the two month estimates, total and per SIM ───────────────────────

    /** True when each SIM's own figure was measured (one SIM, or the platform let the engine split them). */
    fun perSim(s: Snapshot) = s.hasPhone && s.sims.isNotEmpty() && (s.sims.size == 1 || s.sims.all { it.exact })

    /** This month's mobile estimate: month-to-date over elapsed days, times the days in the month. */
    fun monthEstimate(monthToDate: Long, s: Snapshot): Long? =
        forecastBytes(monthToDate, s.monthElapsedMs, s.monthLengthMs)

    /** The 30-day one: the average daily mobile use over the last 30 days, times the days in the month.
     *  Needs history: a window with no mobile bytes at all reads as none rather than as zero use. */
    fun avg30Estimate(last30: Long, s: Snapshot): Long? =
        if (last30 <= 0) null else forecastBytes(last30, s.last30WindowMs, s.monthLengthMs)

    /** "Month est: 12.30 GB (this month avg) \u00b7 10.80 GB (30-day avg)"; "learning" on day 1 for the first. */
    fun estLine(monthToDate: Long, last30: Long, s: Snapshot) =
        "Month est: ${monthPart(monthToDate, s)} (this month avg) \u00b7 ${avgPart(last30, s)} (30-day avg)"

    /** The compact pair used per SIM: "9.30 GB | 8.00 GB". */
    fun estPair(monthToDate: Long, last30: Long, s: Snapshot) = "${monthPart(monthToDate, s)} | ${avgPart(last30, s)}"

    private fun monthPart(used: Long, s: Snapshot) = monthEstimate(used, s)?.let(::bytes) ?: "learning"
    private fun avgPart(last30: Long, s: Snapshot) = avg30Estimate(last30, s)?.let(::bytes) ?: "no history"

    /** The mobile section of the expanded text, estimates first. */
    fun simLines(s: Snapshot): List<String> {
        val total = listOf(estLine(s.month.mobile, s.last30.mobile, s) + " \u2192 by ${s.monthEndLabel.ifBlank { "month end" }} (forecast)")
        if (!s.hasPhone) return total + "Mobile by SIM: needs the Phone permission (Grant phone). Mobile this month: ${bytes(s.month.mobile)}"
        if (s.sims.isEmpty()) return total + "Mobile: no active SIM"
        if (!perSim(s)) return total + ("Mobile: ${bytes(s.month.mobile)}, all SIMs together - Android 10+ does not give apps the " +
            "subscriber id, so it cannot be split per SIM (${s.sims.joinToString(", ") { simName(it) }})")
        return total + "Mobile by SIM:" + s.sims.map {
            "\u2022 ${simName(it)} \u00b7 ${bytes(it.monthBytes)} this month \u00b7 est ${monthPart(it.monthBytes, s)} (this month avg) \u00b7 " +
                "${avgPart(it.last30Bytes, s)} (30-day avg)"
        }
    }

    fun simName(x: Sim) = "SIM ${x.slot + 1}" + x.carrier.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()

    /**
     * used * monthLength / elapsed: usage so far (or over a window) divided by its elapsed days, times the days
     * in the month. Null until one full day has passed, because there is nothing to extrapolate from.
     */
    fun forecastBytes(used: Long, elapsedMs: Long, monthLengthMs: Long): Long? {
        if (elapsedMs < DAY_MS || monthLengthMs <= 0) return null
        return (used.coerceAtLeast(0).toDouble() * monthLengthMs / elapsedMs).toLong()
    }

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** "mobile 300 MB · Wi-Fi 900 MB". */
    fun split(w: Window) = "mobile ${bytes(w.mobile)} · Wi-Fi ${bytes(w.wifi)}"

    fun bytes(n: Long): String {
        val v = n.coerceAtLeast(0).toDouble()
        return when {
            v < 1024 -> "${n.coerceAtLeast(0)} B"
            v < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", v / 1024)
            v < 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024 * 1024))
            else -> String.format(Locale.US, "%.2f GB", v / (1024.0 * 1024 * 1024))
        }
    }
}
