package com.diegonmarcos.superapp.wifi

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

// ---- Channels ----

private val bandSel = mutableStateOf(Band.B24)

internal fun LazyListScope.channelsTab(c: WifiController, s: WifiState, now: Long, layout: WifiLayout) {
    item("bands") {
        WCard {
            WRow { listOf(Band.B24, Band.B5, Band.B6).forEach { b -> WChoice(b.label, bandSel.value == b, "ch-band-${b.id}") { bandSel.value = b } } }
            WMuted(t(R.string.wifi_plan) + " · " + s.country.ifEmpty { "?" })
            WRow { c.plans.plans.forEach { p -> WChoice(p.id, s.planId == p.id, "plan-${p.id}") { c.setPlan(p.id) } } }
            WMuted(c.plan().label)
        }
    }
    val band = bandSel.value
    val plan = c.plan()
    item("spectrum") {
        WCard(Modifier.testTag("wifi:channels:spectrum")) {
            WTitle(t(R.string.wifi_spectrum) + " · " + band.label, "bar_chart")
            SpectrumChart(s.aps, band, plan, s.selected, layout.chart)
        }
    }
    val scores = ChannelRating.rate(s.aps, plan, band, c.cfg.dfsPenalty)
    val best = ChannelRating.best(scores, c.cfg.nonOverlap24)
    val cong = ChannelRating.congestion(s.aps, plan, band)
    item("best") {
        WCard(Modifier.testTag("wifi:channels:best")) {
            WTitle(if (best != null) t(R.string.wifi_best, "${best.channel} (${best.rating}/10${if (best.dfs) ", DFS" else ""})") else t(R.string.wifi_best_none), "speed")
            WPill(t(R.string.wifi_congestion, cong.score, cong.aps, cong.busiest), if (cong.score < 35) WifiOk else if (cong.score < 70) WifiWarn else WifiBad)
            s.link.takeIf { it.connected && it.band == band }?.let { l ->
                val mine = scores.firstOrNull { it.channel == l.channel }
                if (mine != null) WMuted("${t(R.string.wifi_link_title)}: ch ${l.channel} = ${mine.rating}/10")
            }
            WMuted(t(R.string.wifi_rating_note))
        }
    }
    item("ratings") {
        WCard(Modifier.testTag("wifi:channels:ratings")) {
            WTitle(t(R.string.wifi_ratings))
            scores.forEach { sc ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${sc.channel}", Modifier.width(34.dp), fontSize = 11.sp, fontWeight = if (sc.channel == best?.channel) FontWeight.Bold else FontWeight.Normal)
                    RatingBar(sc.rating, Modifier.weight(1f))
                    Text("${sc.rating}", Modifier.width(30.dp), fontSize = 10.sp)
                    WMuted("${sc.aps} AP" + (if (sc.dfs) " · " + t(R.string.wifi_dfs) else "") + (if (sc.note.isNotEmpty()) " · ${sc.note}" else ""), Modifier.width(110.dp), max = 1)
                }
            }
        }
    }
    item("time") {
        val top = s.aps.filter { it.band == band }.sortedByDescending { it.smoothed }.take(8)
        WCard(Modifier.testTag("wifi:channels:time")) {
            WTitle(t(R.string.wifi_time_graph), "timeline")
            TimeChart(top.map { it.bssid to it.history }, 10 * 60_000L, layout.chart, now = now)
            WRow { top.forEach { WPill(it.displaySsid.take(16) + " ${it.smoothed.roundToInt()}", apColor(it.bssid)) } }
        }
    }
}

// ---- Link ----

