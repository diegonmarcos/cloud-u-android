package com.diegonmarcos.superapp.network

/**
 * What the mobile radio can do right now: the status strip's mobile icon (label, tint, dots) and
 * the network popup's Cellular section, as one pure rule so every state is tested without a radio.
 *
 *   DATA        in service, mobile data on: the label is the network type ("5G", "4G", "3G"...),
 *               white, dots = the data SIM's signal level.
 *   CALL        in service, mobile data off (the user's switch, data disabled for the data SIM by
 *               the carrier / policy / thermal, data roaming off while roaming, or no SIM chosen
 *               for data): "Call" = calls and SMS only. White, and the dots STILL show the signal.
 *   SOS         emergency calls only: "SOS", half-grey, dots follow the signal.
 *   NO_SERVICE  out of service / radio off: grey, 0 dots.
 *   AIRPLANE    airplane mode: grey, 0 dots.
 *   NO_SIM      no SIM in any slot: grey, 0 dots.
 *
 * Dual SIM: the default DATA SIM drives the icon; the popup lists every SIM ([simLines]).
 * The constants mirror the platform's (ServiceState.STATE_*, TelephonyManager.NETWORK_TYPE_*,
 * TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*) so this file needs no android import.
 */
object MobileLink {

    enum class Kind { DATA, CALL, SOS, NO_SERVICE, AIRPLANE, NO_SIM }

    // ServiceState.getState()
    const val IN_SERVICE = 0
    const val OUT_OF_SERVICE = 1
    const val EMERGENCY_ONLY = 2
    const val POWER_OFF = 3

    /** SubscriptionManager.INVALID_SUBSCRIPTION_ID. */
    const val NO_SUB = -1

    /** The strip's grey label for every state with no usable radio; the popup says why. */
    const val OFF_LABEL = "Cell"
    const val CALL_LABEL = "Call"
    const val SOS_LABEL = "SOS"

    /** Emergency-only reads as half-lit: brighter than off (the radio IS there), never white. */
    const val TINT_SOS: Int = 0x99FFFFFF.toInt()

    /** Why the platform has mobile data disabled for a SIM, beyond the user's own switch. */
    enum class Block(val text: String) { CARRIER("by the carrier"), POLICY("by a data-usage policy"), THERMAL("to cool the phone") }

    data class Sim(
        val subId: Int,
        /** 0-based SIM slot, -1 when unknown. */
        val slot: Int = -1,
        val carrier: String? = null,
        /** ServiceState.getState(); null = not reported yet (or not readable). */
        val serviceState: Int? = null,
        val emergencyOnly: Boolean = false,
        val roaming: Boolean = false,
        /** The user's Mobile data switch for this SIM; null = unknown. */
        val dataUser: Boolean? = null,
        /** Data disabled by the platform for another reason (API 31+ isDataEnabledForReason). */
        val dataBlock: Block? = null,
        /** The Data roaming switch; null = unknown. */
        val dataRoaming: Boolean? = null,
        /** TelephonyManager.NETWORK_TYPE_* of the data (or voice) network, 0 = unknown. */
        val networkType: Int = 0,
        /** TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*, 0 = none. */
        val overrideType: Int = 0,
        /** 0..4, SignalLevels.NONE when not known. */
        val level: Int = SignalLevels.NONE,
        val dbm: Int? = null,
    )

    data class Inputs(
        val airplane: Boolean,
        /** SIMs present (active subscriptions, or what could be learnt of them). */
        val sims: List<Sim>,
        val defaultDataSubId: Int,
        /** Android has a cellular network up: only consulted when the data switch is unreadable. */
        val cellularTransport: Boolean = false,
    )

    data class Reading(
        val kind: Kind,
        /** The strip's label. */
        val label: String,
        val tint: Int,
        /** The dots: 0..4, or SignalLevels.NONE (no row) while in service with no level yet. */
        val level: Int,
        /** The popup's one-line explanation. */
        val reason: String,
        /** The SIM the icon follows, null when there is none. */
        val sim: Sim?,
    )

