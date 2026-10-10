package com.diegonmarcos.superapp.network

/**
 * The status strip's GPS icon and the network popup's GPS section, as pure rules: how good the
 * current position fix is (the 0..4 dots), where it came from (satellites or cell towers / Wi-Fi),
 * and the GNSS constellations and bands the receiver sees. Constants mirror the platform's
 * (GnssStatus.CONSTELLATION_*, LocationManager providers) so this file needs no android import.
 *
 * Battery: the strip never starts a location request of its own. Its icon is
 * LocationManager.isLocationEnabled and its dots come from the passive provider (fixes other
 * apps already asked for) or the last known fix; only the popup's GPS section, while it is on
 * screen, asks for live GNSS (see GpsProbe).
 */
object GpsFix {

    enum class Source { GNSS, NETWORK, UNKNOWN }

    data class Fix(
        /** LocationManager provider name: "gps", "network", "fused", "passive"... */
        val provider: String,
        val accuracyM: Float?,
        val ageMs: Long,
        /** Satellites used in the fix, when the provider says (Location extras "satellites", or the live status). */
        val satellitesUsed: Int? = null,
    )

    /** The fused provider's fixes this sharp only come from satellites; Wi-Fi / cell are coarser. */
    const val FUSED_GNSS_M = 10f

    fun source(f: Fix): Source = when {
        f.provider == "gps" -> Source.GNSS
        (f.satellitesUsed ?: 0) > 0 -> Source.GNSS
        f.provider == "network" -> Source.NETWORK
        f.provider == "fused" && f.accuracyM != null -> if (f.accuracyM <= FUSED_GNSS_M) Source.GNSS else Source.NETWORK
        else -> Source.UNKNOWN
    }

    /** A fix older than this is not "now": the dots fall to at most 1. */
    const val STALE_MS = 2 * 60_000L
    /** A fix older than this says nothing about now: 0 dots. */
    const val EXPIRED_MS = 10 * 60_000L

    /**
     * 0..4. No fix = 0. A satellite fix maps its accuracy: <= 5 m 4, <= 15 m 3, <= 50 m 2, worse 1;
     * fewer than 4 satellites used (a weak, 2D fix) caps it at 2. A network-only fix (cell towers /
     * Wi-Fi) is at most 2 (<= 100 m) and otherwise 1. A stale fix is at most 1, an expired one 0.
     * Location off = 0.
     */
    fun dots(locationOn: Boolean, f: Fix?): Int {
        if (!locationOn || f == null) return 0
        if (f.ageMs > EXPIRED_MS) return 0
        val acc = f.accuracyM
        val byAccuracy = when (source(f)) {
            Source.GNSS -> {
                val l = when {
                    acc == null -> 2
                    acc <= 5f -> 4
                    acc <= 15f -> 3
                    acc <= 50f -> 2
                    else -> 1
                }
                val sats = f.satellitesUsed
                if (sats != null && sats in 1..3) minOf(l, 2) else l
            }
            Source.NETWORK, Source.UNKNOWN -> if (acc != null && acc <= 100f) 2 else 1
        }
        return if (f.ageMs > STALE_MS) minOf(byAccuracy, 1) else byAccuracy
    }

    // ── constellations ──────────────────────────────────────────────────

    // GnssStatus.CONSTELLATION_*
    const val GPS = 1; const val SBAS = 2; const val GLONASS = 3; const val QZSS = 4
    const val BEIDOU = 5; const val GALILEO = 6; const val NAVIC = 7

    /** The order the popup lists them in. */
    val CONSTELLATIONS = listOf(GPS, GLONASS, GALILEO, BEIDOU, QZSS, NAVIC, SBAS)

    fun constellationName(c: Int): String = when (c) {
        GPS -> "GPS"; SBAS -> "SBAS"; GLONASS -> "GLONASS"; QZSS -> "QZSS"
        BEIDOU -> "BeiDou"; GALILEO -> "Galileo"; NAVIC -> "NavIC"
        else -> "Other"
    }