internal fun LazyListScope.linkTab(c: WifiController, s: WifiState, now: Long, layout: WifiLayout) {
    val l = s.link
    val th = c.cfg.rssi
    item("link") {
        WCard(Modifier.testTag("wifi:link")) {
            WTitle(t(R.string.wifi_link_title), "router")
            if (!l.connected) { WMuted(t(R.string.wifi_not_connected)); return@WCard }
            val yes = t(R.string.wifi_yes); val no = t(R.string.wifi_no)
            WKv(t(R.string.wifi_l_ssid), l.ssid + if (l.hiddenSsid) " (hidden)" else "")
            WKv(t(R.string.wifi_l_bssid), l.bssid)
            WKv(t(R.string.wifi_l_vendor), s.aps.firstOrNull { it.bssid == l.bssid }?.vendor.orEmpty())
            WKv(t(R.string.wifi_l_signal), "${l.rssi} dBm", signalColor(th, l.rssi))
            WKv(t(R.string.wifi_l_quality), "${th.quality(l.rssi)}%")
            WKv(t(R.string.wifi_l_snr), "~${th.snr(l.rssi)} dB (noise floor ${th.noiseFloor} dBm)")
            WKv(t(R.string.wifi_l_freq), "${l.freqMhz} MHz · ${l.band.label} ch ${l.channel}")
            WKv(t(R.string.wifi_l_speed), if (l.linkMbps >= 0) "${l.linkMbps} Mbps" else "")
            WKv(t(R.string.wifi_l_txrx), "${l.txMbps.takeIf { it >= 0 } ?: "-"} / ${l.rxMbps.takeIf { it >= 0 } ?: "-"} Mbps")
            WKv(t(R.string.wifi_l_max), "${l.maxTxMbps.takeIf { it >= 0 } ?: "-"} / ${l.maxRxMbps.takeIf { it >= 0 } ?: "-"} Mbps")
            WKv(t(R.string.wifi_l_std), "${l.std.gen} ${l.std.label}".trim())
            WKv(t(R.string.wifi_l_security), l.securityType)
            WKv(t(R.string.wifi_l_iface), l.iface)
            WKv(t(R.string.wifi_l_ipv4), if (l.ipv4.isNotEmpty()) "${l.ipv4}/${l.prefix} (${Ipv4.prefixToMask(l.prefix)})" else "")
            WKv(t(R.string.wifi_l_gateway), l.gateway)
            WKv(t(R.string.wifi_l_dns), l.dns.joinToString(", "))
            WKv(t(R.string.wifi_l_dhcp), l.dhcpServer)
            WKv(t(R.string.wifi_l_lease), if (l.leaseSeconds > 0) "${l.leaseSeconds / 3600}h ${l.leaseSeconds % 3600 / 60}m" else "")
            WKv(t(R.string.wifi_l_mtu), if (l.mtu > 0) "${l.mtu}" else "")
            WKv(t(R.string.wifi_l_ipv6), l.ipv6.joinToString("\n"))
            WKv(t(R.string.wifi_l_domains), l.domains)
            WKv(t(R.string.wifi_l_proxy), l.proxy)
            WKv(t(R.string.wifi_l_captive), if (l.captivePortal) yes else no, if (l.captivePortal) WifiWarn else null)
            WKv(t(R.string.wifi_l_validated), if (l.validated) yes else no, if (l.validated) WifiOk else WifiWarn)
            WKv(t(R.string.wifi_l_metered), if (l.metered) yes else no)
            WKv(t(R.string.wifi_l_private_dns), if (l.privateDnsActive) t(R.string.wifi_active) + " " + l.privateDnsServer else t(R.string.wifi_off))
            WKv(t(R.string.wifi_l_bw), "${l.downKbps / 1000} / ${l.upKbps / 1000} Mbps")
            WKv(t(R.string.wifi_l_mac), l.macRandomization)
            if (l.apMldMac.isNotEmpty()) WKv(t(R.string.wifi_l_mld), l.apMldMac)
            l.mloLinks.forEach { m -> WKv(t(R.string.wifi_l_links) + " ${m.linkId}", "${m.bssid} ${m.band.label} ch ${m.channel}") }
        }
    }
    item("link-graph") {
        WCard {
            WTitle(t(R.string.wifi_time_graph), "timeline")
            TimeChart(listOf(l.bssid.ifEmpty { "link" } to s.linkHistory), 5 * 60_000L, layout.chart, now = now)
        }
    }
    item("caps") {
        val y = t(R.string.wifi_yes); val n = t(R.string.wifi_no)
        fun yn(b: Boolean) = if (b) y else n
        WCard { WTitle(t(R.string.wifi_device_caps), "settings"); WMuted(t(R.string.wifi_caps_line, yn(s.caps.band5), yn(s.caps.band6), yn(s.caps.wifi7), yn(s.caps.rtt), yn(s.caps.staAp))) }
    }
    item("roams-title") { WTitle(t(R.string.wifi_roaming), "history") }
    if (s.roams.isEmpty()) item("roams-empty") { WMuted(t(R.string.wifi_no_roams)) }
    items(s.roams.reversed(), key = { "roam" + it.t + it.toBssid }) { r ->
        WMuted("${clock(r.t)} ${r.kind} ${r.ssid}: ${r.fromBssid.ifEmpty { "-" }} (${r.fromRssi}) -> ${r.toBssid.ifEmpty { "-" }} (${r.toRssi}) ${r.fromFreq}->${r.toFreq} MHz", mono = true)
    }
}

