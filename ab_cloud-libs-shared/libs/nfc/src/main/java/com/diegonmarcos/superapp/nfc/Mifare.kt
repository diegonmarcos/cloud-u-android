package com.diegonmarcos.superapp.nfc

/**
 * MIFARE Classic and DESFire identification (#1322): plain Kotlin over bytes, so the JVM suite checks every rule. The Android half is
 * in [NfcReader] (it wires android.nfc.tech.MifareClassic and IsoDep into the small interfaces below).
 *
 * SCOPE, deliberately narrow. This reads and identifies only:
 *  - MIFARE Classic with the four PUBLIC default sector keys declared in assets/nfc/nfc-catalog.json (transport FFFFFFFFFFFF, blank
 *    000000000000, the MAD key A0A1A2A3A4A5 and the NFC Forum NDEF key D3F7D3F7D3F7), and nothing else;
 *  - DESFire through the unauthenticated native GetVersion (0x60) and GetApplicationIDs (0x6A).
 * A sector that does not open with a public key is reported "locked (private key)". That is the normal state of a hotel key card
 * (Saflok, VingCard, Onity and similar systems) and is NOT an error. Out of scope and never to be added here: key recovery or
 * cracking (nested, darkside, hardnested, mfkey), dictionaries of leaked or vendor keys, and writing to, cloning or emulating access
 * cards. Trailer blocks are shown with both key fields masked, so a readable key B is never displayed.
 */
object MifareLayout {
    /** Phone feature flag of the NXP NFC controller: android.nfc.tech.MifareClassic only works when it is present. */
    const val FEATURE_NXP_MIFARE = "com.nxp.mifare"

    const val UNSUPPORTED_PHONE = "This phone's NFC chip cannot read MIFARE Classic; only the UID/SAK are available"

    fun sectorCount(size: Int): Int = when (size) { 320 -> 5; 1024 -> 16; 2048 -> 32; 4096 -> 40; else -> size / 64 }
    fun blocksIn(sector: Int, size: Int): Int = if (size == 4096 && sector >= 32) 16 else 4
    fun firstBlock(sector: Int, size: Int): Int = if (sector < 32) sector * 4 else 128 + (sector - 32) * 16
    fun blockCount(size: Int): Int = size / 16

    /** Family name from the byte size: Mini 320, 1K, 2K, 4K. */
    fun sizeName(size: Int): String = when (size) { 320 -> "Mini (320 B)"; 1024 -> "1K"; 2048 -> "2K"; 4096 -> "4K"; else -> "$size B" }

    /** The line shown for a Classic card the phone cannot open as MIFARE Classic, or null when it can (or the SAK is not Classic). */
    fun unsupportedLine(sakIsClassic: Boolean, techPresent: Boolean, phoneHasNxp: Boolean): String? = when {
        !sakIsClassic || techPresent -> null
        !phoneHasNxp -> UNSUPPORTED_PHONE
        else -> "The tag says MIFARE Classic but Android exposed no MIFARE Classic tech; only the UID/SAK are available"
    }
}

/** What the probe needs from a connected MifareClassic. [auth] returns false for a wrong key (the adapter re-selects the tag). */
interface ClassicIo {
    fun auth(sector: Int, key: ByteArray, keyB: Boolean): Boolean
    fun read(block: Int): ByteArray?
}

/** One sector: [keyName] is the public key that opened it, null when it is locked (private key). */
data class SectorRead(val sector: Int, val keyName: String?, val keyB: Boolean, val firstBlock: Int, val blocks: List<ByteArray?>) {
    val open: Boolean get() = keyName != null
}

data class ClassicScan(val sectors: List<SectorRead>, val notTried: Int = 0)

