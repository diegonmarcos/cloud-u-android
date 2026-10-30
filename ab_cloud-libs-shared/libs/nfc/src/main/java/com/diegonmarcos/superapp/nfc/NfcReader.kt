package com.diegonmarcos.superapp.nfc

import android.nfc.FormatException
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import java.io.IOException

/**
 * The Android half of the standard tag analyzer (#1264): what one tapped [Tag] says through the public tech classes (NfcA / B / F / V,
 * IsoDep, NDEF, NdefFormatable, MIFARE Ultralight page read, MIFARE Classic with the PUBLIC default keys, DESFire GetVersion) and the NDEF
 * write / format of the user's own tags. Reads only public identification data and the tag's user memory; the only keys ever tried are the
 * four public ones declared in the catalog (see [MifareClassicProbe]); no key recovery, no hotel-key lists, no cloning. Runs on the
 * reader-mode binder thread, never the UI.
 */
object NfcReader {
    private fun techName(full: String) = full.substringAfterLast('.')

    fun read(tag: Tag, catalog: NfcCatalog, now: Long = System.currentTimeMillis(), phoneHasNxp: Boolean = false): TagInfo {
        val uid = tag.id ?: ByteArray(0)
        val techs = tag.techList.map(::techName)
        val sections = ArrayList<InfoSection>()
        val identity = ArrayList<Pair<String, String>>()
        identity += "UID" to Bytes.hex(uid, ":")
        identity += "UID length" to "${uid.size} bytes"
        identity += "Technologies" to techs.joinToString(", ")
        var sakValue = -1
        var type = techs.firstOrNull { it != "Ndef" && it != "NdefFormatable" } ?: "Unknown"
        val errors = ArrayList<String>()
        fun guard(what: String, block: () -> Unit) {
            try { block() } catch (e: Exception) { errors += "$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim() }
        }

        NfcA.get(tag)?.let { a ->
            val sak = a.sak.toInt() and 0xFF
            sakValue = sak
            if (uid.isNotEmpty()) identity += "Manufacturer" to catalog.manufacturer(uid[0].toInt() and 0xFF)
            type = catalog.sakName(sak)
            sections += InfoSection("NfcA (ISO 14443-3A)", listOf(
                "ATQA" to Bytes.hex(a.atqa, " "),
                "SAK" to "0x%02X".format(sak),
                "SAK meaning" to catalog.sakName(sak),
                "Max transceive" to "${a.maxTransceiveLength} bytes",
                "Timeout" to "${a.timeout} ms",
            ))
        }
        NfcB.get(tag)?.let { b ->
            type = if (type == "Unknown" || type == "NfcB") "ISO 14443-3B" else type
            sections += InfoSection("NfcB (ISO 14443-3B)", listOf(
                "Application data" to Bytes.hex(b.applicationData, " "),
                "Protocol info" to Bytes.hex(b.protocolInfo, " "),
                "Max transceive" to "${b.maxTransceiveLength} bytes",
            ))
        }
        NfcF.get(tag)?.let { f ->
            type = "FeliCa (JIS X 6319-4)"
            sections += InfoSection("NfcF (FeliCa)", listOf(
                "IDm" to Bytes.hex(uid, " "),
                "PMm (manufacturer)" to Bytes.hex(f.manufacturer, " "),
                "System code" to Bytes.hex(f.systemCode, " "),
                "Max transceive" to "${f.maxTransceiveLength} bytes",
            ))
        }
        NfcV.get(tag)?.let { v ->
            type = "ISO 15693 (NFC-V)"
            // The UID is LSB first on the wire: byte 7 is 0xE0, byte 6 the IC manufacturer.
            if (uid.size == 8) identity += "Manufacturer" to catalog.iso15693(uid[6].toInt() and 0xFF)
            sections += InfoSection("NfcV (ISO 15693)", listOf(
                "DSFID" to "0x%02X".format(v.dsfId.toInt() and 0xFF),
                "Response flags" to "0x%02X".format(v.responseFlags.toInt() and 0xFF),
                "Max transceive" to "${v.maxTransceiveLength} bytes",
            ))
        }
        IsoDep.get(tag)?.let { d ->
            val rows = ArrayList<Pair<String, String>>()
            d.historicalBytes?.let { rows += "ATS historical bytes" to Bytes.hex(it, " "); rows += "Historical (ASCII)" to Bytes.ascii(it) }
            d.hiLayerResponse?.let { rows += "ATTRIB response (B)" to Bytes.hex(it, " ") }
            rows += "Extended length APDU" to if (d.isExtendedLengthApduSupported) "yes" else "no"
            rows += "Max transceive" to "${d.maxTransceiveLength} bytes"
            sections += InfoSection("IsoDep (ISO 14443-4)", rows)
        }
        val classic = MifareClassic.get(tag)
        MifareLayout.unsupportedLine(catalog.isClassicSak(sakValue), classic != null, phoneHasNxp)?.let {
            // Not an error: the card is fine, this phone's chip just cannot open the tech.
            sections += InfoSection("MIFARE Classic", listOf("Support" to it, "Phone MIFARE Classic support (com.nxp.mifare)" to if (phoneHasNxp) "yes" else "no"))
        }
        classic?.let { mc -> guard("MIFARE Classic") { sections += classicSection(mc, catalog) } }
        if (catalog.isDesfireSak(sakValue)) IsoDep.get(tag)?.let { d ->
            guard("DESFire") { desfire(d, catalog)?.let { info -> sections += Desfire.section(info); type = info.name } }
        }
        MifareUltralight.get(tag)?.let { ul -> guard("MIFARE Ultralight") { sections += ultralight(ul, catalog).also { s -> s.rows.firstOrNull { it.first == "Product" }?.let { type = it.second } } } }

        var records: List<NdefRecord> = emptyList()
        var raw = ByteArray(0)
        var writable: Boolean? = null
        var capacity: Int? = null
        android.nfc.tech.Ndef.get(tag)?.let { n ->
            guard("NDEF") {
                n.connect()
                try {
                    val msg = n.ndefMessage ?: n.cachedNdefMessage
                    raw = msg?.toByteArray() ?: ByteArray(0)
                    writable = n.isWritable; capacity = n.maxSize
                    sections += InfoSection("NDEF", listOf(
                        "NDEF type" to n.type,
                        "Max size" to "${n.maxSize} bytes",
                        "Message size" to "${raw.size} bytes",
                        "Writable" to if (n.isWritable) "yes" else "no (read-only)",
                        "Can be made read-only" to if (n.canMakeReadOnly()) "yes" else "no",
                    ), Bytes.dump(raw))
                    if (raw.isNotEmpty()) records = Ndef.parse(raw)
                } finally { runCatching { n.close() } }
            }
        }
        if (NdefFormatable.get(tag) != null) sections += InfoSection("NdefFormatable", listOf("State" to "Blank: can be formatted to NDEF (Write tab)"))

        return TagInfo(now, Bytes.hex(uid), techs, type, listOf(InfoSection("Identity", identity)) + sections, records, raw, writable, capacity, errors.joinToString("; "))
    }

    private fun classicSection(mc: MifareClassic, catalog: NfcCatalog): InfoSection {
        mc.connect()
        try {
            val io = object : ClassicIo {
                override fun auth(sector: Int, key: ByteArray, keyB: Boolean): Boolean = try {
                    if (keyB) mc.authenticateSectorWithKeyB(sector, key) else mc.authenticateSectorWithKeyA(sector, key)
                } catch (e: IOException) {
                    // A wrong key can leave the tag deselected: re-select once; a second failure is a lost tag and propagates.
                    runCatching { mc.close() }; mc.connect(); false
                }
                override fun read(block: Int): ByteArray? = try { mc.readBlock(block) } catch (e: IOException) { null }
            }
            val scan = MifareClassicProbe.scan(io, mc.size, catalog.classicKeys, catalog.classicBudgetMs)
            val kind = when (mc.type) { MifareClassic.TYPE_CLASSIC -> "Classic"; MifareClassic.TYPE_PLUS -> "Plus (SL1)"; MifareClassic.TYPE_PRO -> "Pro"; else -> "Unknown" }
            return ClassicReport.section("MIFARE $kind ${MifareLayout.sizeName(mc.size)}", mc.size, kind, scan, catalog)
        } finally { runCatching { mc.close() } }
    }

    private fun desfire(d: IsoDep, catalog: NfcCatalog): DesfireInfo? {
        d.connect()
        try { return Desfire.probe({ d.transceive(it) }, catalog) } finally { runCatching { d.close() } }
    }

    /** GET_VERSION (0x60) names an NTAG / Ultralight EV1 from the catalog; the pages are read 4 at a time until the tag refuses. */
    private fun ultralight(ul: MifareUltralight, catalog: NfcCatalog): InfoSection {
        ul.connect()
        try {
            val rows = ArrayList<Pair<String, String>>()
            rows += "Android type" to when (ul.type) { MifareUltralight.TYPE_ULTRALIGHT -> "Ultralight"; MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C"; else -> "Unknown" }
            val version = runCatching { ul.transceive(byteArrayOf(0x60)) }.getOrNull()
            val product = version?.takeIf { it.size == 8 }?.let { catalog.version(it) }
            if (version != null) rows += "GET_VERSION" to Bytes.hex(version, " ")
            if (product != null) rows += "Product" to product.name
            // A tag that answered GET_VERSION must be reselected before READ (it may have halted on a NAK).
            if (version == null) { runCatching { ul.close() }; ul.connect() }
            val total = (product?.userPages?.plus(9)) ?: 64
            val out = java.io.ByteArrayOutputStream()
            var page = 0
            while (page < total) {
                val chunk = try { ul.readPages(page) } catch (e: IOException) { break }
                out.write(chunk, 0, minOf(16, (total - page) * 4))
                page += 4
            }
            val mem = out.toByteArray()
            rows += "Pages read" to "${mem.size / 4}"
            if (mem.size >= 16) {
                rows += "Lock bytes" to Bytes.hex(mem.copyOfRange(10, 12), " ")
                rows += "Capability container" to Bytes.hex(mem.copyOfRange(12, 16), " ")
                val cc = mem[12].toInt() and 0xFF
                if (cc == 0xE1) rows += "NDEF data area" to "${(mem[14].toInt() and 0xFF) * 8} bytes, access 0x%02X".format(mem[15].toInt() and 0xFF)
            }
            return InfoSection("MIFARE Ultralight / NTAG", rows, Bytes.dump(mem, 4))
        } finally { runCatching { ul.close() } }
    }

    /** Write [message] (or the canonical empty record to erase) to an NDEF tag, formatting a blank NdefFormatable one when [format] allows. */
    fun write(tag: Tag, message: ByteArray, format: Boolean): String {
        val msg = try { android.nfc.NdefMessage(message) } catch (e: FormatException) { return "Not a valid NDEF message: ${e.message}" }
        android.nfc.tech.Ndef.get(tag)?.let { n ->
            n.connect()
            try {
                if (!n.isWritable) return "The tag is read-only"
                if (message.size > n.maxSize) return "Message is ${message.size} bytes, the tag holds ${n.maxSize}"
                n.writeNdefMessage(msg)
                return "Written: ${message.size} bytes"
            } finally { runCatching { n.close() } }
        }
        NdefFormatable.get(tag)?.let { f ->
            if (!format) return "The tag is blank: allow formatting to write it"
            f.connect()
            try { f.format(msg); return "Formatted and written: ${message.size} bytes" } finally { runCatching { f.close() } }
        }
        return "This tag does not support NDEF"
    }
}
