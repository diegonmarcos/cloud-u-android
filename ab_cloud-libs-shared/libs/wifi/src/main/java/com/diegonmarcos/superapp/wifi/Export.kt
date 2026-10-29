package com.diegonmarcos.superapp.wifi

import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scan log export (#1263): CSV, JSON and a PCAP of synthesized beacons. Android gives an app no monitor mode, so the PCAP is
 * honest about what it is: one 802.11 beacon per observed AP (radiotap: channel frequency + antenna dBm; body: SSID, DS channel,
 * RSN/WPA flags via the privacy bit) built from the scan, timestamped when the AP was last seen — it opens in Wireshark and its
 * Wi-Fi tools (channel / signal columns) work on it. Pure: JVM-tested.
 */
object Export {
    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    val CSV_HEADER = listOf("ssid", "bssid", "vendor", "rssi", "rssi_smoothed", "band", "channel", "freq_mhz", "center_mhz", "width_mhz",
        "standard", "security", "pmf", "wps", "hidden", "rtt_mc", "rtt_az", "mld_mac", "mlo_links", "first_seen_ms", "last_seen_ms", "capabilities")

    fun csv(aps: List<AccessPoint>): String = buildString {
        append(CSV_HEADER.joinToString(",")).append('\n')
        for (a in aps) append(listOf(a.ssid, a.bssid, a.vendor, a.rssi.toString(), "%.1f".format(java.util.Locale.ROOT, a.smoothed), a.band.label,
            a.channel.toString(), a.freqMhz.toString(), a.centerMhz.toString(), a.widthMhz.toString(), "${a.std.label} ${a.std.gen}".trim(),
            a.security.label, a.security.pmf.name, a.security.wps.toString(), a.hidden.toString(), a.rtt80211mc.toString(), a.rtt80211az.toString(),
            a.mldMac.orEmpty(), a.mloLinks.joinToString(" ") { "${it.linkId}:${it.bssid}@${it.band.id}/${it.channel}" },
            a.firstSeenMs.toString(), a.lastSeenMs.toString(), a.capabilities).joinToString(",") { csvCell(it) }).append('\n')
    }

    fun json(aps: List<AccessPoint>, link: LinkSnapshot?, roams: List<RoamEvent>, exportedAt: Long): String = JSONObject().apply {
        put("exported_at_ms", exportedAt)
        put("access_points", JSONArray().apply {
            aps.forEach { a ->
                put(JSONObject().apply {
                    put("ssid", a.ssid); put("bssid", a.bssid); put("vendor", a.vendor); put("rssi", a.rssi); put("rssi_smoothed", a.smoothed)
                    put("band", a.band.id); put("channel", a.channel); put("freq_mhz", a.freqMhz); put("center_mhz", a.centerMhz); put("width_mhz", a.widthMhz)
                    put("standard", a.std.label); put("generation", a.std.gen); put("security", JSONArray(a.security.kinds)); put("pmf", a.security.pmf.name)
                    put("wps", a.security.wps); put("hidden", a.hidden); put("rtt_80211mc", a.rtt80211mc); put("rtt_80211az", a.rtt80211az)
                    put("mld_mac", a.mldMac ?: JSONObject.NULL); put("capabilities", a.capabilities)
                    put("history", JSONArray().apply { a.history.forEach { h -> put(JSONArray().put(h.t).put(h.rssi)) } })
                })
            }
        })
        link?.let { l -> put("connected", JSONObject().apply { put("ssid", l.ssid); put("bssid", l.bssid); put("rssi", l.rssi); put("freq_mhz", l.freqMhz); put("ipv4", l.ipv4); put("gateway", l.gateway) }) }
        put("roaming", JSONArray().apply { roams.forEach { r -> put(JSONObject().apply { put("t", r.t); put("kind", r.kind); put("ssid", r.ssid); put("from", r.fromBssid); put("to", r.toBssid); put("from_rssi", r.fromRssi); put("to_rssi", r.toRssi) }) } })
    }.toString(1)

    // ---- PCAP ----

    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 255); write(v ushr 8 and 255) }
    private fun ByteArrayOutputStream.le32(v: Long) { for (i in 0 until 4) write((v ushr (8 * i) and 255).toInt()) }

    fun macBytes(mac: String): ByteArray {
        val hex = mac.filter { it.isLetterOrDigit() }.padEnd(12, '0').take(12)
        return ByteArray(6) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** One radiotap + beacon frame for [a]. */
    fun beacon(a: AccessPoint): ByteArray {
        val f = ByteArrayOutputStream()
        // radiotap: version 0, pad, length 13, present = channel (bit 3) | dbm antenna signal (bit 5)
        f.write(0); f.write(0); f.le16(13); f.le32((1L shl 3) or (1L shl 5))
        f.le16(a.freqMhz)
        f.le16(if (a.band == Band.B24) 0x00A0 else 0x0140) // 2 GHz + CCK|OFDM, or 5 GHz + OFDM
        f.write(a.rssi and 255)
        // 802.11 beacon header
        f.write(0x80); f.write(0x00); f.le16(0)
        repeat(6) { f.write(0xff) }
        val mac = macBytes(a.bssid); f.write(mac); f.write(mac)
        f.le16(0)
        // fixed: timestamp (8), beacon interval 100 TU, capability ESS (+ privacy when protected)
        repeat(8) { f.write(0) }
        f.le16(100)
        f.le16(0x0001 or (if (a.security.open) 0 else 0x0010))
        // IE 0 SSID
        val ssid = (if (a.hidden) "" else a.ssid).toByteArray(Charsets.UTF_8).take(32).toByteArray()
        f.write(0); f.write(ssid.size); f.write(ssid)
        // IE 3 DS parameter set (channel)
        f.write(3); f.write(1); f.write(a.channel.coerceIn(0, 255))
        // IE 48 RSN when WPA2 / WPA3 / OWE: version 1, CCMP group, one CCMP pairwise, one AKM (PSK 2, SAE 8, EAP 1, OWE 18)
        if (a.security.level >= 2 && a.security.kinds.none { it == "WAPI" }) {
            val akm = when { a.security.kinds.any { it.startsWith("OWE") } -> 18; a.security.enterprise -> 1; "WPA3" in a.security.kinds && "WPA2" !in a.security.kinds -> 8; else -> 2 }
            val rsn = ByteArrayOutputStream().apply {
                le16(1); write(byteArrayOf(0x00, 0x0f, 0xac.toByte(), 4)); le16(1); write(byteArrayOf(0x00, 0x0f, 0xac.toByte(), 4))
                le16(1); write(byteArrayOf(0x00, 0x0f, 0xac.toByte(), akm.toByte()))
                le16((if (a.security.pmf != Pmf.None) 0x80 else 0) or (if (a.security.pmf == Pmf.Required) 0x40 else 0))
            }.toByteArray()
            f.write(48); f.write(rsn.size); f.write(rsn)
        }
        return f.toByteArray()
    }

    /** A pcap (LINKTYPE_IEEE802_11_RADIOTAP = 127) holding one synthesized beacon per AP. */
    fun pcap(aps: List<AccessPoint>): ByteArray {
        val o = ByteArrayOutputStream()
        o.le32(0xa1b2c3d4L); o.le16(2); o.le16(4); o.le32(0); o.le32(0); o.le32(65535); o.le32(127)
        for (a in aps.sortedBy { it.lastSeenMs }) {
            val pkt = beacon(a)
            o.le32(a.lastSeenMs / 1000); o.le32((a.lastSeenMs % 1000) * 1000); o.le32(pkt.size.toLong()); o.le32(pkt.size.toLong())
            o.write(pkt)
        }
        return o.toByteArray()
    }
}