    /**
     * The SIM the icon follows: the default data SIM; failing that (no data SIM chosen, or it
     * vanished) the first one in service, else the first one.
     */
    fun dataSim(sims: List<Sim>, defaultDataSubId: Int): Sim? =
        sims.firstOrNull { it.subId == defaultDataSubId && defaultDataSubId != NO_SUB }
            ?: sims.firstOrNull { it.serviceState == IN_SERVICE }
            ?: sims.firstOrNull()

    /** Null = mobile data is usable on [s]; otherwise why it is not. */
    fun dataOffReason(s: Sim, isDataSim: Boolean, cellularTransport: Boolean): String? = when {
        !isDataSim -> "No SIM is chosen for mobile data"
        s.dataUser == false -> "Mobile data is off"
        s.dataBlock != null -> "Mobile data is disabled ${s.dataBlock.text}"
        s.roaming && s.dataRoaming == false -> "Roaming, and data roaming is off"
        s.dataUser == null && !cellularTransport -> "Mobile data is not connected"
        else -> null
    }

    fun derive(i: Inputs): Reading {
        if (i.airplane) return Reading(Kind.AIRPLANE, OFF_LABEL, WgLink.TINT_OFF, 0, "Airplane mode", null)
        val sim = dataSim(i.sims, i.defaultDataSubId)
            ?: return Reading(Kind.NO_SIM, OFF_LABEL, WgLink.TINT_OFF, 0, "No SIM", null)
        val dots = sim.level.coerceAtMost(SignalLevels.MAX)
        if (sim.emergencyOnly || sim.serviceState == EMERGENCY_ONLY)
            return Reading(Kind.SOS, SOS_LABEL, TINT_SOS, if (dots < 0) 0 else dots, "Emergency calls only", sim)
        val inService = when (sim.serviceState) {
            IN_SERVICE -> true
            null -> sim.networkType != 0 || dots > 0   // not reported yet: a network or a signal says it is there
            else -> false
        }
        if (!inService) return Reading(Kind.NO_SERVICE, OFF_LABEL, WgLink.TINT_OFF, 0,
            if (sim.serviceState == POWER_OFF) "Radio off" else "No service", sim)
        val isDataSim = sim.subId == i.defaultDataSubId && i.defaultDataSubId != NO_SUB
        val off = dataOffReason(sim, isDataSim, i.cellularTransport)
        return if (off == null) Reading(Kind.DATA, typeLabel(sim.networkType, sim.overrideType), WgLink.TINT_ON, dots,
            "Mobile data on · ${typeName(sim.networkType, sim.overrideType)}", sim)
        else Reading(Kind.CALL, CALL_LABEL, WgLink.TINT_ON, dots, "$off — calls and SMS only", sim)
    }

    // ── network type ────────────────────────────────────────────────────

    // TelephonyManager.NETWORK_TYPE_*
    const val GPRS = 1; const val EDGE = 2; const val UMTS = 3; const val CDMA = 4; const val EVDO_0 = 5
    const val EVDO_A = 6; const val RTT1X = 7; const val HSDPA = 8; const val HSUPA = 9; const val HSPA = 10
    const val IDEN = 11; const val EVDO_B = 12; const val LTE = 13; const val EHRPD = 14; const val HSPAP = 15
    const val GSM = 16; const val TD_SCDMA = 17; const val IWLAN = 18; const val LTE_CA = 19; const val NR = 20

    // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*
    const val OVR_LTE_CA = 1; const val OVR_LTE_ADVANCED_PRO = 2; const val OVR_NR_NSA = 3
    const val OVR_NR_NSA_MMWAVE = 4; const val OVR_NR_ADVANCED = 5

    /** The strip's short label: what the system status bar would draw for this network. */
    fun typeLabel(type: Int, override: Int = 0): String = when (override) {
        OVR_NR_ADVANCED, OVR_NR_NSA_MMWAVE -> "5G+"
        OVR_NR_NSA -> "5G"
        OVR_LTE_CA, OVR_LTE_ADVANCED_PRO -> "4G+"
        else -> when (type) {
            NR -> "5G"
            LTE_CA -> "4G+"
            LTE -> "4G"
            IWLAN -> "WLAN"
            HSPAP -> "H+"
            HSDPA, HSUPA, HSPA -> "H"
            UMTS, EVDO_0, EVDO_A, EVDO_B, EHRPD, TD_SCDMA -> "3G"
            EDGE -> "E"
            GPRS -> "G"
            GSM, CDMA, RTT1X, IDEN -> "2G"
            else -> OFF_LABEL
        }
    }