// ---- Tools ----

internal fun LazyListScope.toolsTab(c: WifiController, s: WifiState, now: Long, layout: WifiLayout) {
    item("ping") { PingCard(c, s, layout) }
    item("trace") { TraceCard(c, s) }
    item("dns") { DnsCard(c, s) }
    item("ports") { PortCard(c, s) }
    item("lan") { LanCard(c, s) }
    item("tp") { ThroughputCard(c, s, layout) }
    item("rtt") { RttCard(c, s, now) }
    item("locator") { LocatorCard(c, s, layout) }
}

@Composable
private fun HostField(c: WifiController, s: WifiState, host: String, tag: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WField(host, t(R.string.wifi_host), tag, onChange, Modifier.weight(1f))
        if (s.link.gateway.isNotEmpty()) WChoice(t(R.string.wifi_gateway), host == s.link.gateway, "$tag-gw") { onChange(s.link.gateway) }
    }
}

@Composable
private fun PingCard(c: WifiController, s: WifiState, layout: WifiLayout) {
    var host by rememberSaveable { mutableStateOf("") }
    val h = host.ifEmpty { s.link.gateway.ifEmpty { c.cfg.pingDefaultHost } }
    WCard(Modifier.testTag("wifi:tool:ping")) {
        WTitle(t(R.string.wifi_tool_ping), "speed")
        HostField(c, s, h, "ping") { host = it }
        WRow {
            if (s.ping.running) WButton(t(R.string.wifi_stop), tag = "ping-stop") { c.stopPing() } else WButton(t(R.string.wifi_start), primary = true, tag = "ping-start") { c.ping(h.trim()) }
            WChoice(c.cfg.pingDefaultHost, h == c.cfg.pingDefaultHost, "ping-public") { host = c.cfg.pingDefaultHost }
        }
        if (s.ping.rtts.isNotEmpty()) ValueChart(s.ping.rtts, WifiBlue, "ms", if (layout.wide) 140.dp else 100.dp)
        val st = s.ping.stats ?: if (s.ping.rtts.isNotEmpty()) PingStats.of(s.ping.lines.count { PingParser.reply(it) != null }, s.ping.rtts) else null
        st?.let { WPill(t(R.string.wifi_ping_stats, it.sent, it.received, it.lossPct, it.min, it.avg, it.max, it.jitter), if (it.lossPct > 2 || it.jitter > 30) WifiWarn else WifiOk) }
        if (s.ping.error.isNotEmpty()) WPill(s.ping.error, WifiBad)
        s.ping.lines.takeLast(4).forEach { WMuted(it, mono = true, max = 1) }
    }
}

@Composable
private fun TraceCard(c: WifiController, s: WifiState) {
    var host by rememberSaveable { mutableStateOf("") }
    val h = host.ifEmpty { c.cfg.pingDefaultHost }
    WCard(Modifier.testTag("wifi:tool:trace")) {
        WTitle(t(R.string.wifi_tool_trace), "lan")
        HostField(c, s, h, "trace") { host = it }
        if (s.trace.running) WButton(t(R.string.wifi_stop), tag = "trace-stop") { c.stopTrace() } else WButton(t(R.string.wifi_start), primary = true, tag = "trace-start") { c.traceroute(h.trim()) }
        WMuted(t(R.string.wifi_trace_note))
        s.trace.hops.forEach { hop -> WMuted("%2d  %-15s %s %s".format(Locale.ROOT, hop.ttl, hop.ip ?: "*", hop.ms?.let { "%.0f ms".format(Locale.ROOT, it) } ?: "", hop.name), mono = true, max = 1) }
        if (s.trace.error.isNotEmpty()) WPill(s.trace.error, WifiBad)
    }
}

