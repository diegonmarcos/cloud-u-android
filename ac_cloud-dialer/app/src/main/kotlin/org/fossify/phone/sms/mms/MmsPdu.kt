package org.fossify.phone.sms.mms

import java.io.ByteArrayOutputStream

/** One body part of an MMS: a type, a file name, its bytes. */
class MmsPart(val contentType: String, val name: String, val data: ByteArray) {
    val isText: Boolean get() = contentType.startsWith("text/plain")
    val isImage: Boolean get() = contentType.startsWith("image/")
}

/** What an MMS PDU says, decoded. Absent headers stay null / empty. */
class MmsPdu(
    val messageType: Int,
    val transactionId: String?,
    val from: String?,
    val to: List<String>,
    val subject: String?,
    val dateSeconds: Long?,
    val contentLocation: String?,
    val size: Long?,
    val parts: List<MmsPart>,
) {
    val isNotification: Boolean get() = messageType == M_NOTIFICATION_IND
    val isRetrieveConf: Boolean get() = messageType == M_RETRIEVE_CONF
    val text: String get() = parts.filter { it.isText }.joinToString("") { it.data.toString(Charsets.UTF_8) }

    companion object {
        const val M_SEND_REQ = 0x80
        const val M_SEND_CONF = 0x81
        const val M_NOTIFICATION_IND = 0x82
        const val M_NOTIFYRESP_IND = 0x83
        const val M_RETRIEVE_CONF = 0x84
        const val M_ACKNOWLEDGE_IND = 0x85
    }
}

/**
 * A compact OMA MMS 1.2 encoder and decoder (OMA-TS-MMS_ENC): enough to read an incoming
 * notification and a retrieved message, and to compose a send request carrying text and images.
 * Pure bytes in, pure bytes out, so the round trip is a JVM unit test.
 *
 * Header fields are the well-known ones MMSCs actually use; a header this decoder does not know
 * ends the header scan (the fields read so far stand) rather than guessing at its width.
 */
object MmsCodec {
    // header field codes (0x80 | code on the wire)
    private const val H_MESSAGE_TYPE = 0x0C
    private const val H_TRANSACTION_ID = 0x18
    private const val H_VERSION = 0x0D
    private const val H_DATE = 0x05
    private const val H_FROM = 0x09
    private const val H_TO = 0x17
    private const val H_CC = 0x02
    private const val H_BCC = 0x01
    private const val H_SUBJECT = 0x16
    private const val H_CONTENT_TYPE = 0x04
    private const val H_MESSAGE_CLASS = 0x0A
    private const val H_PRIORITY = 0x0F
    private const val H_DELIVERY_REPORT = 0x06
    private const val H_READ_REPORT = 0x10
    private const val H_MESSAGE_SIZE = 0x0E
    private const val H_CONTENT_LOCATION = 0x03
    private const val H_EXPIRY = 0x08
    private const val H_DELIVERY_TIME = 0x07
    private const val H_RETRIEVE_STATUS = 0x19
    private const val H_RESPONSE_STATUS = 0x12
    private const val H_MESSAGE_ID = 0x0B

