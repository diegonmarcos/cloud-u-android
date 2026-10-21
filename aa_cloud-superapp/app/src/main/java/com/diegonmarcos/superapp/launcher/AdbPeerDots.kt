package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.adbdebug.AdbPeers
import com.diegonmarcos.superapp.adbdebug.PeerLink
import com.diegonmarcos.superapp.adbdebug.PeerStatus

/**
 * The home status strip's ADB readout: TWO dots under the "ADB" label, one per app that runs an adb channel of its own
 * (libs:shizuku-adb-debug-tools [AdbPeers.FLEET]: SuperApp first, Cloud Store second). Each dot is that app's own link:
 * green = up, amber = degraded or connecting, dim = down, not installed or not answering. The label itself still follows
 * USB / Wireless debugging; the dots are the channels the two apps hold over it.
 */
internal object AdbPeerDots {
    const val UP: Int = 0xFF4ADE80.toInt()
    const val WARN: Int = 0xFFFBBF24.toInt()
    const val OFF: Int = 0x55FFFFFF

    fun tint(link: PeerLink): Int = when (link) {
        PeerLink.UP -> UP
        PeerLink.DEGRADED, PeerLink.CONNECTING -> WARN
        PeerLink.DOWN, PeerLink.UNREACHABLE -> OFF
    }

    /** One link per app in [AdbPeers.FLEET] order; an app the reading did not name is [PeerLink.UNREACHABLE]. */
    fun links(statuses: List<PeerStatus>): List<PeerLink> =
        AdbPeers.FLEET.map { app -> statuses.firstOrNull { it.peer.applicationId == app.applicationId }?.link ?: PeerLink.UNREACHABLE }

    fun tints(statuses: List<PeerStatus>): List<Int> = links(statuses).map(::tint)

    /** The spoken form: "SuperApp up, Cloud Store down". */
    fun describe(statuses: List<PeerStatus>): String =
        AdbPeers.FLEET.zip(links(statuses)).joinToString(", ") { (app, l) -> "${app.label} ${AdbPeers.title(l)}" }
}

/** Two 2 dp dots, one per app, in the strip's own dot size (the same footprint class as SignalDots). */
@Composable
internal fun PeerDots(tints: List<Int>, description: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 1.dp).semantics { contentDescription = "ADB: $description" }) {
        tints.forEachIndexed { i, tint ->
            Box(Modifier.padding(start = if (i > 0) 2.dp else 0.dp).size(2.dp).background(Color(tint), CircleShape).testTag("strip:adb:dot:$i"))
        }
    }
}
