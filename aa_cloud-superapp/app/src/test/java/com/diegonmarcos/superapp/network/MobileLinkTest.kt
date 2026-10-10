package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.MobileLink.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mobile icon's states: in service x data on/off x roaming x emergency x airplane x no SIM,
 * and dual SIM (the default data SIM drives the icon). The old icon was a fixed "5G", white with
 * any cellular transport and grey without: it could not say "Call" or "SOS", and lost the dots'
 * meaning with data off.
 */
class MobileLinkTest {

    private val lte = MobileLink.Sim(subId = 1, slot = 0, carrier = "Vodafone", serviceState = MobileLink.IN_SERVICE,
        dataUser = true, dataRoaming = false, networkType = MobileLink.LTE, level = 3, dbm = -95)

    private fun of(vararg sims: MobileLink.Sim, data: Int = 1, airplane: Boolean = false, transport: Boolean = true) =
        MobileLink.derive(MobileLink.Inputs(airplane, sims.toList(), data, transport))

    @Test fun `in service with data on shows the network type in white with the signal`() {
        val r = of(lte)
        assertEquals(Kind.DATA, r.kind); assertEquals("4G", r.label)
        assertEquals(WgLink.TINT_ON, r.tint); assertEquals(3, r.level)
    }

    @Test fun `5G NSA reads 5G from the display info, SA from the network type`() {
        assertEquals("5G", of(lte.copy(overrideType = MobileLink.OVR_NR_NSA)).label)
        assertEquals("5G+", of(lte.copy(overrideType = MobileLink.OVR_NR_ADVANCED)).label)
        assertEquals("5G", of(lte.copy(networkType = MobileLink.NR)).label)
        assertEquals("4G+", of(lte.copy(overrideType = MobileLink.OVR_LTE_CA)).label)
        assertEquals("3G", of(lte.copy(networkType = MobileLink.UMTS)).label)
        assertEquals("H+", of(lte.copy(networkType = MobileLink.HSPAP)).label)
        assertEquals("E", of(lte.copy(networkType = MobileLink.EDGE)).label)
        assertEquals("NSA", MobileLink.nrNow(MobileLink.LTE, MobileLink.OVR_NR_NSA))
        assertEquals("SA", MobileLink.nrNow(MobileLink.NR, 0))
        assertEquals("not on 5G", MobileLink.nrNow(MobileLink.LTE, 0))
    }

    @Test fun `mobile data off reads Call in white and keeps the dots`() {
        val r = of(lte.copy(dataUser = false), transport = false)
        assertEquals(Kind.CALL, r.kind); assertEquals("Call", r.label)
        assertEquals(WgLink.TINT_ON, r.tint); assertEquals(3, r.level)
        assertTrue(r.reason, r.reason.contains("Mobile data is off") && r.reason.contains("calls and SMS only"))
    }

    @Test fun `data disabled for the data SIM by the carrier, policy or thermal is Call too`() {
        for (b in MobileLink.Block.values()) {
            val r = of(lte.copy(dataBlock = b))
            assertEquals(Kind.CALL, r.kind); assertEquals(3, r.level)
            assertTrue(r.reason.contains(b.text))
        }
    }

    @Test fun `roaming with data roaming off is Call, with it on is data`() {
        assertEquals(Kind.CALL, of(lte.copy(roaming = true, dataRoaming = false)).kind)
        assertTrue(of(lte.copy(roaming = true, dataRoaming = false)).reason.contains("data roaming is off"))
        assertEquals(Kind.DATA, of(lte.copy(roaming = true, dataRoaming = true)).kind)
        assertEquals(Kind.DATA, of(lte.copy(roaming = false, dataRoaming = false)).kind)
    }

    @Test fun `emergency only is SOS, half grey, dots following the signal`() {
        val r = of(lte.copy(emergencyOnly = true, serviceState = MobileLink.OUT_OF_SERVICE, level = 1))
        assertEquals(Kind.SOS, r.kind); assertEquals("SOS", r.label)
        assertEquals(MobileLink.TINT_SOS, r.tint); assertEquals(1, r.level)
        assertEquals(Kind.SOS, of(lte.copy(serviceState = MobileLink.EMERGENCY_ONLY)).kind)
    }