@Composable
private fun DnsCard(c: WifiController, s: WifiState) {
    var name by rememberSaveable { mutableStateOf(c.cfg.dnsDefaultName) }
    WCard(Modifier.testTag("wifi:tool:dns")) {
        WTitle(t(R.string.wifi_tool_dns), "search")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WField(name, t(R.string.wifi_name), "dns", { name = it }, Modifier.weight(1f))
            WButton(t(R.string.wifi_lookup), primary = true, tag = "dns-go") { c.dns(name) }
        }
        WMuted("DNS: " + s.link.dns.joinToString(", ").ifEmpty { "-" } + if (s.link.privateDnsActive) " · DoT ${s.link.privateDnsServer}" else "")
        s.dns.take(5).forEach { a ->
            WMuted("${a.name}  %.1f ms%s".format(Locale.ROOT, a.ms, if (a.viaWifi) " (wlan)" else ""), mono = true)
            if (a.error.isNotEmpty()) WPill(a.error, WifiBad) else WMuted(a.addresses.joinToString("  ") + if (a.reverse.isNotEmpty()) "  ← ${a.reverse}" else "", mono = true)
        }
    }
}

@Composable
private fun PortCard(c: WifiController, s: WifiState) {
    var host by rememberSaveable { mutableStateOf("") }
    var spec by rememberSaveable { mutableStateOf(c.cfg.portDefaultSpec) }
    val h = host.ifEmpty { s.link.gateway }
    WCard(Modifier.testTag("wifi:tool:ports")) {
        WTitle(t(R.string.wifi_tool_ports), "lan")
        HostField(c, s, h, "ports") { host = it }
        WField(spec, t(R.string.wifi_ports), "port-spec", { spec = it }, Modifier.fillMaxWidth())
        WMuted(t(R.string.wifi_ports_note, c.cfg.portMax))
        if (s.ports.running) WButton(t(R.string.wifi_stop), tag = "ports-stop") { c.stopPorts() } else WButton(t(R.string.wifi_start), primary = true, enabled = h.isNotBlank(), tag = "ports-start") { c.portScan(h.trim(), spec) }
        if (s.ports.total > 0) WMuted(t(R.string.wifi_ports_progress, s.ports.done, s.ports.total, s.ports.open.size))
        if (s.ports.error.isNotEmpty()) WPill(s.ports.error, WifiBad)
        WRow { s.ports.open.forEach { p -> WPill("${p.port}${if (p.service.isNotEmpty()) " " + p.service else ""} %.0fms".format(Locale.ROOT, p.ms), WifiOk) } }
    }
}

@Composable
private fun LanCard(c: WifiController, s: WifiState) {
    val ctx = LocalContext.current
    WCard(Modifier.testTag("wifi:tool:lan")) {
        WTitle(t(R.string.wifi_tool_lan), "router")
        WMuted(t(R.string.wifi_lan_note))
        if (s.lan.running) WButton(t(R.string.wifi_stop), tag = "lan-stop") { c.stopDiscover() } else WButton(t(R.string.wifi_discover), primary = true, enabled = s.link.ipv4.isNotEmpty(), tag = "lan-start") { c.discover() }
        if (s.lan.total > 0 || s.lan.devices.isNotEmpty()) WMuted(t(R.string.wifi_lan_progress, s.lan.phase.ifEmpty { "done" }, s.lan.done, s.lan.total, s.lan.devices.size))
        s.lan.devices.forEach { d ->
            Column(Modifier.fillMaxWidth().clickable { copy(ctx, d.ip) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    d.rttMs?.let { WMuted("%.0f ms".format(Locale.ROOT, it)) }
                    d.sources.forEach { WPill(it, WifiBlue) }
                }
                WMuted(d.ip + (if (d.ip == s.link.gateway) "  (gateway)" else "") + (if (d.server.isNotEmpty()) "  ${d.server}" else ""), mono = true, max = 1)
                if (d.services.isNotEmpty()) WMuted(d.services.joinToString("  "), mono = true, max = 2)
            }
        }
    }
}