    /**
     * The signal band of a carrier frequency, in that constellation's own naming: GPS/QZSS L1 L2 L5,
     * Galileo E1 E5a E5b E6, BeiDou B1I B1C B2a B2b B3I, GLONASS G1 G2 G3, NavIC L5 S, SBAS L1 L5.
     * Null when no frequency is known or it falls outside every band.
     */
    fun band(constellation: Int, hz: Double?): String? {
        if (hz == null || hz <= 0) return null
        val mhz = hz / 1e6
        fun near(c: Double, w: Double = 10.0) = kotlin.math.abs(mhz - c) <= w
        return when (constellation) {
            GALILEO -> when { near(1575.42) -> "E1"; near(1176.45) -> "E5a"; near(1207.14) -> "E5b"; near(1278.75) -> "E6"; else -> null }
            BEIDOU -> when {
                near(1561.098, 5.0) -> "B1I"; near(1575.42, 5.0) -> "B1C"; near(1176.45) -> "B2a"
                near(1207.14) -> "B2b"; near(1268.52) -> "B3I"; else -> null
            }
            GLONASS -> when { mhz in 1590.0..1610.0 -> "G1"; mhz in 1237.0..1256.0 -> "G2"; near(1202.025) -> "G3"; else -> null }
            NAVIC -> when { near(1176.45) -> "L5"; near(2492.028) -> "S"; else -> null }
            else -> when { near(1575.42) -> "L1"; near(1227.60) -> "L2"; near(1176.45) -> "L5"; else -> null }
        }
    }

    /** The second-frequency bands (L5 / E5 / B2 / L2 / G2 / G3 / E6 / B3): seeing one is dual-frequency. */
    fun isSecondFrequency(band: String?): Boolean = band != null && band !in setOf("L1", "E1", "B1I", "B1C", "G1", "S")

    /** One satellite of a GnssStatus. */
    data class Sat(val constellation: Int, val usedInFix: Boolean, val carrierHz: Double? = null)

    data class ConstellationCount(val constellation: Int, val inView: Int, val used: Int, val bands: List<String>)

    /** Satellites in view / used and the bands seen, per constellation, in [CONSTELLATIONS] order. */
    fun byConstellation(sats: List<Sat>): List<ConstellationCount> =
        sats.groupBy { it.constellation }.map { (c, ss) ->
            ConstellationCount(c, ss.size, ss.count { it.usedInFix },
                ss.mapNotNull { band(c, it.carrierHz) }.distinct().sortedBy { if (isSecondFrequency(it)) 1 else 0 })
        }.sortedBy { CONSTELLATIONS.indexOf(it.constellation).let { i -> if (i < 0) 99 else i } }

    /** "Galileo 9 in view / 6 used · E1+E5a". */
    fun constellationLine(c: ConstellationCount): String =
        "${constellationName(c.constellation)} ${c.inView} in view / ${c.used} used" +
            (if (c.bands.isEmpty()) "" else " · " + c.bands.joinToString("+"))

    fun dualFrequencySeen(sats: List<Sat>): Boolean = sats.any { isSecondFrequency(band(it.constellation, it.carrierHz)) }

    // ── popup lines ─────────────────────────────────────────────────────

    const val NETWORK_ONLY = "Using cell towers / Wi-Fi (no satellites)"

    /** "GNSS (satellites) · ±4 m · 12 s old", or the network-only text. */
    fun fixLine(f: Fix?): String {
        if (f == null) return "Fix: none yet"
        val acc = f.accuracyM?.let { "±${if (it < 10f) "%.1f".format(it) else it.toInt().toString()} m" } ?: "± —"
        val src = when (source(f)) {
            Source.GNSS -> "GNSS (satellites" + (f.satellitesUsed?.let { ", $it used" } ?: "") + ")"
            Source.NETWORK -> "network (cell towers / Wi-Fi)"
            Source.UNKNOWN -> f.provider
        }
        return "Fix: $src · $acc · ${WgLink.ago(f.ageMs)} old"
    }

    /**
     * The section's satellite summary: per-constellation lines when satellites are in view; the
     * network-only text when the fix is from cell towers / Wi-Fi and no satellite is seen.
     */
    fun satelliteLines(sats: List<Sat>, f: Fix?, listening: Boolean): List<String> {
        if (sats.isNotEmpty()) return byConstellation(sats).map(::constellationLine)
        if (f != null && source(f) == Source.NETWORK) return listOf(NETWORK_ONLY)
        return listOf(if (listening) "Satellites: searching…" else "Satellites: —")
    }

    /** LocationManager.MODE_* via Settings.Secure.LOCATION_MODE (0..3). */
    fun modeName(mode: Int?): String = when (mode) {
        0 -> "off"
        1 -> "device only (GPS)"
        2 -> "battery saving (network)"
        3 -> "high accuracy (GPS + network)"
        else -> "on"
    }
}
