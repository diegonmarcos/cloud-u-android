package com.diegonmarcos.superapp.bluetooth

import kotlin.math.pow

/** One AD structure of an advertisement: the AD type and its data, exactly as the air carried them. */
class AdStructure(val type: Int, val data: ByteArray) {
    val typeName: String get() = AdvParser.typeName(type)
    val hex: String get() = Hex.format(data)
}

/** A recognised beacon frame. [txAt1m] is the calibrated power at one metre the frame declares (Eddystone's 0 m value minus 41). */
data class Beacon(val kind: String, val fields: List<Pair<String, String>>, val txAt1m: Int? = null)

/** Everything an advertisement says, parsed: the structures in air order plus the fields a scanner page shows. */
data class Advertisement(
    val structures: List<AdStructure> = emptyList(),
    val flags: Int? = null,
    val name: String? = null,
    val shortName: Boolean = false,
    val txPower: Int? = null,
    val serviceUuids: List<String> = emptyList(),
    val solicited: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val manufacturer: Map<Int, ByteArray> = emptyMap(),
    val appearance: Int? = null,
    val beacons: List<Beacon> = emptyList(),
    val malformed: Boolean = false,
) {
    val flagNames: List<String> get() = flags?.let { AdvParser.flagNames(it) }.orEmpty()

    /** The best one-metre reference the advertisement offers: a beacon's calibrated value, else TX power minus 41 dB. */
    val txAt1m: Int? get() = beacons.firstNotNullOfOrNull { it.txAt1m } ?: txPower?.let { it - 41 }
}

/**
 * The Bluetooth LE advertising-data parser (Core Spec Vol 3 Part C §11, Supplement to the Core Specification Part A): splits the raw
 * scan record into AD structures and reads flags, names, TX power, 16/32/128-bit service UUIDs (complete, incomplete and solicited),
 * service data, manufacturer data, appearance, and the beacon frames iBeacon, AltBeacon and Eddystone UID / URL / TLM / EID.
 * Plain Kotlin over bytes: the JVM suite feeds it captured payloads.
 */
object AdvParser {
    const val APPLE = 0x004C
    const val EDDYSTONE = 0xFEAA

    private val TYPES = mapOf(
        0x01 to "Flags", 0x02 to "Incomplete 16-bit UUIDs", 0x03 to "Complete 16-bit UUIDs", 0x04 to "Incomplete 32-bit UUIDs",
        0x05 to "Complete 32-bit UUIDs", 0x06 to "Incomplete 128-bit UUIDs", 0x07 to "Complete 128-bit UUIDs", 0x08 to "Shortened local name",
        0x09 to "Complete local name", 0x0A to "TX power level", 0x0D to "Class of device", 0x0E to "Simple pairing hash C",
        0x0F to "Simple pairing randomizer R", 0x10 to "Device ID / Security manager TK", 0x11 to "Security manager OOB flags",
        0x12 to "Peripheral connection interval range", 0x14 to "16-bit solicitation UUIDs", 0x15 to "128-bit solicitation UUIDs",
        0x16 to "Service data, 16-bit UUID", 0x17 to "Public target address", 0x18 to "Random target address", 0x19 to "Appearance",
        0x1A to "Advertising interval", 0x1B to "LE Bluetooth device address", 0x1C to "LE role", 0x1F to "32-bit solicitation UUIDs",
        0x20 to "Service data, 32-bit UUID", 0x21 to "Service data, 128-bit UUID", 0x22 to "LE secure connections confirmation",
        0x23 to "LE secure connections random", 0x24 to "URI", 0x25 to "Indoor positioning", 0x26 to "Transport discovery data",
        0x27 to "LE supported features", 0x28 to "Channel map update indication", 0x29 to "PB-ADV", 0x2A to "Mesh message",
        0x2B to "Mesh beacon", 0x2C to "BIGInfo", 0x2D to "Broadcast code", 0x2E to "Resolvable set identifier",
        0x2F to "Advertising interval (long)", 0x30 to "Broadcast name", 0x31 to "Encrypted advertising data", 0x3D to "3D information data",
        0xFF to "Manufacturer specific data",
    )

    fun typeName(type: Int): String = TYPES[type] ?: "AD type 0x%02X".format(type)

    fun flagNames(f: Int): List<String> = buildList {
        if (f and 0x01 != 0) add("LE Limited Discoverable")
        if (f and 0x02 != 0) add("LE General Discoverable")
        if (f and 0x04 != 0) add("BR/EDR not supported")
        if (f and 0x08 != 0) add("LE + BR/EDR controller")
        if (f and 0x10 != 0) add("LE + BR/EDR host")
    }