    /** The popup's longer name, which tells 5G NSA (an LTE anchor) from 5G SA. */
    fun typeName(type: Int, override: Int = 0): String = when (override) {
        OVR_NR_ADVANCED -> "5G+ (NR advanced, LTE anchor)"
        OVR_NR_NSA_MMWAVE -> "5G+ (NR mmWave, LTE anchor)"
        OVR_NR_NSA -> "5G NSA (NR on an LTE anchor)"
        OVR_LTE_ADVANCED_PRO -> "LTE-A Pro"
        OVR_LTE_CA -> "LTE-A (carrier aggregation)"
        else -> when (type) {
            NR -> "5G SA (NR)"
            LTE_CA -> "LTE-A (carrier aggregation)"
            LTE -> "LTE"
            IWLAN -> "IWLAN (over Wi-Fi)"
            HSPAP -> "HSPA+"
            HSDPA -> "HSDPA"; HSUPA -> "HSUPA"; HSPA -> "HSPA"
            UMTS -> "UMTS"; TD_SCDMA -> "TD-SCDMA"
            EVDO_0 -> "EVDO rev 0"; EVDO_A -> "EVDO rev A"; EVDO_B -> "EVDO rev B"; EHRPD -> "eHRPD"
            EDGE -> "EDGE"; GPRS -> "GPRS"; GSM -> "GSM"; CDMA -> "CDMA"; RTT1X -> "1xRTT"; IDEN -> "iDEN"
            0 -> "—"
            else -> "type $type"
        }
    }

    // ── popup lines ─────────────────────────────────────────────────────

    /** The section's state line, e.g. "State: Call — Mobile data is off — calls and SMS only". */
    fun stateLine(r: Reading): String = "State: " + when (r.kind) {
        Kind.DATA -> "${r.label} — ${r.reason}"
        Kind.CALL -> "Call — ${r.reason}"
        Kind.SOS -> "SOS — ${r.reason}"
        Kind.NO_SERVICE, Kind.AIRPLANE, Kind.NO_SIM -> r.reason
    }

    /** "—", or "— needs ADB Shell" when the value is only readable through the shell channel and it is down. */
    fun missing(needsShell: Boolean): String = if (needsShell) "— needs ADB Shell" else "—"

    fun onOff(v: Boolean?, miss: String = "—"): String = when (v) { true -> "ON"; false -> "OFF"; null -> miss }

    /** An IMS user setting as the SIM database stores it: 1 on, 0 off, -1 = the carrier's default. */
    fun setting(v: Int?, miss: String = "—"): String = when (v) { 1 -> "ON"; 0 -> "OFF"; -1 -> "carrier default"; null -> miss; else -> miss }

    /** One cell's signal: LTE / NR carry RSRP, RSRQ, SINR; 2G / 3G only a dBm. Null = unavailable. */
    data class CellSig(val rat: String, val rsrp: Int? = null, val rsrq: Int? = null, val sinr: Int? = null, val dbm: Int? = null)

    /** The popup-only facts of one SIM; every one may be unreadable (null). */
    data class Detail(
        /** "214-01" from the registered network's MCC+MNC. */
        val mccMnc: String? = null,
        /** TelephonyManager.getVoiceNetworkType(): which network carries calls now. */
        val voiceType: Int? = null,
        /** VoLTE / VoNR / Wi-Fi calling user settings: 1 on, 0 off, -1 carrier default. */
        val volte: Int? = null,
        val vonr: Int? = null,
        val wfc: Int? = null,
        /** Carrier config: does the carrier offer VoLTE / Wi-Fi calling / 5G NSA (1) and SA (2). */
        val volteAvailable: Boolean? = null,
        val wfcAvailable: Boolean? = null,
        val nrAvailability: List<Int>? = null,
        /** The preferred / allowed network types, already as families ("5G/4G/3G/2G (auto)"). */
        val preferredMode: String? = null,
        val signals: List<CellSig> = emptyList(),
        /** A privileged value could only come through the shell channel, and it is not up. */
        val needsShell: Boolean = false,
    )