@Composable
private fun ThroughputCard(c: WifiController, s: WifiState, layout: WifiLayout) {
    WCard(Modifier.testTag("wifi:tool:throughput")) {
        WTitle(t(R.string.wifi_tool_tp), "download")
        WMuted(t(R.string.wifi_tp_endpoint, c.cfg.downloadUrlFor().substringBefore('?')), mono = true)
        if (s.throughputRunning) WButton(t(R.string.wifi_stop), tag = "tp-stop") { c.stopThroughput() } else WButton(t(R.string.wifi_start), primary = true, tag = "tp-start") { c.throughput() }
        listOfNotNull(s.down?.let { t(R.string.wifi_down) to it }, s.up?.let { t(R.string.wifi_up) to it }).forEach { (label, tp) ->
            WPill("$label %.1f Mbps · %.1f MB · %.1fs%s".format(Locale.ROOT, tp.mbps, tp.bytes / 1e6, tp.seconds, if (tp.error.isNotEmpty()) " · " + tp.error else ""), if (tp.error.isNotEmpty()) WifiWarn else WifiOk)
            if (tp.samples.isNotEmpty()) ValueChart(tp.samples, if (tp.direction == "download") WifiOk else WifiBlue, "Mbps", if (layout.wide) 120.dp else 80.dp)
        }
    }
}

@Composable
private fun RttCard(c: WifiController, s: WifiState, now: Long) {
    val responders = s.aps.filter { it.rtt80211mc || it.rtt80211az }.sortedByDescending { it.smoothed }
    WCard(Modifier.testTag("wifi:tool:rtt")) {
        WTitle(t(R.string.wifi_tool_rtt), "sensors")
        when {
            !s.caps.rtt -> WMuted(t(R.string.wifi_rtt_unsupported))
            !s.caps.rttAvailable -> WMuted(t(R.string.wifi_rtt_unavailable))
            responders.isEmpty() -> WMuted(t(R.string.wifi_rtt_none))
            else -> {
                WRow {
                    if (s.rttRunning) WButton(t(R.string.wifi_stop), tag = "rtt-stop") { c.stopRtt() }
                    else {
                        WButton(t(R.string.wifi_continuous), primary = true, tag = "rtt-cont") { c.startRtt(responders.take(10).map { it.bssid }, true) }
                        WButton(t(R.string.wifi_once), tag = "rtt-once") { c.startRtt(responders.take(10).map { it.bssid }, false) }
                    }
                }
                responders.take(10).forEach { ap ->
                    val r = s.rtt[ap.bssid]?.lastOrNull { it.ok }
                    WKv(ap.displaySsid.take(18) + " " + ap.bssid.takeLast(5),
                        r?.let { "%.2f m ±%.2f · %d/%d · %s".format(Locale.ROOT, it.distanceM, it.stdDevM, it.succeeded, it.attempted, ago(now - it.at)) }
                            ?: s.rtt[ap.bssid]?.lastOrNull()?.status.orEmpty(), apColor(ap.bssid))
                }
            }
        }
    }
}

@Composable
private fun LocatorCard(c: WifiController, s: WifiState, layout: WifiLayout) {
    WCard(Modifier.testTag("wifi:tool:locator")) {
        WTitle(t(R.string.wifi_tool_locator), "sensors")
        WMuted(t(R.string.wifi_locator_note))
        val target = s.locatorTarget ?: s.selected ?: s.link.bssid.ifEmpty { null }
        val ap = target?.let { tg -> s.aps.firstOrNull { it.bssid == tg } }
        WMuted(if (target == null) t(R.string.wifi_no_target) else t(R.string.wifi_target, (ap?.displaySsid ?: s.link.ssid) + " " + target))
        WRow {
            if (s.locatorOn) WButton(t(R.string.wifi_stop), tag = "loc-stop") { c.setLocator(false) }
            else WButton(t(R.string.wifi_start), primary = true, enabled = target != null, tag = "loc-start") { c.setLocator(true, target) }
            WSwitch(t(R.string.wifi_sound), s.locatorSound) { c.setLocatorSound(it) }
        }
        if (s.locatorSamples.isNotEmpty()) {
            val v = s.locatorSamples.last()
            val tr = Locator.trend(s.locatorSamples.takeLast(10))
            Text("${v.roundToInt()} dBm", fontSize = if (layout.wide) 44.sp else 34.sp, fontWeight = FontWeight.Bold, color = signalColor(c.cfg.rssi, v.roundToInt()))
            WPill(when (tr) { 1 -> t(R.string.wifi_warmer); -1 -> t(R.string.wifi_colder); else -> t(R.string.wifi_steady) }, when (tr) { 1 -> WifiOk; -1 -> WifiBad; else -> WifiBlue })
            ValueChart(s.locatorSamples.map { it + 100 }, WifiOk, "dB over -100", 80.dp)
        }
    }
}

