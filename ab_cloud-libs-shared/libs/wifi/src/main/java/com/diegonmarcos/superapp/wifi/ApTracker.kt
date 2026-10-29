package com.diegonmarcos.superapp.wifi

/**
 * Turns scan batches into tracked [AccessPoint]s (#1263): an exponential moving average of the RSSI ([alpha]), a bounded history
 * ([historyMax] samples) that only grows when a result is fresh (its timestamp moved), and pruning of BSSIDs unseen for [dropMs].
 * Pure: JVM-tested.
 */
class ApTracker(
    private val alpha: Double = 0.35,
    private val historyMax: Int = 90,
    private val dropMs: Long = 600_000,
    private val vendorOf: (String) -> String = { "" },
) {
    private val aps = LinkedHashMap<String, AccessPoint>()
    private val stamps = HashMap<String, Long>()

    fun all(): List<AccessPoint> = aps.values.toList()
    fun get(bssid: String): AccessPoint? = aps[bssid.lowercase()]
    fun clear() { aps.clear(); stamps.clear() }

    fun ingest(batch: List<RawScan>, nowMs: Long): List<AccessPoint> {
        for (r in batch) {
            val key = r.bssid.lowercase()
            if (key.isBlank()) continue
            val seen = nowMs - r.ageMs.coerceAtLeast(0)
            val prev = aps[key]
            val fresh = prev == null || stamps[key] != r.timestampUs || r.timestampUs == 0L
            val band = ChannelMath.band(r.freqMhz)
            val width = ChannelMath.widthMhz(r.widthCode)
            val smoothed = if (prev == null) r.rssi.toDouble() else if (fresh) alpha * r.rssi + (1 - alpha) * prev.smoothed else prev.smoothed
            val hist = if (!fresh && prev != null) prev.history else ((prev?.history ?: emptyList()) + RssiSample(seen, r.rssi)).takeLast(historyMax)
            val ssid = r.ssid.trim('"')
            aps[key] = AccessPoint(
                bssid = key, ssid = ssid, hidden = ssid.isEmpty() || ssid.all { it == '\u0000' },
                freqMhz = r.freqMhz, centerMhz = ChannelMath.centre(r.freqMhz, r.centerFreq0, r.centerFreq1, r.widthCode, band),
                centerFreq0 = r.centerFreq0, centerFreq1 = r.centerFreq1, widthMhz = width, band = band,
                channel = ChannelMath.channel(r.freqMhz), std = WifiStd.fromCode(r.stdCode, band, width),
                security = SecurityParser.parse(r.capabilities), rssi = r.rssi, smoothed = smoothed,
                vendor = prev?.vendor ?: vendorOf(key), randomizedBssid = OuiTable.isLocallyAdministered(key),
                rtt80211mc = r.rtt80211mc, rtt80211az = r.rtt80211az, passpoint = r.passpoint,
                mldMac = r.mldMac?.lowercase(), mloLinkId = r.mloLinkId, mloLinks = r.mloLinks,
                firstSeenMs = prev?.firstSeenMs ?: seen, lastSeenMs = maxOf(seen, prev?.lastSeenMs ?: 0), history = hist,
                capabilities = r.capabilities,
            )
            stamps[key] = r.timestampUs
        }
        aps.entries.removeAll { nowMs - it.value.lastSeenMs > dropMs }
        return all()
    }
}

enum class ApSort(val label: String) { Signal("Signal"), Ssid("SSID"), Channel("Channel"), Band("Band"), Security("Security"), Age("Last seen") }

/** The AP list's filter: text (SSID / BSSID / vendor), bands, minimum signal, hide stale / hidden, security, and sort. */
data class ApQuery(
    val text: String = "",
    val bands: Set<Band> = setOf(Band.B24, Band.B5, Band.B6, Band.B60),
    val minRssi: Int = -100,
    val hideHidden: Boolean = false,
    val hideStaleMs: Long = 0,
    val openOnly: Boolean = false,
    val rttOnly: Boolean = false,
    val sort: ApSort = ApSort.Signal,
    val groupBySsid: Boolean = false,
)

object ApFilter {
    fun apply(aps: List<AccessPoint>, q: ApQuery, now: Long): List<AccessPoint> {
        val t = q.text.trim().lowercase()
        val shown = aps.filter { a ->
            a.band in q.bands && a.smoothed >= q.minRssi &&
                (!q.hideHidden || !a.hidden) && (q.hideStaleMs <= 0 || a.ageMs(now) <= q.hideStaleMs) &&
                (!q.openOnly || a.security.open) && (!q.rttOnly || a.rtt80211mc || a.rtt80211az) &&
                (t.isEmpty() || a.ssid.lowercase().contains(t) || a.bssid.contains(t) || a.vendor.lowercase().contains(t))
        }
        val cmp: Comparator<AccessPoint> = when (q.sort) {
            ApSort.Signal -> compareByDescending { it.smoothed }
            ApSort.Ssid -> compareBy<AccessPoint> { it.hidden }.thenBy { it.ssid.lowercase() }.thenByDescending { it.smoothed }
            ApSort.Channel -> compareBy<AccessPoint> { it.freqMhz }.thenByDescending { it.smoothed }
            ApSort.Band -> compareBy<AccessPoint> { it.band.ordinal }.thenByDescending { it.smoothed }
            ApSort.Security -> compareBy<AccessPoint> { it.security.level }.thenByDescending { it.smoothed }
            ApSort.Age -> compareBy<AccessPoint> { it.ageMs(now) }.thenByDescending { it.smoothed }
        }
        return shown.sortedWith(cmp)
    }

    /** SSID groups in the order of their best member (hidden networks each stay their own group). */
    fun groups(sorted: List<AccessPoint>): List<Pair<String, List<AccessPoint>>> =
        sorted.groupBy { if (it.hidden) "\u0000" + it.bssid else it.ssid }.map { (k, v) -> (if (k.startsWith("\u0000")) "(hidden)" else k) to v }
}

/** Signal words and a 0..100 quality from RSSI, against the declared thresholds. */
data class RssiThresholds(val excellent: Int = -55, val good: Int = -67, val fair: Int = -75, val poor: Int = -85, val noiseFloor: Int = -95) {
    fun grade(rssi: Int): Int = when { rssi >= excellent -> 4; rssi >= good -> 3; rssi >= fair -> 2; rssi >= poor -> 1; else -> 0 }
    fun quality(rssi: Int): Int = (2 * (rssi + 100)).coerceIn(0, 100)
    /** An SNR estimate: Android exposes no noise reading, so the declared noise floor stands in for it. */
    fun snr(rssi: Int): Int = rssi - noiseFloor
}