    @Test fun `no service, airplane and no SIM are grey with no dots, and say why`() {
        val none = of(lte.copy(serviceState = MobileLink.OUT_OF_SERVICE))
        assertEquals(Kind.NO_SERVICE, none.kind); assertEquals("No service", none.reason)
        val air = of(lte, airplane = true)
        assertEquals(Kind.AIRPLANE, air.kind); assertEquals("Airplane mode", air.reason)
        val nosim = of()
        assertEquals(Kind.NO_SIM, nosim.kind); assertEquals("No SIM", nosim.reason)
        for (r in listOf(none, air, nosim)) {
            assertEquals(WgLink.TINT_OFF, r.tint); assertEquals(0, r.level); assertEquals(MobileLink.OFF_LABEL, r.label)
        }
        assertEquals("Radio off", of(lte.copy(serviceState = MobileLink.POWER_OFF)).reason)
    }

    @Test fun `dual SIM - the default data SIM drives the icon, not the stronger one`() {
        val sim1 = lte.copy(subId = 1, slot = 0, level = 4, dataUser = true)
        val sim2 = lte.copy(subId = 2, slot = 1, level = 1, dataUser = false, networkType = MobileLink.NR)
        val r = of(sim1, sim2, data = 2)
        assertEquals(2, r.sim?.subId); assertEquals(Kind.CALL, r.kind); assertEquals(1, r.level)
        assertEquals("4G", of(sim1, sim2, data = 1).label)
    }

    @Test fun `no SIM chosen for data falls back to an in-service SIM and reads Call`() {
        val r = of(lte.copy(subId = 5, serviceState = MobileLink.OUT_OF_SERVICE), lte.copy(subId = 6), data = MobileLink.NO_SUB)
        assertEquals(6, r.sim?.subId); assertEquals(Kind.CALL, r.kind)
        assertTrue(r.reason.contains("No SIM is chosen"))
    }

    @Test fun `an unreadable data switch falls back to the cellular transport`() {
        assertEquals(Kind.DATA, of(lte.copy(dataUser = null), transport = true).kind)
        assertEquals(Kind.CALL, of(lte.copy(dataUser = null), transport = false).kind)
    }

    @Test fun `a SIM whose service is not reported yet is in service when it has a network or a signal`() {
        assertEquals(Kind.DATA, of(lte.copy(serviceState = null)).kind)
        assertEquals(Kind.NO_SERVICE, of(lte.copy(serviceState = null, networkType = 0, level = 0)).kind)
    }

    @Test fun `the popup lists each SIM - carrier, type, signal, data, roaming`() {
        val l = MobileLink.simLines(lte.copy(roaming = true, dataRoaming = true), isDataSim = true, phonePermission = true,
            d = MobileLink.Detail(mccMnc = "214-01"))
        assertEquals("SIM 1 · Vodafone (214-01) · mobile data SIM", l[0])
        assertEquals("  LTE · Signal 3/4 (-95 dBm)", l[1])
        assertEquals("  Mobile data: ON", l[2])
        assertEquals("  Data roaming: ON · roaming now", l[3])
        val off = MobileLink.simLines(lte.copy(slot = 1, dataUser = false), false, true)
        assertEquals("SIM 2 · Vodafone", off[0]); assertEquals("  Mobile data: OFF", off[2])
        assertEquals("  Data roaming: OFF · not roaming", off[3])
    }

