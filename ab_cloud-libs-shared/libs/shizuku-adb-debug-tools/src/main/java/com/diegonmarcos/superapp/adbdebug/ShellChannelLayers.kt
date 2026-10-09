package com.diegonmarcos.superapp.adbdebug

/**
 * The layers of the privileged path as plain facts, and the lines the "ADB Shell" page and the
 * Store show for them. Pure: [describe] is tested, [ShellChannelProbe] fills it in on a device.
 */
data class ShellChannelLayers(
    val mode: ChannelMode,
    val wirelessDebug: Boolean,
    val adbPaired: Boolean,
    val adbConnected: Boolean,
    val serverPort: Int,
    val serverRunning: Boolean,
    val serverUid: String?,
    val serverUptime: String?,
    val shizuku: ShizukuState,
    val route: String?,
) {
    fun describe(): List<Pair<String, String>> = listOf(
        "Wireless debugging" to if (wirelessDebug) "on" else "off",
        "Embedded adb" to when {
            adbConnected -> "paired, connected"
            adbPaired -> "paired, not connected"
            else -> "not paired"
        },
        "Local server :$serverPort" to if (!serverRunning) "not running" else
            "running" + (serverUid?.let { ", $it" } ?: "") + (serverUptime?.let { ", up $it" } ?: ""),
        "Shizuku" to ChannelSelector.shizukuLabel(shizuku).removePrefix("Shizuku: "),
        "Active route" to (route ?: "none"),
    )

    companion object {
        /** `uid=2000(shell) gid=...` -> "uid 2000"; anything else null. */
        fun uidOf(idOutput: String?): String? =
            idOutput?.let { Regex("""uid=(\d+)""").find(it)?.groupValues?.get(1) }?.let { "uid $it" }
    }
}
