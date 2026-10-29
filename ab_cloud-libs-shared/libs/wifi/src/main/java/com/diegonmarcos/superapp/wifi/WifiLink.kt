package com.diegonmarcos.superapp.wifi

/** The connected network as the system reports it (#1263): WifiInfo + LinkProperties + NetworkCapabilities + DhcpInfo. */
data class LinkSnapshot(
    val ssid: String = "",
    val bssid: String = "",
    val rssi: Int = -127,
    val freqMhz: Int = 0,
    val linkMbps: Int = -1,
    val txMbps: Int = -1,
    val rxMbps: Int = -1,
    val maxTxMbps: Int = -1,
    val maxRxMbps: Int = -1,
    val stdCode: Int = 0,
    val securityType: String = "",
    val hiddenSsid: Boolean = false,
    val iface: String = "",
    val ipv4: String = "",
    val prefix: Int = 0,
    val gateway: String = "",
    val dns: List<String> = emptyList(),
    val dhcpServer: String = "",
    val leaseSeconds: Int = -1,
    val mtu: Int = 0,
    val ipv6: List<String> = emptyList(),
    val domains: String = "",
    val proxy: String = "",
    val captivePortal: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = true,
    val privateDnsActive: Boolean = false,
    val privateDnsServer: String = "",
    val downKbps: Int = 0,
    val upKbps: Int = 0,
    val macRandomization: String = "",
    val apMldMac: String = "",
    val mloLinks: List<MloLink> = emptyList(),
    val at: Long = 0,
) {
    val connected: Boolean get() = bssid.isNotEmpty() && bssid != "02:00:00:00:00:00"
    val band: Band get() = ChannelMath.band(freqMhz)
    val channel: Int get() = ChannelMath.channel(freqMhz)
    val std: WifiStd get() = WifiStd.fromCode(stdCode, band, 20)
}
