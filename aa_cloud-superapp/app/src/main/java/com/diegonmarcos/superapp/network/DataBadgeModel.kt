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

    data class Snapshot(
        /** PACKAGE_USAGE_STATS (Usage Access) granted. */
        val hasAccess: Boolean,
        val today: Window = Window(),
        /** The month so far (the engine's startOfMonth window). */
        val month: Window = Window(),
        /** Biggest apps of the month, already sorted; the model shows [TOP_APPS]. */
        val topApps: List<App> = emptyList(),
    )

    enum class Act { GRANT, REFRESH, OPEN }

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
        )
        if (s.topApps.isEmpty()) l += "Top apps: none yet"
        else {
            l += "Top apps this month:"
            s.topApps.take(TOP_APPS).forEachIndexed { i, a -> l += "${i + 1}. ${a.label} · ${bytes(a.bytes)}" }
        }
        return l.joinToString("\n")
    }

    fun actions(s: Snapshot): List<Action> =
        if (!s.hasAccess) listOf(Action(Act.GRANT, "Grant access"), Action(Act.OPEN, "Data Manager"))
        else listOf(Action(Act.REFRESH, "Refresh"), Action(Act.OPEN, "Data Manager"))

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
