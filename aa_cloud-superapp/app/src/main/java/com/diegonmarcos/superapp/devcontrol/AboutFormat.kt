package com.diegonmarcos.superapp.devcontrol

import java.text.DateFormat
import java.util.Date

/** The About page's number and time wording, pure so a JVM test pins it (AboutFormatTest). */
internal object AboutFormat {

    fun sizeStr(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GiB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.2f MiB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun fmtDuration(ms: Long): String {
        if (ms < 0) return "—"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min %02d s".format(m, sec)
            else -> "%d s".format(sec)
        }
    }

    fun fmtSecs(s: Long): String = when {
        s < 0 -> "—"
        s < 60 -> "${s}s"
        s < 3600 -> "%dm %02ds".format(s / 60, s % 60)
        else -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
    }

    fun fmtMillis(ms: Long): String = DateFormat.getDateTimeInstance().format(Date(ms))

    /**
     * The mesh IP to ping for a WG peer — prefer a /32 allowed-IP, then any non-network IPv4 allowed-IP, finally the endpoint
     * host. Null when nothing pingable can be derived.
     */
    fun meshIpOf(allowedIps: String, endpoint: String): String? {
        val cidrs = allowedIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val ipv4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        cidrs.firstOrNull { it.endsWith("/32") }?.substringBefore('/')?.let { return it }
        cidrs.map { it.substringBefore('/') }
            .firstOrNull { it.matches(ipv4) && it != "0.0.0.0" && !it.endsWith(".0") }
            ?.let { return it }
        return endpoint.substringBefore(':').trim().ifBlank { null }
    }

    /** The reachability verdict line for a ping that took [dtMs] (or timed out after [timeoutMs]). */
    fun pingVerdict(reachable: Boolean, dtMs: Long, timeoutMs: Int): String =
        if (reachable) "✓ reachable · $dtMs ms" else "✗ no answer ($timeoutMs ms)"
}