    /** Splits [raw] into AD structures; a zero length ends the significant part (the rest is padding). */
    fun structures(raw: ByteArray): Pair<List<AdStructure>, Boolean> {
        val out = ArrayList<AdStructure>()
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 1 + len > raw.size) return out to true
            out += AdStructure(raw[i + 1].toInt() and 0xFF, raw.copyOfRange(i + 2, i + 1 + len))
            i += 1 + len
        }
        return out to false
    }

    fun parse(raw: ByteArray): Advertisement {
        val (st, malformed) = structures(raw)
        var flags: Int? = null
        var name: String? = null
        var short = false
        var tx: Int? = null
        var appearance: Int? = null
        val uuids = ArrayList<String>()
        val solicited = ArrayList<String>()
        val sdata = LinkedHashMap<String, ByteArray>()
        val manu = LinkedHashMap<Int, ByteArray>()
        for (s in st) {
            val d = s.data
            when (s.type) {
                0x01 -> if (d.isNotEmpty()) flags = d[0].toInt() and 0xFF
                0x02, 0x03 -> uuids += uuidList(d, 2)
                0x04, 0x05 -> uuids += uuidList(d, 4)
                0x06, 0x07 -> uuids += uuidList(d, 16)
                0x14 -> solicited += uuidList(d, 2)
                0x1F -> solicited += uuidList(d, 4)
                0x15 -> solicited += uuidList(d, 16)
                0x08 -> if (name == null) { name = String(d, Charsets.UTF_8); short = true }
                0x09 -> { name = String(d, Charsets.UTF_8); short = false }
                0x30 -> if (name == null) name = String(d, Charsets.UTF_8)
                0x0A -> if (d.isNotEmpty()) tx = d[0].toInt()
                0x19 -> if (d.size >= 2) appearance = u16le(d, 0)
                0x16 -> if (d.size >= 2) sdata[uuid(d.copyOfRange(0, 2))] = d.copyOfRange(2, d.size)
                0x20 -> if (d.size >= 4) sdata[uuid(d.copyOfRange(0, 4))] = d.copyOfRange(4, d.size)
                0x21 -> if (d.size >= 16) sdata[uuid(d.copyOfRange(0, 16))] = d.copyOfRange(16, d.size)
                0xFF -> if (d.size >= 2) manu[u16le(d, 0)] = d.copyOfRange(2, d.size)
            }
        }
        val beacons = beacons(manu, sdata)
        return Advertisement(st, flags, name, short, tx, uuids.distinct(), solicited.distinct(), sdata, manu, appearance, beacons, malformed)
    }

    /** Little-endian UUIDs of [width] bytes each, as full lower-case UUID strings. */
    fun uuidList(d: ByteArray, width: Int): List<String> = (0 until d.size / width).map { uuid(d.copyOfRange(it * width, it * width + width)) }

    fun uuid(le: ByteArray): String {
        val be = le.reversedArray()
        val h = Hex.format(be, "").lowercase()
        return when (be.size) {
            2 -> "0000$h${Uuids.BASE_SUFFIX}"
            4 -> "$h${Uuids.BASE_SUFFIX}"
            else -> "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
        }
    }

    private fun u16le(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun u16be(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun u32be(d: ByteArray, o: Int): Long = (0..3).fold(0L) { a, k -> (a shl 8) or (d[o + k].toLong() and 0xFF) }

    private fun uuidBe(d: ByteArray, o: Int): String {
        val h = Hex.format(d.copyOfRange(o, o + 16), "").lowercase()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    fun beacons(manu: Map<Int, ByteArray>, sdata: Map<String, ByteArray>): List<Beacon> = buildList {
        manu[APPLE]?.let { d ->
            if (d.size >= 23 && d[0].toInt() == 0x02 && d[1].toInt() == 0x15) {
                add(Beacon("iBeacon", listOf("UUID" to uuidBe(d, 2), "Major" to u16be(d, 18).toString(), "Minor" to u16be(d, 20).toString(),
                    "Measured power" to "${d[22].toInt()} dBm"), d[22].toInt()))
            }
        }
        for ((company, d) in manu) {
            if (d.size >= 24 && (d[0].toInt() and 0xFF) == 0xBE && (d[1].toInt() and 0xFF) == 0xAC) {
                add(Beacon("AltBeacon", listOf("ID1" to uuidBe(d, 2), "ID2" to u16be(d, 18).toString(), "ID3" to u16be(d, 20).toString(),
                    "Reference RSSI" to "${d[22].toInt()} dBm", "Reserved" to "0x%02X".format(d[23].toInt() and 0xFF),
                    "Manufacturer" to "0x%04X".format(company)), d[22].toInt()))
            }
        }
        sdata[Uuids.normalize("feaa")]?.let { d -> eddystone(d)?.let { add(it) } }
    }

    private val EDDY_SCHEMES = listOf("http://www.", "https://www.", "http://", "https://")
    private val EDDY_EXPANSIONS = listOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    fun eddystoneUrl(d: ByteArray, from: Int): String? {
        if (from >= d.size) return null
        val scheme = EDDY_SCHEMES.getOrNull(d[from].toInt() and 0xFF) ?: return null
        val sb = StringBuilder(scheme)
        for (i in from + 1 until d.size) {
            val c = d[i].toInt() and 0xFF
            if (c < EDDY_EXPANSIONS.size) sb.append(EDDY_EXPANSIONS[c]) else if (c in 0x21..0x7E) sb.append(c.toChar()) else return null
        }
        return sb.toString()
    }

    fun eddystone(d: ByteArray): Beacon? {
        if (d.isEmpty()) return null
        return when (d[0].toInt() and 0xFF) {
            0x00 -> if (d.size >= 18) Beacon("Eddystone-UID", listOf("Namespace" to Hex.format(d.copyOfRange(2, 12), ""), "Instance" to Hex.format(d.copyOfRange(12, 18), ""),
                "TX at 0 m" to "${d[1].toInt()} dBm"), d[1].toInt() - 41) else null
            0x10 -> if (d.size >= 3) Beacon("Eddystone-URL", listOf("URL" to (eddystoneUrl(d, 2) ?: "(invalid encoding)"), "TX at 0 m" to "${d[1].toInt()} dBm"), d[1].toInt() - 41) else null
            0x20 -> if (d.size >= 14 && d[1].toInt() == 0x00) {
                val mv = u16be(d, 2)
                val temp = if ((d[4].toInt() and 0xFF) == 0x80 && d[5].toInt() == 0) null else d[4].toInt() + (d[5].toInt() and 0xFF) / 256.0
                Beacon("Eddystone-TLM", listOf("Battery" to if (mv == 0) "n/a" else "$mv mV", "Temperature" to (temp?.let { "%.2f °C".format(it) } ?: "n/a"),
                    "Advertisements" to u32be(d, 6).toString(), "Uptime" to "%.1f s".format(u32be(d, 10) / 10.0)))
            } else if (d.size >= 2 && d[1].toInt() == 0x01) Beacon("Eddystone-eTLM", listOf("Encrypted TLM" to Hex.format(d.copyOfRange(2, d.size), ""))) else null
            0x30 -> if (d.size >= 10) Beacon("Eddystone-EID", listOf("EID" to Hex.format(d.copyOfRange(2, 10), ""), "TX at 0 m" to "${d[1].toInt()} dBm"), d[1].toInt() - 41) else null
            else -> null
        }
    }

    /**
     * Log-distance path loss: d = 10^((P1m - RSSI) / (10 n)) metres. [n] is the environment exponent (2 free space, 2.5-4 indoors).
     * Null without a one-metre reference: a distance from a guessed TX power is not a measurement.
     */
    fun distanceMeters(rssi: Int, txAt1m: Int?, n: Double = 2.0): Double? {
        if (txAt1m == null || rssi >= 0 || rssi <= -127 || n <= 0) return null
        return 10.0.pow((txAt1m - rssi) / (10.0 * n))
    }

    /** Exponential smoothing over an RSSI history, newest last (alpha 0.3); null on an empty history. */
    fun smooth(history: List<Int>, alpha: Double = 0.3): Double? {
        if (history.isEmpty()) return null
        var s = history.first().toDouble()
        for (v in history.drop(1)) s = alpha * v + (1 - alpha) * s
        return s
    }
}

/** The classic Bluetooth class of device (Assigned Numbers §2.8): the major class name of a CoD value. */
object BtClass {
    private val MAJOR = mapOf(
        0x0000 to "Miscellaneous", 0x0100 to "Computer", 0x0200 to "Phone", 0x0300 to "Network access point", 0x0400 to "Audio / video",
        0x0500 to "Peripheral", 0x0600 to "Imaging", 0x0700 to "Wearable", 0x0800 to "Toy", 0x0900 to "Health", 0x1F00 to "Uncategorized",
    )

    fun major(cod: Int): String = MAJOR[cod and 0x1F00] ?: "Major class 0x%04X".format(cod and 0x1F00)
}

/** The locator's arithmetic, plain Kotlin for the JVM suite. */
object Locator {
    /** 0 at -100 dBm or weaker, 1 at -35 dBm or stronger. */
    fun proximity(rssi: Double): Double = ((rssi + 100.0) / 65.0).coerceIn(0.0, 1.0)

    /** Beep period: 1500 ms far away down to 100 ms right next to it. */
    fun beepIntervalMs(rssi: Double): Long = (1500 - proximity(rssi) * 1400).toLong()

    /** Warmer / colder from the last five samples against the five before them (3 dB dead band). */
    fun trend(h: List<Int>): String {
        if (h.size < 10) return "Collecting..."
        val now = h.takeLast(5).average(); val before = h.dropLast(5).takeLast(5).average()
        return when { now - before >= 3 -> "Warmer"; before - now >= 3 -> "Colder"; else -> "Steady" }
    }
}