    @Test fun `unreadable cellular fields read a dash, privileged ones name the shell channel`() {
        val bare = MobileLink.Sim(subId = 3)
        val l = MobileLink.simLines(bare, false, phonePermission = false, d = MobileLink.Detail(needsShell = true))
        assertEquals("SIM · —", l[0])
        assertEquals("  — · Signal —", l[1])
        assertEquals("  Mobile data: — (needs Phone permission)", l[2])
        assertEquals("  Voice: VoLTE — needs ADB Shell · VoNR — needs ADB Shell · calls over —", l[4])
        assertEquals("  Wi-Fi calling: — needs ADB Shell", l[5])
        assertEquals("  Preferred network: — needs ADB Shell", l[6])
        assertEquals("  5G: not on 5G", l[7])
        assertEquals("  Cell signal: —", l[8])
        val plain = MobileLink.simLines(bare, false, true, MobileLink.Detail())
        assertEquals("  Wi-Fi calling: —", plain[5])
    }

    @Test fun `cellular detail formatting - voice, Wi-Fi calling, preferred network, 5G offer, RSRP`() {
        val d = MobileLink.Detail(voiceType = MobileLink.LTE, volte = 1, vonr = 0, wfc = -1, preferredMode = "5G/4G/3G/2G (auto)",
            nrAvailability = listOf(1, 2),
            signals = listOf(MobileLink.CellSig("4G", rsrp = -98, rsrq = -11, sinr = 12), MobileLink.CellSig("2G", dbm = -85),
                MobileLink.CellSig("5G", rsrp = -101)))
        val l = MobileLink.simLines(lte, true, true, d)
        assertEquals("  Voice: VoLTE ON · VoNR OFF · calls over 4G (VoLTE)", l[4])
        assertEquals("  Wi-Fi calling: carrier default", l[5])
        assertEquals("  Preferred network: 5G/4G/3G/2G (auto)", l[6])
        assertEquals("  5G: not on 5G · carrier offers NSA+SA", l[7])
        assertEquals("  4G RSRP -98 dBm · RSRQ -11 dB · SINR 12 dB", l[8])
        assertEquals("  2G -85 dBm", l[9])
        assertEquals("  5G RSRP -101 dBm · RSRQ — · SINR —", l[10])
        assertEquals("calls over 5G (VoNR)", MobileLink.voiceLine(MobileLink.NR))
        assertEquals("calls over 3G (circuit-switched)", MobileLink.voiceLine(MobileLink.UMTS))
    }

    @Test fun `MCC MNC, preferred network modes and the shell's SIM rows`() {
        assertEquals("214-01", MobileLink.mccMnc("21401")); assertEquals("310-260", MobileLink.mccMnc("310260"))
        assertEquals(null, MobileLink.mccMnc("")); assertEquals(null, MobileLink.mccMnc(null))
        assertEquals("5G/4G/3G/2G (auto)", MobileLink.familiesLabel(MobileLink.familiesOfRilMode(33)))
        assertEquals("4G/3G/2G (auto)", MobileLink.familiesLabel(MobileLink.familiesOfRilMode(9)))
        assertEquals("4G only", MobileLink.familiesLabel(MobileLink.familiesOfRilMode(11)))
        assertEquals(null, MobileLink.familiesLabel(MobileLink.familiesOfRilMode(99)))
        val nrLte = (1L shl (MobileLink.NR - 1)) or (1L shl (MobileLink.LTE - 1))
        assertEquals(listOf("5G", "4G"), MobileLink.familiesOfBitmask(nrLte))
        val out = "Row: 0 _id=1, volte_vt_enabled=1, wfc_ims_enabled=0, nr_advanced_calling_enabled=-1\n" +
            "Row: 1 _id=2, volte_vt_enabled=0, wfc_ims_enabled=1, nr_advanced_calling_enabled=NULL\n"
        assertEquals(mapOf("volte_vt_enabled" to 1, "wfc_ims_enabled" to 0, "nr_advanced_calling_enabled" to -1), MobileLink.parseSimInfo(out, 1))
        assertEquals(null, MobileLink.parseSimInfo(out, 2)["nr_advanced_calling_enabled"])
        assertEquals(emptyMap<String, Int?>(), MobileLink.parseSimInfo("Error while accessing provider", 1))
    }
}
