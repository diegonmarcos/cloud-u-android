package com.diegonmarcos.superapp.wifi

/** The radio band of a frequency (#1263). */
enum class Band(val id: String, val label: String) {
    B24("2.4", "2.4 GHz"), B5("5", "5 GHz"), B6("6", "6 GHz"), B60("60", "60 GHz"), Unknown("?", "?");

    companion object { fun of(id: String?): Band = entries.firstOrNull { it.id == id } ?: Unknown }
}

/** The 802.11 generation an AP advertises (ScanResult.getWifiStandard, API 30+), else inferred from width / band. */
enum class WifiStd(val label: String, val gen: String) {
    Legacy("802.11a/b/g", "Legacy"), N("802.11n", "Wi-Fi 4"), AC("802.11ac", "Wi-Fi 5"), AX("802.11ax", "Wi-Fi 6"),
    AX6E("802.11ax", "Wi-Fi 6E"), BE("802.11be", "Wi-Fi 7"), AD("802.11ad", "WiGig"), Unknown("?", "");

    companion object {
        /** ScanResult.WIFI_STANDARD_* codes: 1 legacy, 4 11n, 5 11ac, 6 11ax, 7 11ad, 8 11be. */
        fun fromCode(code: Int, band: Band, widthMhz: Int): WifiStd = when (code) {
            1 -> Legacy; 4 -> N; 5 -> AC; 6 -> if (band == Band.B6) AX6E else AX; 7 -> AD; 8 -> BE
            else -> infer(band, widthMhz)
        }

        /** Below API 30 nothing says the standard: 6 GHz implies ax, 320 MHz be, 80 / 160 MHz at least ac, 40 MHz at least n. */
        fun infer(band: Band, widthMhz: Int): WifiStd = when {
            widthMhz >= 320 -> BE
            band == Band.B6 -> AX6E
            band == Band.B60 -> AD
            widthMhz >= 80 -> AC
            widthMhz >= 40 -> N
            else -> Unknown
        }
    }
}

enum class Pmf(val label: String) { None("no PMF"), Capable("PMF capable"), Required("PMF required") }

/** What the capabilities string says about protection (#1263, [SecurityParser]). [level]: 0 open .. 5 WPA3-Enterprise 192. */
data class Security(
    val kinds: List<String>,
    val pmf: Pmf,
    val wps: Boolean,
    val enterprise: Boolean,
    val ciphers: List<String>,
    val ft: Boolean,
    val level: Int,
) {
    val label: String get() = kinds.joinToString("/").ifEmpty { "Open" }
    val open: Boolean get() = level == 0
    /** WEP, WPA (TKIP), TKIP-only or open-with-WPS: a weakness worth a warning pill. */
    val weak: Boolean get() = kinds.any { it == "WEP" || it == "WPA" } || (ciphers.isNotEmpty() && ciphers.all { it == "TKIP" })
}

/** One link of a Wi-Fi 7 multi-link AP (ScanResult.getAffiliatedMloLinks, API 33+). */
data class MloLink(val linkId: Int, val bssid: String, val band: Band, val channel: Int)

/** What the Android side hands over per scan result, before tracking: no Android types, so the rules are JVM-testable. */
data class RawScan(
    val bssid: String,
    val ssid: String,
    val freqMhz: Int,
    val centerFreq0: Int = 0,
    val centerFreq1: Int = 0,
    val widthCode: Int = 0,
    val rssi: Int,
    val capabilities: String = "",
    val ageMs: Long = 0,
    val stdCode: Int = 0,
    val rtt80211mc: Boolean = false,
    val rtt80211az: Boolean = false,
    val passpoint: Boolean = false,
    val mldMac: String? = null,
    val mloLinkId: Int = -1,
    val mloLinks: List<MloLink> = emptyList(),
    val timestampUs: Long = 0,
)

data class RssiSample(val t: Long, val rssi: Int)

/** A tracked access point: the latest scan of a BSSID, its smoothed RSSI and its history. */
data class AccessPoint(
    val bssid: String,
    val ssid: String,
    val hidden: Boolean,
    val freqMhz: Int,
    val centerMhz: Int,
    val centerFreq0: Int,
    val centerFreq1: Int,
    val widthMhz: Int,
    val band: Band,
    val channel: Int,
    val std: WifiStd,
    val security: Security,
    val rssi: Int,
    val smoothed: Double,
    val vendor: String,
    val randomizedBssid: Boolean,
    val rtt80211mc: Boolean,
    val rtt80211az: Boolean,
    val passpoint: Boolean,
    val mldMac: String?,
    val mloLinkId: Int,
    val mloLinks: List<MloLink>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val history: List<RssiSample>,
    val capabilities: String,
) {
    val displaySsid: String get() = if (hidden) "(hidden)" else ssid
    fun ageMs(now: Long): Long = (now - lastSeenMs).coerceAtLeast(0)
    /** The occupied frequency ranges (one, or two for 80+80). */
    val spans: List<IntRange> get() = ChannelMath.spansMhz(freqMhz, centerFreq0, centerFreq1, widthMhz, band)
}
