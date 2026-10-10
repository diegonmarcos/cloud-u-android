package com.diegonmarcos.superapp.network

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import com.diegonmarcos.superapp.adbdebug.AdbMdns
import com.diegonmarcos.superapp.adbdebug.ChannelReader
import com.diegonmarcos.superapp.adbdebug.ChannelState
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.adbdebug.title
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The network popup's ADB section. Every channel fact comes from libs:shizuku-adb-debug-tools
 * (ChannelReader / ChannelState / WirelessDebugging / EmbeddedAdbChannel / AdbMdns); nothing here
 * re-derives the channel's status. The slow reads (the channel probe, the mDNS port lookup) fill
 * their line in after the bubble is up.
 */
internal object AdbSection {

    data class Snap(
        val usb: Boolean,
        val wireless: Boolean,
        /** Wireless debugging exists from Android 11 (API 30). */
        val wirelessSupported: Boolean,
        val wifiIp: String?,
        /** "host:port" of the embedded adb session when it is connected and kept the port. */
        val sessionEndpoint: String?,
    )

    fun read(ctx: Context): Snap {
        val app = ctx.applicationContext
        return Snap(
            usb = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false),
            wireless = runCatching { WirelessDebugging.isOn(app) }.getOrDefault(false),
            wirelessSupported = Build.VERSION.SDK_INT >= 30,
            wifiIp = wifiIpv4(),
            sessionEndpoint = runCatching { EmbeddedAdbChannel.endpoint(app) }.getOrNull(),
        )
    }

    fun render(ctx: Context, s: Snap, into: LinearLayout, row: (Context, String) -> TextView) {
        val app = ctx.applicationContext
        into.addView(row(ctx, "USB debugging: ${onOff(s.usb)}"))
        into.addView(row(ctx, "Wireless debugging: " + if (s.wirelessSupported) onOff(s.wireless) else "— (Android 11+)"))
        if (s.wireless) {
            val known = s.sessionEndpoint?.takeIf { it.contains(':') && !it.startsWith("127.") }
            val line = row(ctx, "Wireless: " + (known ?: "${s.wifiIp ?: "—"}:… (looking up the port)"))
            into.addView(line)
            // The connect port rotates on every toggle and is only advertised over mDNS, which is how
            // the lib's own connect finds it: same lookup, our own address only, given up after 4 s.
            if (known == null) {
                val main = Handler(Looper.getMainLooper())
                var found = false
                val mdns = runCatching {
                    AdbMdns(app, AdbMdns.TLS_CONNECT) { host, port ->
                        main.post { found = true; line.text = "Wireless: ${host.hostAddress}:$port" }
                    }
                }.getOrNull()
                mdns?.start()
                main.postDelayed({
                    mdns?.stop()
                    if (!found) line.text = "Wireless: ${s.wifiIp ?: "—"}:— (port not advertised)"
                }, 4_000)
            }
        }
        val chan = row(ctx, "Shell channel: checking…")
        into.addView(chan)
        Thread {
            val text = runCatching {
                val f = ChannelReader.read(app)
                ChannelState.chip(f).removePrefix("ADB Shell: ").let { "Shell channel: $it · ${f.mode.title()} mode" }
            }.getOrElse { "Shell channel: — (could not read)" }
            chan.post { chan.text = text }
        }.start()
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /** This phone's own Wi-Fi IPv4 (the address Wireless debugging listens on). */
    fun wifiIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()
}

/**
 * The network popup's Hotspot section. Which tethering is on is public (sticky broadcasts, see
 * TetherModel); the SSID, band and clients are not, so they come from the privileged shell channel
 * when one is up and read "needs the ADB Shell channel" otherwise (the row under them opens it).
 */
internal object HotspotSection {

    fun render(ctx: Context, st: TetherModel.State, into: LinearLayout, row: (Context, String) -> TextView) {
        into.addView(row(ctx, "On: ${st.summary()}"))
        into.addView(row(ctx, "Wi-Fi hotspot ${dot(st.wifi)} · USB ${dot(st.usb)} · BT ${dot(st.bluetooth)}"))
        if (st.tethered.isNotEmpty()) into.addView(row(ctx, "Ifaces: ${st.tethered.joinToString(", ")}"))
        if (!st.active) return
        val ssid = if (st.wifi) row(ctx, "SSID: checking…").also { into.addView(it) } else null
        val clients = row(ctx, "Clients: checking…")
        into.addView(clients)
        val app = ctx.applicationContext
        Thread {
            val ch = runCatching { ShellChannels.active(app) }.getOrNull()
            if (ch == null) {
                val need = "— needs the ADB Shell channel"
                ssid?.post { ssid.text = "SSID: $need" }
                clients.post { clients.text = "Clients: $need" }
                return@Thread
            }
            val out = runCatching {
                ch.exec(app, "dumpsys wifi 2>/dev/null | grep -E 'mApConfig|mConnectedClient|SoftApInfo|mNumAssociatedStations' | head -40; echo $SPLIT; ip neigh show 2>/dev/null")
            }.getOrNull().orEmpty()
            val dump = out.substringBefore(SPLIT)
            val neigh = out.substringAfter(SPLIT, "")
            val ap = TetherModel.parseSoftAp(dump, Build.VERSION.SDK_INT)
            val n = TetherModel.countClients(neigh, st.tethered)
            val count = maxOf(n, ap.clients ?: 0)
            ssid?.post { ssid.text = "SSID: ${ap.ssid ?: "—"}" + (ap.band?.let { " · $it" } ?: "") }
            clients.post { clients.text = "Clients: " + if (out.isBlank()) "— (no answer from ${ch.name()})" else "$count" }
        }.start()
    }