object MifareClassicProbe {
    /** Tries every public [keys] entry as key A then key B on each sector, within [budgetMs]; reads all blocks of an open sector. */
    fun scan(io: ClassicIo, size: Int, keys: List<ClassicKey>, budgetMs: Long, clock: () -> Long = System::currentTimeMillis): ClassicScan {
        val t0 = clock()
        val out = ArrayList<SectorRead>()
        val n = MifareLayout.sectorCount(size)
        for (s in 0 until n) {
            if (clock() - t0 > budgetMs) return ClassicScan(out, n - s)
            val first = MifareLayout.firstBlock(s, size)
            var opened: SectorRead? = null
            search@ for (key in keys) for (b in listOf(false, true)) {
                if (io.auth(s, key.bytes, b)) {
                    opened = SectorRead(s, key.name, b, first, List(MifareLayout.blocksIn(s, size)) { io.read(first + it) })
                    break@search
                }
            }
            out += opened ?: SectorRead(s, null, false, first, emptyList())
        }
        return ClassicScan(out)
    }
}

data class MadEntry(val sector: Int, val aid: Int)
data class Mad(val version: Int, val multiApp: Boolean, val crcOk: Boolean, val info: Int, val entries: List<MadEntry>)

/** MIFARE Application Directory (NXP AN10787): sector 0 blocks 1-2 (MAD1), plus sector 16 (MAD2) on the 4K. */
object MadDecoder {
    fun crc8(b: ByteArray): Int {
        var crc = 0xC7
        for (x in b) {
            crc = crc xor (x.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x1D) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    /** [gpb] is byte 9 of the sector 0 trailer: bit 7 = MAD available, bit 6 = multi-application card, bits 1..0 = MAD version. Null when there is no MAD. */
    fun decode(block1: ByteArray, block2: ByteArray, gpb: Int, mad2: List<ByteArray>? = null): Mad? {
        if (gpb and 0x80 == 0 || block1.size != 16 || block2.size != 16) return null
        val version = gpb and 0x03
        if (version != 1 && version != 2) return null
        var ok = (block1[0].toInt() and 0xFF) == crc8(block1.copyOfRange(1, 16) + block2)
        val entries = ArrayList<MadEntry>()
        fun aids(first: Int, data: ByteArray, from: Int, count: Int) {
            for (i in 0 until count) entries += MadEntry(first + i, Bytes.u16le(data, from + i * 2))
        }
        aids(1, block1, 2, 7)
        aids(8, block2, 0, 8)
        if (version == 2 && mad2 != null && mad2.size == 3 && mad2.all { it.size == 16 }) {
            ok = ok && (mad2[0][0].toInt() and 0xFF) == crc8(mad2[0].copyOfRange(1, 16) + mad2[1] + mad2[2])
            aids(17, mad2[0], 2, 7)
            aids(24, mad2[1], 0, 8)
            aids(32, mad2[2], 0, 8)
        }
        return Mad(version, gpb and 0x40 != 0, ok, block1[1].toInt() and 0x3F, entries)
    }
}

object ClassicReport {
    /** `xx` hides both key fields of a sector trailer: key A reads back zeros anyway, key B must never be displayed. */
    fun blockLine(block: Int, data: ByteArray?, trailer: Boolean): String {
        if (data == null) return "%03d  (read refused)".format(block)
        if (!trailer || data.size != 16) return "%03d  %s  %s".format(block, Bytes.hex(data, " "), Bytes.ascii(data))
        return "%03d  xx xx xx xx xx xx %s xx xx xx xx xx xx  (trailer: keys hidden, access bits + GPB shown)".format(block, Bytes.hex(data.copyOfRange(6, 10), " "))
    }

    fun summary(scan: ClassicScan): Pair<String, Boolean> {
        val total = scan.sectors.size + scan.notTried
        val open = scan.sectors.count { it.open }
        val locked = scan.sectors.size - open
        val text = when {
            open == 0 && scan.notTried == 0 -> "All $total sectors are locked (private key). This is normal for a hotel key card: the UID and card type above are what a phone can read; the content needs the system's private key."
            else -> "$open of $total sectors open with public keys; $locked locked (private key)" + if (scan.notTried > 0) "; ${scan.notTried} not tried (time budget)" else ""
        }
        return text to (open > 0)
    }

    fun section(typeName: String, size: Int, androidType: String, scan: ClassicScan, catalog: NfcCatalog): InfoSection {
        val rows = ArrayList<Pair<String, String>>()
        rows += "Type" to typeName
        rows += "Android type" to androidType
        rows += "Size" to "$size bytes"
        rows += "Sectors" to "${MifareLayout.sectorCount(size)}"
        rows += "Blocks" to "${MifareLayout.blockCount(size)}"
        rows += "Public keys tried" to catalog.classicKeys.joinToString(", ") { it.name + " " + Bytes.hex(it.bytes) }
        rows += "Result" to summary(scan).first
        val dump = ArrayList<String>()
        for (s in scan.sectors) {
            rows += "Sector ${s.sector}" to if (s.open) "open (key ${if (s.keyB) "B" else "A"}: ${s.keyName})" else "locked (private key)"
            if (s.open) {
                dump += "-- sector ${s.sector}"
                s.blocks.forEachIndexed { i, b -> dump += blockLine(s.firstBlock + i, b, i == s.blocks.size - 1) }
            }
        }
        val s0 = scan.sectors.firstOrNull { it.sector == 0 && it.open && it.blocks.size == 4 }
        if (s0 != null) {
            val b1 = s0.blocks[1]; val b2 = s0.blocks[2]; val t = s0.blocks[3]
            val s16 = scan.sectors.firstOrNull { it.sector == 16 && it.open && it.blocks.size == 4 }
            val m2 = s16?.blocks?.take(3)?.filterNotNull()?.takeIf { it.size == 3 }
            val mad = if (b1 != null && b2 != null && t != null && t.size == 16) MadDecoder.decode(b1, b2, t[9].toInt() and 0xFF, m2) else null
            if (mad == null) rows += "MAD" to "none (sector 0 does not declare an application directory)"
            else {
                rows += "MAD" to "version ${mad.version}${if (mad.multiApp) ", multi-application" else ""}, CRC ${if (mad.crcOk) "ok" else "BAD"}"
                mad.entries.filter { it.aid != 0 }.forEach { rows += "MAD sector ${it.sector}" to catalog.madAid(it.aid) }
            }
        }
        return InfoSection("MIFARE Classic", rows, dump.joinToString("\n"))
    }
}

data class DesfireVersion(
    val hwVendor: Int, val hwType: Int, val hwSub: Int, val hwMajor: Int, val hwMinor: Int, val hwStorage: Int, val hwProto: Int,
    val swMajor: Int, val swMinor: Int, val swStorage: Int,
    val uid: ByteArray, val batch: ByteArray, val week: Int, val year: Int,
) {
    val storage: String get() = if (hwStorage in 0x10..0x30) { val b = 1L shl (hwStorage shr 1); (if (b >= 1024) "${b / 1024} KB" else "$b B") + if (hwStorage and 1 != 0) " or more" else "" } else "0x%02X".format(hwStorage)
}

data class DesfireInfo(val version: DesfireVersion, val name: String, val appIds: List<String>?, val appNote: String)

object Desfire {
    /** 28 bytes: hardware (7), software (7), UID (7), batch (5), production week (BCD) and year (BCD). */
    fun parseVersion(b: ByteArray): DesfireVersion? {
        if (b.size != 28) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        return DesfireVersion(u(0), u(1), u(2), u(3), u(4), u(5), u(6), u(10), u(11), u(12), b.copyOfRange(14, 21), b.copyOfRange(21, 26), u(26), u(27))
    }

    private fun bcd(x: Int) = "%02X".format(x)

    fun productionText(v: DesfireVersion) = "week ${bcd(v.week)} of 20${bcd(v.year)}"

    fun describe(v: DesfireVersion, catalog: NfcCatalog): String =
        catalog.desfireName(v.hwType, v.hwMajor) ?: "NXP DESFire-family (hardware type 0x%02X, version %d.%d)".format(v.hwType, v.hwMajor, v.hwMinor)

    private val GET_VERSION = byteArrayOf(0x90.toByte(), 0x60, 0, 0, 0)
    private val GET_APPS = byteArrayOf(0x90.toByte(), 0x6A, 0, 0, 0)
    private val MORE = byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0, 0, 0)

    /** Split an ISO-wrapped DESFire answer into (data, status byte SW2) when SW1 is 0x91, else null. */
    fun split(resp: ByteArray): Pair<ByteArray, Int>? =
        if (resp.size >= 2 && (resp[resp.size - 2].toInt() and 0xFF) == 0x91) resp.copyOfRange(0, resp.size - 2) to (resp[resp.size - 1].toInt() and 0xFF) else null

    /** Runs a command and its 0xAF continuations; returns the joined data and the final SW2, or null when the card does not answer in DESFire framing. */
    private fun run(tx: (ByteArray) -> ByteArray, first: ByteArray): Pair<ByteArray, Int>? {
        val out = java.io.ByteArrayOutputStream()
        var cmd = first
        repeat(16) {
            val (data, sw2) = split(tx(cmd)) ?: return null
            out.write(data)
            if (sw2 != 0xAF) return out.toByteArray() to sw2
            cmd = MORE
        }
        return out.toByteArray() to 0xAF
    }

    /** Identify a DESFire card without authenticating; null when the card is not a DESFire (or answers otherwise). */
    fun probe(tx: (ByteArray) -> ByteArray, catalog: NfcCatalog): DesfireInfo? {
        val (data, sw2) = run(tx, GET_VERSION) ?: return null
        if (sw2 != 0x00) return null
        val v = parseVersion(data) ?: return null
        val apps = runCatching { run(tx, GET_APPS) }.getOrNull()
        val (ids, note) = when {
            apps == null -> null to "Application list not available"
            apps.second == 0x00 -> appIds(apps.first) to "Application list read without authentication"
            apps.second == 0xAE || apps.second == 0x9D -> null to "Application list needs authentication (status 91 %02X): not available".format(apps.second)
            else -> null to "Application list refused (status 91 %02X)".format(apps.second)
        }
        return DesfireInfo(v, describe(v, catalog), ids, note)
    }

    /** 3-byte AIDs, LSB first on the wire, shown most significant first. */
    fun appIds(d: ByteArray): List<String> = List(d.size / 3) { i -> "%02X%02X%02X".format(d[i * 3 + 2].toInt() and 0xFF, d[i * 3 + 1].toInt() and 0xFF, d[i * 3].toInt() and 0xFF) }

    fun section(info: DesfireInfo): InfoSection {
        val v = info.version
        val rows = ArrayList<Pair<String, String>>()
        rows += "Product" to info.name
        rows += "Vendor" to if (v.hwVendor == 0x04) "NXP Semiconductors" else "0x%02X".format(v.hwVendor)
        rows += "Hardware" to "type 0x%02X, subtype 0x%02X, version %d.%d, protocol 0x%02X".format(v.hwType, v.hwSub, v.hwMajor, v.hwMinor, v.hwProto)
        rows += "Software" to "version ${v.swMajor}.${v.swMinor}"
        rows += "Memory" to v.storage
        rows += "UID (7 bytes)" to Bytes.hex(v.uid, ":")
        rows += "Batch" to Bytes.hex(v.batch)
        rows += "Production" to productionText(v)
        rows += "Applications" to (info.appIds?.let { if (it.isEmpty()) "none" else it.joinToString(", ") } ?: info.appNote)
        if (info.appIds != null) rows += "Application list" to info.appNote
        return InfoSection("MIFARE DESFire", rows)
    }
}
