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

    fun title(s: Snapshot) =
        if (!s.hasAccess) "Data · Usage access needed" else "Data · ${bytes(s.today.total)} today"

    fun collapsed(s: Snapshot): String =
        if (!s.hasAccess) "Grant Usage Access to see data usage"
        else "Today ${split(s.today)} · Month ${bytes(s.month.total)}"

    fun expanded(s: Snapshot): String {
        if (!s.hasAccess) return "Data usage is read from the system's network statistics, which needs the " +
            "Usage Access special permission. Tap Grant access, pick Cloud SuperApp and switch it on."
        val l = mutableListOf(
            "Today: ${bytes(s.today.total)} (${split(s.today)})",
            "This month: ${bytes(s.month.total)} (${split(s.month)})",
            "Wi-Fi this month: ${bytes(s.month.wifi)}",
        )
        l += simLines(s)
        if (s.topApps.isEmpty()) l += "Top apps: none yet"
        else {
            l += "Top apps this month:"
            s.topApps.take(TOP_APPS).forEachIndexed { i, a -> l += "${i + 1}. ${a.label} · ${bytes(a.bytes)}" }
        }
        return l.joinToString("\n")
    }

    fun actions(s: Snapshot): List<Action> = when {
        !s.hasAccess -> listOf(Action(Act.GRANT, "Grant access"), Action(Act.OPEN, "Data Manager"))
        !s.hasPhone -> listOf(Action(Act.GRANT_PHONE, "Grant phone"), Action(Act.REFRESH, "Refresh"), Action(Act.OPEN, "Data Manager"))
        else -> listOf(Action(Act.REFRESH, "Refresh"), Action(Act.OPEN, "Data Manager"))
    }

    // ── mobile per SIM, with the month-end forecast ──────────────────────

    /** The mobile section of the expanded text. */
    fun simLines(s: Snapshot): List<String> {
        if (!s.hasPhone) return listOf(
            "Mobile by SIM: needs the Phone permission (Grant phone). Mobile this month: ${bytes(s.month.mobile)}",
            "Mobile forecast: " + forecast(s.month.mobile, s))
        if (s.sims.isEmpty()) return listOf("Mobile: no active SIM")
        val measured = s.sims.size == 1 || s.sims.all { it.exact }
        if (!measured) return listOf(
            "Mobile: ${bytes(s.month.mobile)}, all SIMs together - Android 10+ does not give apps the subscriber id, " +
                "so it cannot be split per SIM (${s.sims.joinToString(", ") { simName(it) }})",
            "Mobile forecast: " + forecast(s.month.mobile, s))
        return listOf("Mobile by SIM:") + s.sims.map {
            "\u2022 ${simName(it)} \u00b7 ${bytes(it.monthBytes)} \u00b7 " + forecast(it.monthBytes, s)
        }
    }

    fun simName(x: Sim) = "SIM ${x.slot + 1}" + x.carrier.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()

    /**
     * Month-to-date divided by the elapsed days, times the days in the month:
     * used * monthLength / elapsed. Before one full day has passed there is
     * nothing to extrapolate from, so it says "learning" instead of a number.
     */
    fun forecastBytes(used: Long, elapsedMs: Long, monthLengthMs: Long): Long? {
        if (elapsedMs < DAY_MS || monthLengthMs <= 0) return null
        return (used.coerceAtLeast(0).toDouble() * monthLengthMs / elapsedMs).toLong()
    }

    fun forecast(used: Long, s: Snapshot): String =
        forecastBytes(used, s.monthElapsedMs, s.monthLengthMs)
            ?.let { "\u2248 ${bytes(it)} by ${s.monthEndLabel.ifBlank { "month end" }} (forecast)" }
            ?: "forecast: learning"

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