    private const val SPLIT = "--neigh--"
    private fun dot(b: Boolean) = if (b) "on" else "off"
}

/**
 * The network popup's Cellular section: the strip's own reading (MobileLink) spelled out, then
 * every SIM (carrier + MCC/MNC, network, signal, mobile data, data roaming, voice over 5G / LTE,
 * Wi-Fi calling, preferred network, 5G NSA / SA, RSRP / RSRQ / SINR). The public API answers first;
 * the privileged switches (VoLTE / VoNR / Wi-Fi calling, preferred network mode) come through the
 * fleet's shell channel when one is up and read "— needs ADB Shell" otherwise, as Hotspot does.
 */
internal object CellularSection {

    fun render(
        ctx: Context, into: LinearLayout, cellularTransport: Boolean, extra: List<String>,
        row: (Context, String) -> TextView, link: (Context, String, () -> Unit) -> TextView,
        group: (Context) -> LinearLayout, onGrant: () -> Unit,
    ) {
        val app = ctx.applicationContext
        val inputs = runCatching { MobileProbe.read(app, cellularTransport) }
            .getOrElse { MobileLink.Inputs(false, emptyList(), MobileLink.NO_SUB, cellularTransport) }
        into.addView(row(ctx, MobileLink.stateLine(MobileLink.derive(inputs))))
        val phone = MobileProbe.phoneGranted(app)
        val blocks = inputs.sims.map { s ->
            val isData = s.subId == inputs.defaultDataSubId && s.subId >= 0
            val d = runCatching { MobileProbe.detail(app, s) }.getOrDefault(MobileLink.Detail())
            val box = group(ctx)
            fill(ctx, box, MobileLink.simLines(s, isData, phone, d), row)
            into.addView(box)
            Block(s, isData, d, box)
        }
        if (!phone) {
            into.addView(row(ctx, "Phone permission not granted: other SIMs, network types and carrier settings read —"))
            into.addView(link(ctx, "Grant ›", onGrant))
        }
        for (e in extra) into.addView(row(ctx, e))
        val todo = blocks.filter { b -> b.sim.subId >= 0 && (b.d.volte == null || b.d.wfc == null || b.d.vonr == null || b.d.preferredMode == null) }
        if (todo.isEmpty()) return
        Thread {
            val ch = runCatching { ShellChannels.active(app) }.getOrNull()
            val out = ch?.let { c -> runCatching { c.exec(app, MobileProbe.shellCommand(todo.map { it.sim.subId })) }.getOrNull() }
            for (b in todo) {
                val d2 = if (out.isNullOrBlank()) b.d.copy(needsShell = ch == null) else MobileProbe.withShell(b.d, out, b.sim.subId)
                val lines = MobileLink.simLines(b.sim, b.isData, phone, d2)
                b.box.post { fill(ctx, b.box, lines, row) }
            }
        }.start()
    }

    private class Block(val sim: MobileLink.Sim, val isData: Boolean, val d: MobileLink.Detail, val box: LinearLayout)

    private fun fill(ctx: Context, box: LinearLayout, lines: List<String>, row: (Context, String) -> TextView) {
        box.removeAllViews()
        for (l in lines) box.addView(row(ctx, l))
    }
}

/**
 * The network popup's GPS section: the location switch and mode, the providers, the permission,
 * the current / last fix (source, accuracy, age), the GNSS constellations in view / used with
 * their bands, and whether the receiver is dual-frequency. The live part (GNSS fixes + satellite
 * status) is a [GpsProbe.LiveSession] the popup runs only while this section is on screen.
 */
internal object GpsSection {

    /** Renders into [into]; returns the live session for the popup to start / stop, or null when none can run. */
    fun render(
        ctx: Context, into: LinearLayout,
        row: (Context, String) -> TextView, link: (Context, String, () -> Unit) -> TextView,
        group: (Context) -> LinearLayout, onGrant: () -> Unit, onSettings: () -> Unit,
    ): GpsProbe.LiveSession? {
        val app = ctx.applicationContext
        val on = GpsProbe.locationOn(app)
        into.addView(row(ctx, "Location: " + if (on) "ON · mode ${GpsFix.modeName(GpsProbe.mode(app))}" else "OFF"))
        into.addView(row(ctx, GpsProbe.providersLine(app)))
        GpsProbe.scanningLine(app)?.let { into.addView(row(ctx, it)) }
        val fine = GpsProbe.fineGranted(app)
        if (!fine) {
            into.addView(row(ctx, if (GpsProbe.coarseGranted(app)) "Permission: approximate only — satellites and GNSS fixes need precise location"
                else "Permission: location not granted — fix and satellites read —"))
            into.addView(link(ctx, "Grant ›", onGrant))
        }
        val live = group(ctx)
        into.addView(live)
        var session: GpsProbe.LiveSession? = null
        fun paint() {
            live.removeAllViews()
            val s = session
            val loc = s?.fix ?: GpsProbe.lastKnown(app)
            val f = loc?.let { GpsProbe.fixOf(it, usedSats = if (it.provider == "gps") s?.usedSats() else null) }
            val sats = s?.sats.orEmpty()
            live.addView(row(ctx, "Fix quality ${SignalLevels.label(GpsFix.dots(on, f))}"))
            live.addView(row(ctx, GpsFix.fixLine(f)))
            for (l in GpsFix.satelliteLines(sats, f, s?.running == true)) live.addView(row(ctx, l))
            for (l in GpsProbe.capabilityLines(app, GpsFix.dualFrequencySeen(sats))) live.addView(row(ctx, l))
        }
        session = if (fine && on) GpsProbe.LiveSession(app) { paint() } else null
        paint()
        into.addView(link(ctx, "Location settings ›", onSettings))
        return session
    }
}
