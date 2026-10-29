package com.diegonmarcos.superapp.wifi

/**
 * ScanResult.capabilities to [Security] (#1263). The string is a run of bracketed blocks written by wpa_supplicant, e.g.
 * `[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS][WPS][MFPC]`, `[RSN-OWE-CCMP][MFPR]`, `[RSN-EAP-SUITE-B-192-GCMP-256]`, `[WEP]`.
 * Pure: JVM-tested.
 */
object SecurityParser {
    private val BLOCK = Regex("\\[([^\\]]*)]")
    private val CIPHER = Regex("CCMP-256|GCMP-256|CCMP|GCMP|TKIP")

    fun parse(caps: String): Security {
        val kinds = LinkedHashSet<String>()
        val ciphers = LinkedHashSet<String>()
        var wps = false; var pmfC = false; var pmfR = false; var enterprise = false; var ft = false
        for (b in BLOCK.findAll(caps).map { it.groupValues[1].uppercase() }) {
            when {
                b == "WEP" -> kinds += "WEP"
                b == "WPS" -> wps = true
                b == "MFPC" -> pmfC = true
                b == "MFPR" -> pmfR = true
                b.startsWith("WAPI") -> kinds += "WAPI"
                b.startsWith("WPA-") || b.startsWith("WPA2-") || b.startsWith("RSN-") || b.startsWith("WPA3-") -> {
                    val proto = b.substringBefore('-')
                    val rest = b.substringAfter('-')
                    val rsn = proto != "WPA"
                    CIPHER.findAll(rest).forEach { ciphers += it.value }
                    if (rest.contains("FT/")) ft = true
                    if (rest.contains("SUITE-B") || rest.contains("SUITE_B")) { kinds += "WPA3-Enterprise 192"; enterprise = true }
                    else if (rest.contains("EAP")) {
                        enterprise = true
                        kinds += if (!rsn) "WPA-Enterprise" else if (proto == "WPA3" || rest.contains("SHA256") && pmfR) "WPA3-Enterprise" else "WPA2-Enterprise"
                    }
                    if (rest.contains("PSK")) kinds += if (rsn) "WPA2" else "WPA"
                    if (rest.contains("SAE")) kinds += "WPA3"
                    if (rest.contains("OWE_TRANSITION")) kinds += "OWE-transition" else if (rest.contains("OWE")) kinds += "OWE"
                    if (rest.contains("DPP")) kinds += "DPP"
                }
            }
        }
        // MFPR without MFPC is not a real combination; required implies capable.
        val pmf = if (pmfR) Pmf.Required else if (pmfC) Pmf.Capable else Pmf.None
        val ordered = ORDER.filter { it in kinds } + kinds.filter { it !in ORDER }
        return Security(ordered, pmf, wps, enterprise, ciphers.toList(), ft, levelOf(ordered))
    }

    private val ORDER = listOf("WEP", "WPA", "WPA-Enterprise", "WPA2", "WPA2-Enterprise", "WPA3", "WPA3-Enterprise", "WPA3-Enterprise 192", "OWE-transition", "OWE", "WAPI", "DPP")

    /** 0 open, 1 WEP / WPA, 2 OWE / WAPI, 3 WPA2, 4 WPA3 / WPA3-Enterprise, 5 WPA3-Enterprise 192. The strongest offered wins. */
    fun levelOf(kinds: Collection<String>): Int = kinds.maxOfOrNull {
        when (it) {
            "WEP", "WPA", "WPA-Enterprise" -> 1
            "OWE", "OWE-transition", "WAPI" -> 2
            "WPA2", "WPA2-Enterprise" -> 3
            "WPA3", "WPA3-Enterprise", "DPP" -> 4
            "WPA3-Enterprise 192" -> 5
            else -> 0
        }
    } ?: 0
}