    private val WELL_KNOWN = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain", 0x06 to "text/x-vCalendar",
        0x07 to "text/x-vCard", 0x0B to "multipart/*", 0x0C to "multipart/mixed", 0x0F to "multipart/alternative",
        0x1C to "image/*", 0x1D to "image/gif", 0x1E to "image/jpeg", 0x1F to "image/tiff", 0x20 to "image/png",
        0x21 to "image/vnd.wap.wbmp", 0x23 to "application/vnd.wap.multipart.mixed",
        0x26 to "application/vnd.wap.multipart.alternative", 0x33 to "application/vnd.wap.multipart.related",
    )

    private class Reader(val b: ByteArray, var pos: Int = 0) {
        val left: Int get() = b.size - pos
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun peek(): Int = b[pos].toInt() and 0xFF
        fun uintvar(): Int {
            var v = 0
            while (true) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F)
                if (c and 0x80 == 0) return v
            }
        }
        fun cstring(): String {
            val start = pos
            while (b[pos].toInt() != 0) pos++
            val s = String(b, start, pos - start, Charsets.UTF_8)
            pos++ // the terminator
            return s
        }
        fun longInt(): Long {
            val n = u8()
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }
        fun valueLength(): Int {
            val c = u8()
            return if (c <= 30) c else uintvar() // 31 = a uintvar follows
        }
    }

    /** An "encoded string value": a plain text string, or a length, a charset, then the text. */
    private fun Reader.encodedString(): String {
        val c = peek()
        if (c >= 0x20 || c == 0) return cstring().removePrefix("\u007F")
        val len = valueLength()
        val end = pos + len
        // the charset: a short integer, or a long integer; the text is UTF-8 either way
        if (peek() >= 0x80) pos++ else if (peek() in 1..30) longInt()
        val text = if (pos < end) cstring() else ""
        pos = end
        return text
    }

    private fun address(raw: String): String = raw.substringBefore("/TYPE=")

    /** A content type value: a well-known short integer, a text string, or a length + either + parameters. */
    private fun Reader.contentType(): String {
        val c = peek()
        if (c >= 0x80) { pos++; return WELL_KNOWN[c and 0x7F] ?: "application/octet-stream" }
        if (c >= 0x20) return cstring()
        val len = valueLength()
        val end = pos + len
        val type = if (pos < end && peek() >= 0x80) { WELL_KNOWN[u8() and 0x7F] ?: "application/octet-stream" } else if (pos < end) cstring() else "application/octet-stream"
        pos = end // parameters (charset, name, start, type) are not needed: text is read as UTF-8
        return type
    }

    /** Decodes [bytes]; null if it is not a readable MMS PDU. */
    fun decode(bytes: ByteArray): MmsPdu? = try {
        decodeUnsafe(bytes)
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: NegativeArraySizeException) {
        null
    }

    private fun decodeUnsafe(bytes: ByteArray): MmsPdu? {
        val r = Reader(bytes)
        var type = -1
        var tx: String? = null
        var from: String? = null
        val to = ArrayList<String>()
        var subject: String? = null
        var date: Long? = null
        var location: String? = null
        var size: Long? = null
        var hasBody = false
        scan@ while (r.left > 0) {
            val key = r.u8()
            if (key < 0x80) break
            when (key and 0x7F) {
                H_MESSAGE_TYPE -> type = r.u8()
                H_TRANSACTION_ID -> tx = r.cstring()
                H_VERSION, H_PRIORITY, H_DELIVERY_REPORT, H_READ_REPORT, H_RETRIEVE_STATUS, H_RESPONSE_STATUS -> r.u8()
                H_MESSAGE_CLASS -> if (r.peek() >= 0x80) r.u8() else r.cstring()
                H_DATE -> date = r.longInt()
                H_FROM -> {
                    val len = r.valueLength()
                    val end = r.pos + len
                    if (r.u8() == 0x80) from = address(r.encodedString())
                    r.pos = end
                }
                H_TO, H_CC, H_BCC -> to.add(address(r.encodedString()))
                H_SUBJECT -> subject = r.encodedString()
                H_MESSAGE_SIZE -> size = r.longInt()
                H_CONTENT_LOCATION -> location = r.cstring()
                H_MESSAGE_ID -> r.cstring()
                H_EXPIRY, H_DELIVERY_TIME -> { val len = r.valueLength(); r.pos += len }
                H_CONTENT_TYPE -> { r.contentType(); hasBody = true; break@scan }
                else -> break@scan
            }
        }
        if (type < 0) return null
        val parts = if (hasBody) parts(r) else emptyList()
        return MmsPdu(type, tx, from, to, subject, date, location, size, parts)
    }

    private fun parts(r: Reader): List<MmsPart> {
        val out = ArrayList<MmsPart>()
        val count = r.uintvar()
        repeat(count) {
            val headersLen = r.uintvar()
            val dataLen = r.uintvar()
            val headersEnd = r.pos + headersLen
            val type = r.contentType()
            var name = ""
            var cid = ""
            while (r.pos < headersEnd) {
                val key = r.u8()
                if (key < 0x80) { r.pos = headersEnd; break }
                when (key and 0x7F) {
                    0x0E -> name = r.cstring()                         // Content-Location
                    0x40 -> cid = r.cstring().removePrefix("\"")      // Content-ID
                    else -> r.pos = headersEnd
                }
            }
            r.pos = headersEnd
            val data = r.b.copyOfRange(r.pos, r.pos + dataLen)
            r.pos += dataLen
            out.add(MmsPart(type, name.ifEmpty { cid.trim('<', '>') }, data))
        }
        return out
    }

    // ---- encoding ---------------------------------------------------------------------------

    private fun ByteArrayOutputStream.uintvar(value: Int) {
        val groups = ArrayList<Int>()
        var v = value
        do { groups.add(v and 0x7F); v = v ushr 7 } while (v > 0)
        for (i in groups.indices.reversed()) write(if (i > 0) groups[i] or 0x80 else groups[i])
    }

    private fun ByteArrayOutputStream.text(s: String) {
        write(s.toByteArray(Charsets.UTF_8)); write(0)
    }

    private fun ByteArrayOutputStream.header(code: Int, value: ByteArray) { write(0x80 or code); write(value) }

    private fun cstr(s: String): ByteArray = s.toByteArray(Charsets.UTF_8) + 0.toByte()

    private fun typeBytes(contentType: String): ByteArray = when (contentType) {
        "image/jpeg" -> byteArrayOf(0x9E.toByte())
        "image/png" -> byteArrayOf(0xA0.toByte())
        "image/gif" -> byteArrayOf(0x9D.toByte())
        "text/plain" -> byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte()) // text/plain; charset=utf-8
        else -> cstr(contentType)
    }

    /**
     * An m-send-req for [to] carrying [parts] (text and images). From is the insert-address token, so
     * the MMSC fills in the sender's own number.
     */
    fun encodeSendReq(transactionId: String, to: List<String>, subject: String?, parts: List<MmsPart>): ByteArray {
        val out = ByteArrayOutputStream()
        out.header(H_MESSAGE_TYPE, byteArrayOf(MmsPdu.M_SEND_REQ.toByte()))
        out.header(H_TRANSACTION_ID, cstr(transactionId))
        out.header(H_VERSION, byteArrayOf(0x92.toByte()))                      // MMS 1.2
        out.header(H_FROM, byteArrayOf(0x01, 0x81.toByte()))                   // insert-address-token
        for (t in to) out.header(H_TO, cstr(if (t.contains('@')) t else "$t/TYPE=PLMN"))
        if (!subject.isNullOrEmpty()) out.header(H_SUBJECT, cstr(subject))
        out.header(H_MESSAGE_CLASS, byteArrayOf(0x80.toByte()))                // personal
        out.header(H_PRIORITY, byteArrayOf(0x81.toByte()))                     // normal
        out.header(H_DELIVERY_REPORT, byteArrayOf(0x81.toByte()))              // no
        out.header(H_READ_REPORT, byteArrayOf(0x81.toByte()))                  // no
        out.header(H_CONTENT_TYPE, byteArrayOf(0xA3.toByte()))                 // application/vnd.wap.multipart.mixed
        out.uintvar(parts.size)
        for (p in parts) {
            val headers = ByteArrayOutputStream()
            headers.write(typeBytes(p.contentType))
            if (p.name.isNotEmpty()) {
                headers.write(0x8E); headers.text(p.name)                       // Content-Location
                headers.write(0xC0); headers.write('"'.code); headers.text("<${p.name}>") // Content-ID
            }
            val h = headers.toByteArray()
            out.uintvar(h.size)
            out.uintvar(p.data.size)
            out.write(h)
            out.write(p.data)
        }
        return out.toByteArray()
    }
}