    /** "21401" (MCC + MNC) -> "214-01"; anything else null. */
    fun mccMnc(op: String?): String? {
        val o = op?.trim() ?: return null
        return if (o.length in 5..6 && o.all { it.isDigit() }) "${o.take(3)}-${o.drop(3)}" else null
    }

    /** Which network carries a call now. */
    fun voiceLine(voiceType: Int?): String = when (voiceType) {
        NR -> "calls over 5G (VoNR)"
        LTE, LTE_CA -> "calls over 4G (VoLTE)"
        IWLAN -> "calls over Wi-Fi"
        null, 0 -> "calls over —"
        else -> "calls over ${typeLabel(voiceType)} (circuit-switched)"
    }

    /** 5G now: SA when the data network is NR itself, NSA when NR rides an LTE anchor. */
    fun nrNow(type: Int, override: Int): String = when {
        type == NR -> "SA"
        override == OVR_NR_NSA || override == OVR_NR_ADVANCED || override == OVR_NR_NSA_MMWAVE -> "NSA"
        else -> "not on 5G"
    }

    /** CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA (1) / _SA (2). */
    fun nrOffered(av: List<Int>?): String? = av?.let {
        val names = listOfNotNull("NSA".takeIf { _ -> 1 in it }, "SA".takeIf { _ -> 2 in it })
        if (names.isEmpty()) "carrier offers no 5G" else "carrier offers " + names.joinToString("+")
    }

    /** "4G RSRP -98 dBm · RSRQ -11 dB · SINR 12 dB", "2G -85 dBm". */
    fun signalLine(c: CellSig): String {
        fun v(x: Int?, u: String) = x?.let { "$it $u" } ?: "—"
        return if (c.rsrp != null || c.rsrq != null || c.sinr != null)
            "${c.rat} RSRP ${v(c.rsrp, "dBm")} · RSRQ ${v(c.rsrq, "dB")} · SINR ${v(c.sinr, "dB")}"
        else "${c.rat} ${v(c.dbm, "dBm")}"
    }

    // TelephonyManager.NETWORK_TYPE_BITMASK_* = 1 << (NETWORK_TYPE_* - 1)
    private fun bit(t: Int): Long = 1L shl (t - 1)
    private val FAMILY_5G = listOf(NR)
    private val FAMILY_4G = listOf(LTE, LTE_CA, IWLAN)
    private val FAMILY_3G = listOf(UMTS, HSDPA, HSUPA, HSPA, HSPAP, EVDO_0, EVDO_A, EVDO_B, EHRPD, TD_SCDMA)
    private val FAMILY_2G = listOf(GPRS, EDGE, GSM, CDMA, RTT1X, IDEN)

    /** Families allowed by a TelephonyManager network-type bitmask (getAllowedNetworkTypesForReason). */
    fun familiesOfBitmask(mask: Long): List<String> = listOfNotNull(
        "5G".takeIf { FAMILY_5G.any { mask and bit(it) != 0L } },
        "4G".takeIf { listOf(LTE, LTE_CA).any { mask and bit(it) != 0L } },
        "3G".takeIf { FAMILY_3G.any { mask and bit(it) != 0L } },
        "2G".takeIf { FAMILY_2G.any { mask and bit(it) != 0L } },
    )

    /** Families of a RILConstants preferred network mode (Settings.Global preferred_network_mode<subId>). */
    fun familiesOfRilMode(mode: Int): List<String>? = when (mode) {
        1, 5 -> listOf("2G")
        2, 6, 13, 14 -> listOf("3G")
        0, 3, 4, 7, 16, 18, 21 -> listOf("3G", "2G")
        11 -> listOf("4G")
        12, 15, 19 -> listOf("4G", "3G")
        8, 9, 10, 17, 20, 22 -> listOf("4G", "3G", "2G")
        23 -> listOf("5G")
        24 -> listOf("5G", "4G")
        28, 29, 31 -> listOf("5G", "4G", "3G")
        25, 26, 27, 30, 32, 33 -> listOf("5G", "4G", "3G", "2G")
        else -> null
    }