// ---- Log ----

internal fun LazyListScope.logTab(c: WifiController, s: WifiState, now: Long, layout: WifiLayout) {
    item("heat") { HeatCard(c, s, layout) }
    item("export") { ExportCard(c, s) }
    item("events-title") {
        val ctx = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WTitle(t(R.string.wifi_events), "history")
            WButton(t(R.string.wifi_copy), tag = "events-copy") { copy(ctx, s.events.joinToString("\n")) }
        }
    }
    if (s.events.isEmpty()) item("events-empty") { WMuted(t(R.string.wifi_no_events)) }
    items(s.events.reversed().take(200), key = null) { WMuted(it, mono = true) }
}

@Composable
private fun HeatCard(c: WifiController, s: WifiState, layout: WifiLayout) {
    val hm = s.heat
    val target = hm.target.ifEmpty { s.link.bssid }
    WCard(Modifier.testTag("wifi:heatmap")) {
        WTitle(t(R.string.wifi_heatmap), "map")
        WMuted(t(R.string.wifi_heat_body, c.cfg.heatSamples))
        WMuted(if (target.isEmpty()) t(R.string.wifi_no_target) else t(R.string.wifi_target, (s.aps.firstOrNull { it.bssid == target }?.displaySsid ?: s.link.ssid) + " " + target))
        Column(Modifier.fillMaxWidth(if (layout.wide) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (r in 0 until hm.rows) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (col in 0 until hm.cols) {
                    val cell = hm.cells[hm.key(r, col)]
                    val outline = MaterialTheme.colorScheme.outline
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).background(cell?.let { heatColor(it.mean) } ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            .border(if (hm.best == cell && cell != null) 2.dp else 0.5.dp, outline)
                            .clickable(enabled = target.isNotEmpty()) { c.heatRecord(r, col) }.testTag("wifi:heat:$r:$col"),
                        contentAlignment = Alignment.Center,
                    ) { if (cell != null) Text("${cell.mean.roundToInt()}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
        WButton(t(R.string.wifi_clear), tag = "heat-clear") { c.heatClear() }
    }
}

@Composable
private fun ExportCard(c: WifiController, s: WifiState) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Triple<String, String, ByteArray>?>(null) }
    var msg by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pending
        if (uri != null && p != null) msg = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(p.third) }; "${p.first} · ${p.third.size} B" }.getOrElse { it.message ?: "write failed" }
        pending = null
    }
    val tooBig = t(R.string.wifi_too_big)
    WCard(Modifier.testTag("wifi:export")) {
        WTitle(t(R.string.wifi_export), "share")
        WMuted(t(R.string.wifi_export_note))
        listOf("csv" to "CSV", "json" to "JSON", "pcap" to "PCAP", "heat" to "Heatmap CSV").forEach { (kind, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, Modifier.width(96.dp), fontSize = 12.sp)
                WButton(t(R.string.wifi_save), tag = "save-$kind") { val e = c.export(kind); pending = e; save.launch(e.first) }
                if (kind != "pcap") WButton(t(R.string.wifi_share), tag = "share-$kind") {
                    val e = c.export(kind)
                    if (e.third.size > 400_000) msg = tooBig
                    else runCatching {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, e.first); putExtra(Intent.EXTRA_TEXT, String(e.third))
                        }, e.first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        if (msg.isNotEmpty()) WMuted(msg)
    }
}
