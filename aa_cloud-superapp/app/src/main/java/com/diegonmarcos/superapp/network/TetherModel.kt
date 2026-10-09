package com.diegonmarcos.superapp.network

/**
 * The strip's "HS" icon and the popup's Hotspot section as pure rules. Inputs are what the app can
 * read without privilege: the sticky TETHER_STATE_CHANGED broadcast's tethered interface names
 * ("tetherArray"), the sticky WIFI_AP_STATE_CHANGED state, and the USB functions (rndis/ncm =
 * USB tethering switched on). The SSID, band and client count are NOT readable by a normal app
 * (SoftApConfiguration and the tethering client callbacks are system APIs), so they come from
 * the privileged shell channel when it is up: [parseSoftAp] reads `dumpsys wifi` lines and
 * [countClients] reads `ip neigh`.
 */
object TetherModel {

    enum class Kind(val label: String) { WIFI("Wi-Fi hotspot"), USB("USB tethering"), BLUETOOTH("Bluetooth tethering"), ETHERNET("Ethernet tethering"), OTHER("Other") }

    /** A tethered interface's kind by its kernel name (names are per-device, these prefixes are the AOSP + vendor ones). */
    fun kindOf(iface: String): Kind {
        val n = iface.lowercase()
        return when {
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> Kind.USB
            n.startsWith("bt-pan") || n.startsWith("bnep") || n.startsWith("bt") -> Kind.BLUETOOTH
            n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") || n.startsWith("wigig") -> Kind.WIFI
            n.startsWith("eth") -> Kind.ETHERNET
            else -> Kind.OTHER
        }
    }

    /** WifiManager.WIFI_AP_STATE_ENABLING / _ENABLED (hidden constants, stable since API 14). */
    private const val AP_ENABLING = 12
    private const val AP_ENABLED = 13

    data class State(
        val tethered: List<String>,
        /** WIFI_AP_STATE_CHANGED "wifi_state"; null = never received. */
        val apState: Int?,
        /** USB_STATE rndis or ncm: the USB tethering function is switched on. */
        val usbFunction: Boolean,
    ) {
        private val kinds get() = tethered.map(::kindOf).toSet()
        val wifi: Boolean get() = apState == AP_ENABLED || apState == AP_ENABLING || Kind.WIFI in kinds
        val usb: Boolean get() = usbFunction || Kind.USB in kinds
        /** Only visible once a client is attached: with BT tethering switched on and nobody on it, Android lists no interface. */
        val bluetooth: Boolean get() = Kind.BLUETOOTH in kinds
        val ethernet: Boolean get() = Kind.ETHERNET in kinds
        val active: Boolean get() = wifi || usb || bluetooth || ethernet || tethered.isNotEmpty()

        /** "Wi-Fi hotspot, USB tethering", or "off". */
        fun summary(): String = listOfNotNull(
            Kind.WIFI.label.takeIf { wifi }, Kind.USB.label.takeIf { usb },
            Kind.BLUETOOTH.label.takeIf { bluetooth }, Kind.ETHERNET.label.takeIf { ethernet },
        ).ifEmpty { if (tethered.isNotEmpty()) listOf("tethering") else emptyList() }.joinToString(", ").ifEmpty { "off" }
    }

    data class SoftAp(val ssid: String?, val band: String?, val clients: Int?)

    // ".SSID" / ".band" right after a dot, so "mApConfig.hiddenSSID" never reads as the name.
    private val SSID = Regex("""mApConfig(?:\.\w+)*?\.(?:SSID|WifiSsid|wifiSsid|ssid)\s*[:=]\s*(.+)""")
    private val SSID_TOSTRING = Regex("""\bssid\s*=\s*"?([^",\n]+)"?""", RegexOption.IGNORE_CASE)
    private val BAND = Regex("""mApConfig(?:\.\w+)*?\.(?:apBand|band|Band)\s*[:=]\s*(-?\d+)""")
    private val FREQ = Regex("""SoftApInfo.*?frequency\s*[:=]\s*(\d{4,5})""", RegexOption.IGNORE_CASE)
    private val CLIENTS = Regex("""(?:mConnectedClient\w*\.size\(\)|mNumAssociatedStations|connected clients?)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * `dumpsys wifi` (pre-filtered on the device to the SoftAp lines) -> what the hotspot is.
     * Line shapes differ per release (SoftApManager.dump); every pattern is optional and a
     * missing value is null, never a guess. [sdk] decides how a raw band number reads: before
     * API 30 it was 0 = 2.4 GHz / 1 = 5 GHz / -1 = any, from API 30 a bit mask.
     */
    fun parseSoftAp(dump: String, sdk: Int): SoftAp {
        val lines = dump.lines()
        val ssid = lines.firstNotNullOfOrNull { SSID.find(it)?.groupValues?.get(1) }
            ?: lines.filter { it.contains("SoftApConfiguration") || it.contains("mApConfig") }
                .firstNotNullOfOrNull { SSID_TOSTRING.find(it)?.groupValues?.get(1) }
        val freq = lines.firstNotNullOfOrNull { FREQ.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }
        val band = freq?.let(::bandOfFreq)
            ?: lines.firstNotNullOfOrNull { BAND.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.let { bandLabel(it, sdk) }
        val clients = lines.firstNotNullOfOrNull { CLIENTS.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return SoftAp(ssid?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" && it != "<unknown ssid>" }, band, clients)
    }

    fun bandOfFreq(mhz: Int): String? = when (mhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 57000..71000 -> "60 GHz"
        else -> null
    }

    fun bandLabel(raw: Int, sdk: Int): String? = if (sdk < 30) when (raw) {
        0 -> "2.4 GHz"; 1 -> "5 GHz"; -1 -> "any band"; else -> null
    } else {
        val parts = listOfNotNull("2.4".takeIf { raw and 1 != 0 }, "5".takeIf { raw and 2 != 0 },
            "6".takeIf { raw and 4 != 0 }, "60".takeIf { raw and 8 != 0 })
        if (parts.isEmpty()) null else parts.joinToString(" + ") + " GHz"
    }

    /**
     * `ip neigh show` -> distinct client MACs on the [ifaces] (the tethered interfaces) whose
     * entry is alive. FAILED / INCOMPLETE entries are addresses that never answered, and an IPv4
     * and an IPv6 entry of one phone share its MAC, so MACs are counted, not lines.
     */
    fun countClients(ipNeigh: String, ifaces: Collection<String>): Int {
        if (ifaces.isEmpty()) return 0
        val want = ifaces.toSet()
        return ipNeigh.lines().mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            val dev = f.indexOf("dev").takeIf { it >= 0 }?.let { f.getOrNull(it + 1) } ?: return@mapNotNull null
            val mac = f.indexOf("lladdr").takeIf { it >= 0 }?.let { f.getOrNull(it + 1) } ?: return@mapNotNull null
            val state = f.lastOrNull().orEmpty()
            if (dev !in want || state == "FAILED" || state == "INCOMPLETE") null else mac.lowercase()
        }.toSet().size
    }
}
