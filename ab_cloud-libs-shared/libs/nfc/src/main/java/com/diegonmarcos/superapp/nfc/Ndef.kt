package com.diegonmarcos.superapp.nfc

/** One NDEF record exactly as on the tag, plus its decoded reading. */
class NdefRecord(
    val tnf: Int,
    val type: ByteArray,
    val id: ByteArray,
    val payload: ByteArray,
    val mb: Boolean = false,
    val me: Boolean = false,
    val chunked: Boolean = false,
    val shortRecord: Boolean = false,
) {
    val typeText: String get() = String(type, Charsets.US_ASCII)
    val tnfName: String get() = Ndef.TNF_NAMES.getOrElse(tnf) { "Reserved" }
    val decoded: Decoded get() = Ndef.decode(this)
}

/** What a record means: a kind (`Text`, `URI`, `Smart poster`, `Wi-Fi`...), a one-line summary and labelled fields; nested for posters / handover. */
data class Decoded(val kind: String, val summary: String, val fields: List<Pair<String, String>> = emptyList(), val nested: List<NdefRecord> = emptyList())

class NdefFormatException(msg: String) : Exception(msg)

/**
 * NDEF (NFC Forum NDEF 1.0, RTD Text / URI / Smart Poster / Handover 1.4, Android Application Record, Wi-Fi Simple Configuration,
 * Bluetooth Secure Simple Pairing OOB): parse a message into records and decode every TNF; build records and serialize a message
 * for Write. Plain Kotlin over bytes (the JVM suite round-trips it); android.nfc.NdefMessage only wraps the bytes at the radio.
 */
object Ndef {
    const val TNF_EMPTY = 0
    const val TNF_WELL_KNOWN = 1
    const val TNF_MIME = 2
    const val TNF_ABSOLUTE_URI = 3
    const val TNF_EXTERNAL = 4
    const val TNF_UNKNOWN = 5
    const val TNF_UNCHANGED = 6

    val TNF_NAMES = listOf("Empty", "Well-known (NFC Forum RTD)", "MIME media", "Absolute URI", "External (NFC Forum)", "Unknown", "Unchanged", "Reserved")

    /** URI identifier codes 0x00-0x23 (RTD URI 1.0, table 3). */
    val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
        "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
        "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    // ---- parse ----

    fun parse(bytes: ByteArray): List<NdefRecord> {
        val out = ArrayList<NdefRecord>()
        var i = 0
        var chunkHead: NdefRecord? = null
        var chunkBuf: java.io.ByteArrayOutputStream? = null
        while (i < bytes.size) {
            val h = bytes[i].toInt() and 0xFF
            val mb = h and 0x80 != 0; val me = h and 0x40 != 0; val cf = h and 0x20 != 0; val sr = h and 0x10 != 0; val il = h and 0x08 != 0
            val tnf = h and 0x07
            i++
            fun need(n: Int) { if (i + n > bytes.size) throw NdefFormatException("record truncated at byte $i") }
            need(1); val typeLen = bytes[i++].toInt() and 0xFF
            val payLen: Long = if (sr) { need(1); (bytes[i++].toInt() and 0xFF).toLong() } else { need(4); Bytes.u32be(bytes, i).also { i += 4 } }
            if (payLen > bytes.size) throw NdefFormatException("payload length $payLen exceeds the message")
            val idLen = if (il) { need(1); bytes[i++].toInt() and 0xFF } else 0
            need(typeLen); val type = bytes.copyOfRange(i, i + typeLen); i += typeLen
            need(idLen); val id = bytes.copyOfRange(i, i + idLen); i += idLen
            need(payLen.toInt()); val payload = bytes.copyOfRange(i, i + payLen.toInt()); i += payLen.toInt()
            if (out.isEmpty() && chunkHead == null && !mb) throw NdefFormatException("first record lacks the MB flag")
            if (chunkHead != null) {
                if (tnf != TNF_UNCHANGED) throw NdefFormatException("a middle / last chunk must be TNF Unchanged")
                chunkBuf!!.write(payload)
                if (!cf) {
                    val c = chunkHead
                    out += NdefRecord(c.tnf, c.type, c.id, chunkBuf.toByteArray(), c.mb, me, chunked = true, shortRecord = false)
                    chunkHead = null; chunkBuf = null
                }
            } else if (cf) {
                chunkHead = NdefRecord(tnf, type, id, ByteArray(0), mb, false)
                chunkBuf = java.io.ByteArrayOutputStream().apply { write(payload) }
            } else out += NdefRecord(tnf, type, id, payload, mb, me, false, sr)
            if (me && !cf) break
        }
        if (chunkHead != null) throw NdefFormatException("unterminated chunked record")
        return out
    }