    /** ["5G","4G","3G","2G"] -> "5G/4G/3G/2G (auto)"; one family -> "4G only". */
    fun familiesLabel(f: List<String>?): String? = when {
        f.isNullOrEmpty() -> null
        f.size == 1 -> "${f[0]} only"
        else -> f.joinToString("/") + " (auto)"
    }

    /**
     * The SIM database rows the shell prints for
     * `content query --uri content://telephony/siminfo --projection _id:volte_vt_enabled:...`:
     * "Row: 0 _id=1, volte_vt_enabled=1, wfc_ims_enabled=0" -> {volte_vt_enabled=1, ...} for [subId].
     */
    fun parseSimInfo(out: String, subId: Int): Map<String, Int?> {
        val row = out.lineSequence().map { it.trim() }.filter { it.startsWith("Row:") }
            .map { line -> line.substringAfter(' ').substringAfter(' ').split(", ").associate {
                it.substringBefore('=').trim() to it.substringAfter('=', "").trim() } }
            .firstOrNull { it["_id"] == subId.toString() } ?: return emptyMap()
        return row.filterKeys { it != "_id" }.mapValues { it.value.toIntOrNull() }
    }

    /**
     * One SIM's lines: who it is, its network and signal, mobile data, data roaming; with [d] the
     * voice, Wi-Fi calling, preferred network, 5G type, the per-cell signal and MCC/MNC too.
     * [phonePermission] false = the facts READ_PHONE_STATE gates read "—" and say so.
     */
    fun simLines(s: Sim, isDataSim: Boolean, phonePermission: Boolean, d: Detail? = null): List<String> {
        val name = if (s.slot >= 0) "SIM ${s.slot + 1}" else "SIM"
        val head = "$name · ${s.carrier?.takeIf { it.isNotBlank() } ?: "—"}" +
            (d?.mccMnc?.let { " ($it)" } ?: "") + (if (isDataSim) " · mobile data SIM" else "")
        val service = when {
            s.emergencyOnly || s.serviceState == EMERGENCY_ONLY -> "emergency calls only"
            s.serviceState == IN_SERVICE -> typeName(s.networkType, s.overrideType)
            s.serviceState == OUT_OF_SERVICE -> "no service"
            s.serviceState == POWER_OFF -> "radio off"
            s.networkType != 0 -> typeName(s.networkType, s.overrideType)
            else -> "—"
        }
        val sig = "Signal ${SignalLevels.label(s.level)}" + (s.dbm?.let { " ($it dBm)" } ?: "")
        val perm = if (phonePermission) "—" else "— (needs Phone permission)"
        val data = "Mobile data: " + when {
            s.dataUser == null -> perm
            !s.dataUser -> "OFF"
            s.dataBlock != null -> "OFF (disabled ${s.dataBlock.text})"
            else -> "ON"
        }
        val roam = "Data roaming: " + onOff(s.dataRoaming, perm) + if (s.roaming) " · roaming now" else " · not roaming"
        val out = mutableListOf(head, "  $service · $sig", "  $data", "  $roam")
        if (d == null) return out
        val miss = missing(d.needsShell)
        out += "  Voice: VoLTE ${setting(d.volte, miss)}" + (if (d.volteAvailable == false) " (carrier: no VoLTE)" else "") +
            " · VoNR ${setting(d.vonr, miss)} · ${voiceLine(d.voiceType)}"
        out += "  Wi-Fi calling: ${setting(d.wfc, miss)}" + (if (d.wfcAvailable == false) " (carrier: not offered)" else "")
        out += "  Preferred network: ${d.preferredMode ?: miss}"
        out += "  5G: ${nrNow(s.networkType, s.overrideType)}" + (nrOffered(d.nrAvailability)?.let { " · $it" } ?: "")
        if (d.signals.isEmpty()) out += "  Cell signal: —" else for (c in d.signals) out += "  " + signalLine(c)
        return out
    }
}