    // ---- decode ----

    fun decode(r: NdefRecord): Decoded = runCatching { decodeOrThrow(r) }.getOrElse { Decoded(r.tnfName, "Undecodable: ${it.message}") }

    private fun decodeOrThrow(r: NdefRecord): Decoded = when (r.tnf) {
        TNF_EMPTY -> Decoded("Empty", "Empty record")
        TNF_WELL_KNOWN -> wellKnown(r.typeText, r.payload)
        TNF_MIME -> mime(r.typeText.lowercase(), r.payload)
        TNF_ABSOLUTE_URI -> Decoded("Absolute URI", r.typeText, listOf("URI" to r.typeText))
        TNF_EXTERNAL -> external(r.typeText, r.payload)
        TNF_UNKNOWN -> Decoded("Unknown", "${r.payload.size} bytes of unknown type")
        TNF_UNCHANGED -> Decoded("Unchanged", "Chunk continuation")
        else -> Decoded("Reserved", "Reserved TNF ${r.tnf}")
    }

    fun text(payload: ByteArray): Pair<String, String> {
        if (payload.isEmpty()) throw NdefFormatException("empty Text payload")
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val langLen = status and 0x3F
        if (1 + langLen > payload.size) throw NdefFormatException("language code runs past the payload")
        val lang = String(payload, 1, langLen, Charsets.US_ASCII)
        val body = payload.copyOfRange(1 + langLen, payload.size)
        return lang to String(body, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
    }

    fun uri(payload: ByteArray): String {
        if (payload.isEmpty()) throw NdefFormatException("empty URI payload")
        val code = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrNull(code) ?: throw NdefFormatException("reserved URI code 0x%02X".format(code))
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    private fun wellKnown(t: String, p: ByteArray): Decoded = when (t) {
        "T" -> text(p).let { (lang, s) -> Decoded("Text", s, listOf("Language" to lang, "Encoding" to if (p[0].toInt() and 0x80 != 0) "UTF-16" else "UTF-8", "Text" to s)) }
        "U" -> uri(p).let { Decoded("URI", it, listOf("URI" to it, "Prefix code" to "0x%02X".format(p[0].toInt() and 0xFF))) }
        "Sp" -> smartPoster(p)
        "Hs", "Hr", "Hm", "Hi" -> handover(t, p)
        "Hc" -> handoverCarrier(p)
        "ac" -> alternativeCarrier(p)
        "cr" -> Decoded("Collision resolution", "Random number 0x%04X".format(Bytes.u16be(p, 0)))
        "err" -> Decoded("Handover error", "Reason 0x%02X".format(p[0].toInt() and 0xFF))
        "Sig" -> Decoded("Signature", "Signature record, ${p.size} bytes", listOf("Version" to "0x%02X".format(p.firstOrNull()?.toInt()?.and(0xFF) ?: 0)))
        "act" -> Decoded("Action", when (p.firstOrNull()?.toInt()) { 0 -> "Do the action"; 1 -> "Save for later"; 2 -> "Open for editing"; else -> "Unknown action" })
        "s" -> Decoded("Size", "${Bytes.u32be(p, 0)} bytes")
        "t" -> Decoded("Type", String(p, Charsets.UTF_8))
        "D" -> Decoded("Device information", "${p.size} bytes")
        else -> Decoded("Well-known", "RTD \"$t\", ${p.size} bytes")
    }

    private fun smartPoster(p: ByteArray): Decoded {
        val inner = parse(p)
        val fields = ArrayList<Pair<String, String>>()
        var uri = ""
        for (r in inner) {
            val d = decode(r)
            when (d.kind) {
                "URI" -> { uri = d.summary; fields += "URI" to d.summary }
                "Text" -> fields += "Title (${d.fields.first { it.first == "Language" }.second})" to d.summary
                "Action", "Size", "Type" -> fields += d.kind to d.summary
                else -> fields += d.kind to d.summary
            }
        }
        return Decoded("Smart poster", uri.ifEmpty { "Smart poster" }, fields, inner)
    }

    private fun handover(t: String, p: ByteArray): Decoded {
        val name = mapOf("Hs" to "Handover select", "Hr" to "Handover request", "Hm" to "Handover mediation", "Hi" to "Handover initiate")[t] ?: t
        if (p.isEmpty()) return Decoded(name, "empty")
        val v = p[0].toInt() and 0xFF
        val inner = if (p.size > 1) parse(p.copyOfRange(1, p.size)) else emptyList()
        return Decoded(name, "Version ${v shr 4}.${v and 0x0F}, ${inner.size} carrier record(s)", listOf("Version" to "${v shr 4}.${v and 0x0F}"), inner)
    }

    private fun alternativeCarrier(p: ByteArray): Decoded {
        val cps = listOf("inactive", "active", "activating", "unknown")[p[0].toInt() and 0x03]
        val refLen = p[1].toInt() and 0xFF
        val ref = String(p, 2, refLen, Charsets.US_ASCII)
        return Decoded("Alternative carrier", "Carrier \"$ref\" $cps", listOf("Power state" to cps, "Carrier data reference" to ref))
    }

    private fun handoverCarrier(p: ByteArray): Decoded {
        val ctf = p[0].toInt() and 0x07
        val len = p[1].toInt() and 0xFF
        val type = String(p, 2, len, Charsets.US_ASCII)
        return Decoded("Handover carrier", type, listOf("Carrier TNF" to TNF_NAMES.getOrElse(ctf) { "?" }, "Carrier type" to type))
    }

    private fun external(t: String, p: ByteArray): Decoded =
        if (t == "android.com:pkg") Decoded("Android application record", String(p, Charsets.UTF_8), listOf("Package" to String(p, Charsets.UTF_8)))
        else Decoded("External", t, listOf("Type" to t, "Payload" to Bytes.hex(p, " ")))

    private fun mime(t: String, p: ByteArray): Decoded = when {
        t == "application/vnd.wfa.wsc" -> wifi(p)
        t == "application/vnd.bluetooth.ep.oob" -> btClassicOob(p)
        t == "application/vnd.bluetooth.le.oob" -> btLeOob(p)
        t.startsWith("text/") -> Decoded("MIME $t", String(p, Charsets.UTF_8).take(200), listOf("Type" to t, "Text" to String(p, Charsets.UTF_8)))
        else -> Decoded("MIME $t", "${p.size} bytes of $t", listOf("Type" to t))
    }

    // ---- Wi-Fi Simple Configuration (WSC 2.0 TLVs: 2-byte type, 2-byte length, big endian) ----

    private val WSC_AUTH = mapOf(0x0001 to "Open", 0x0002 to "WPA-Personal", 0x0004 to "Shared", 0x0008 to "WPA-Enterprise", 0x0010 to "WPA2-Enterprise", 0x0020 to "WPA2-Personal", 0x0022 to "WPA/WPA2-Personal")
    private val WSC_ENC = mapOf(0x0001 to "None", 0x0002 to "WEP", 0x0004 to "TKIP", 0x0008 to "AES", 0x000C to "AES/TKIP")

    fun wscTlvs(p: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 4 <= p.size) {
            val t = Bytes.u16be(p, i); val l = Bytes.u16be(p, i + 2)
            if (i + 4 + l > p.size) throw NdefFormatException("WSC attribute 0x%04X runs past the payload".format(t))
            out += t to p.copyOfRange(i + 4, i + 4 + l)
            i += 4 + l
        }
        return out
    }

    fun wifi(p: ByteArray): Decoded {
        val top = wscTlvs(p)
        val cred = top.firstOrNull { it.first == 0x100E }?.second?.let { wscTlvs(it) } ?: top
        fun v(t: Int) = cred.firstOrNull { it.first == t }?.second
        val ssid = v(0x1045)?.let { String(it, Charsets.UTF_8) } ?: ""
        val key = v(0x1027)
        val auth = v(0x1003)?.let { WSC_AUTH[Bytes.u16be(it, 0)] ?: "0x%04X".format(Bytes.u16be(it, 0)) } ?: "?"
        val enc = v(0x100F)?.let { WSC_ENC[Bytes.u16be(it, 0)] ?: "0x%04X".format(Bytes.u16be(it, 0)) } ?: "?"
        val mac = v(0x1020)?.let { Bytes.hex(it, ":") } ?: ""
        val fields = listOf("SSID" to ssid, "Authentication" to auth, "Encryption" to enc,
            // The key is the tag owner's secret: shown as its length only, never echoed into a screen or the history.
            "Network key" to (key?.let { "${it.size} characters (hidden)" } ?: "none")) + (if (mac.isNotEmpty()) listOf("MAC" to mac) else emptyList())
        return Decoded("Wi-Fi network", ssid, fields)
    }

    // ---- Bluetooth OOB ----

    private fun eir(p: ByteArray, from: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = from
        while (i < p.size) {
            val len = p[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > p.size) break
            val t = p[i + 1].toInt() and 0xFF
            val d = p.copyOfRange(i + 2, i + 1 + len)
            when (t) {
                0x08, 0x09 -> out += "Name" to String(d, Charsets.UTF_8)
                0x0D -> if (d.size == 3) out += "Class of device" to "0x%06X".format((d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8) or ((d[2].toInt() and 0xFF) shl 16))
                0x1B -> if (d.size == 7) out += "LE address" to Bytes.hex(d.copyOfRange(0, 6).reversedArray(), ":") + if (d[6].toInt() == 1) " (random)" else " (public)"
                0x1C -> out += "LE role" to when (d.firstOrNull()?.toInt()) { 0 -> "Peripheral only"; 1 -> "Central only"; 2 -> "Peripheral preferred"; 3 -> "Central preferred"; else -> "?" }
                0x03, 0x02 -> out += "Services" to (0 until d.size / 2).joinToString(", ") { "%04X".format(Bytes.u16le(d, it * 2)) }
                else -> out += "EIR 0x%02X".format(t) to Bytes.hex(d, " ")
            }
            i += 1 + len
        }
        return out
    }

    fun btClassicOob(p: ByteArray): Decoded {
        if (p.size < 8) throw NdefFormatException("Bluetooth OOB shorter than 8 bytes")
        val addr = Bytes.hex(p.copyOfRange(2, 8).reversedArray(), ":")
        val f = listOf("Address" to addr) + eir(p, 8)
        return Decoded("Bluetooth pairing", f.firstOrNull { it.first == "Name" }?.second ?: addr, f)
    }

    fun btLeOob(p: ByteArray): Decoded {
        val f = eir(p, 0)
        return Decoded("Bluetooth LE pairing", f.firstOrNull { it.first == "Name" }?.second ?: f.firstOrNull { it.first == "LE address" }?.second ?: "LE OOB", f)
    }

    // ---- build ----

    fun record(tnf: Int, type: ByteArray, payload: ByteArray, id: ByteArray = ByteArray(0)) = NdefRecord(tnf, type, id, payload)

    fun textRecord(text: String, lang: String = "en"): NdefRecord {
        val l = lang.toByteArray(Charsets.US_ASCII)
        require(l.size <= 0x3F) { "language code too long" }
        return record(TNF_WELL_KNOWN, "T".toByteArray(), byteArrayOf(l.size.toByte()) + l + text.toByteArray(Charsets.UTF_8))
    }

    /** The longest matching identifier code is used, so `https://www.x` is 0x02 + `x`. */
    fun uriRecord(uri: String): NdefRecord {
        val code = URI_PREFIXES.indices.drop(1).filter { uri.startsWith(URI_PREFIXES[it]) }.maxByOrNull { URI_PREFIXES[it].length } ?: 0
        return record(TNF_WELL_KNOWN, "U".toByteArray(), byteArrayOf(code.toByte()) + uri.substring(URI_PREFIXES[code].length).toByteArray(Charsets.UTF_8))
    }

    fun mimeRecord(type: String, payload: ByteArray) = record(TNF_MIME, type.lowercase().toByteArray(Charsets.US_ASCII), payload)
    fun externalRecord(domainType: String, payload: ByteArray) = record(TNF_EXTERNAL, domainType.lowercase().toByteArray(Charsets.US_ASCII), payload)
    fun aarRecord(pkg: String) = externalRecord("android.com:pkg", pkg.toByteArray(Charsets.UTF_8))

    fun smartPosterRecord(uri: String, title: String, lang: String = "en") =
        record(TNF_WELL_KNOWN, "Sp".toByteArray(), encode(listOf(uriRecord(uri), textRecord(title, lang))))

    /** A WSC credential (auth WPA2-Personal / Open, AES) the Android Wi-Fi stack and most readers join from. */
    fun wifiRecord(ssid: String, key: String, open: Boolean = key.isEmpty()): NdefRecord {
        fun tlv(t: Int, v: ByteArray) = byteArrayOf((t shr 8).toByte(), t.toByte(), (v.size shr 8).toByte(), v.size.toByte()) + v
        fun u16(x: Int) = byteArrayOf((x shr 8).toByte(), x.toByte())
        val cred = tlv(0x1026, byteArrayOf(1)) + tlv(0x1045, ssid.toByteArray(Charsets.UTF_8)) + tlv(0x1003, u16(if (open) 0x0001 else 0x0020)) +
            tlv(0x100F, u16(if (open) 0x0001 else 0x0008)) + tlv(0x1027, key.toByteArray(Charsets.UTF_8)) + tlv(0x1020, ByteArray(6) { 0xFF.toByte() })
        return mimeRecord("application/vnd.wfa.wsc", tlv(0x100E, cred))
    }

    /** Serialize: MB on the first record, ME on the last, short records where the payload fits a byte. */
    fun encode(records: List<NdefRecord>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (records.isEmpty()) return byteArrayOf(0xD0.toByte(), 0, 0) // one empty record: the canonical blank message
        records.forEachIndexed { i, r ->
            val sr = r.payload.size < 256
            var h = r.tnf and 0x07
            if (i == 0) h = h or 0x80
            if (i == records.lastIndex) h = h or 0x40
            if (sr) h = h or 0x10
            if (r.id.isNotEmpty()) h = h or 0x08
            out.write(h); out.write(r.type.size)
            if (sr) out.write(r.payload.size) else { val n = r.payload.size; out.write(n ushr 24); out.write(n ushr 16 and 0xFF); out.write(n ushr 8 and 0xFF); out.write(n and 0xFF) }
            if (r.id.isNotEmpty()) out.write(r.id.size)
            out.write(r.type); out.write(r.id); out.write(r.payload)
        }
        return out.toByteArray()
    }

    /** One line per record for the history and the list header. */
    fun summary(records: List<NdefRecord>): String = records.joinToString(" | ") { r -> r.decoded.let { "${it.kind}: ${it.summary}" } }
}
