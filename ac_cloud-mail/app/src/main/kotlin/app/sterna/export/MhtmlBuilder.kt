package app.sterna.export

import java.io.OutputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val CRLF = "\r\n"

/**
 * The whole message as ONE multipart/related (RFC 2557) document: top-level headers, then the HTML
 * page (QP, UTF-8), then every inline image (Content-ID + Content-Location `cid:`) and every attachment
 * (Content-Disposition: attachment, original filename), each base64 and STREAMED in 76-column lines.
 *
 * The boundary starts `=_`: base64 has no `_` and quoted-printable writes every `=` as `=3D`, so it
 * cannot occur in a body part. Header lines are ours and contain no such text.
 */
fun writeMhtml(
    out: OutputStream,
    msg: ExportMessage,
    labels: ExportLabels,
    boundary: String = newBoundary(),
    progress: ExportProgress = ExportProgress.NONE,
    isCancelled: () -> Boolean = { false },
) {
    val w = out.buffered(64 * 1024)
    fun put(s: String) = w.write(s.toByteArray(Charsets.ISO_8859_1))
    put("MIME-Version: 1.0$CRLF")
    put(headerLine("From", msg.from))
    put(headerLine("To", msg.to))
    if (msg.cc.isNotBlank()) put(headerLine("Cc", msg.cc))
    put(headerLine("Subject", msg.subject))
    rfc2822(msg.dateIso)?.let { put("Date: $it$CRLF") }
    msg.messageId?.takeIf { it.isNotBlank() }?.let { put("Message-ID: ${it.trim().let { id -> if (id.startsWith("<")) id else "<$id>" }}$CRLF") }
    put("Content-Type: multipart/related; type=\"text/html\"; boundary=\"$boundary\"$CRLF$CRLF")
    put("This is a multi-part message in MIME format.$CRLF")

    put("--$boundary$CRLF")
    put("Content-Type: text/html; charset=\"utf-8\"$CRLF")
    put("Content-Transfer-Encoding: quoted-printable$CRLF")
    put("Content-Location: message.html$CRLF$CRLF")
    put(quotedPrintable(exportDocument(msg, labels)))
    put(CRLF)

    val total = msg.parts.sumOf { it.size }
    var done = 0L
    val names = dedupeNames(msg.parts.map { it.name })
    val chunk = ByteArray(Base64Lines.CHUNK)
    msg.parts.forEachIndexed { i, part ->
        if (isCancelled()) throw ExportCancelled()
        val name = names[i]
        put("--$boundary$CRLF")
        put("Content-Type: ${part.type.ifBlank { "application/octet-stream" }}; name=\"${headerSafe(name)}\"$CRLF")
        put("Content-Transfer-Encoding: base64$CRLF")
        val cid = part.cid?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() }
        if (cid != null) {
            put("Content-ID: <$cid>$CRLF")
            put("Content-Location: cid:$cid$CRLF")
        } else {
            put("Content-Location: attachment/${headerSafe(name)}$CRLF")
        }
        // RFC 2231 `filename*` carries the real (non-ASCII) name; the quoted ASCII shadow is for old readers.
        val star = if (name.any { it.code > 126 || it.code < 32 }) "; filename*=UTF-8''" + pctEncode(name) else ""
        put("Content-Disposition: ${if (part.inline && cid != null) "inline" else "attachment"}; filename=\"${headerSafe(name)}\"$star$CRLF$CRLF")
        part.open().use { input ->
            val enc = Base64Lines(w)
            copyChunked(input, chunk, isCancelled) { buf, n ->
                enc.write(buf, n)
                done += n
                progress.update(done, total)
            }
            enc.finish()
        }
    }
    put("--$boundary--$CRLF")
    w.flush()
}

fun newBoundary(): String {
    val b = ByteArray(12).also { SecureRandom().nextBytes(it) }
    return "=_Mhtml_" + b.joinToString("") { "%02x".format(it) }
}

/** RFC 2822 date for a header from an ISO-8601 instant; null when there is none or it does not parse. */
fun rfc2822(iso: String?): String? = runCatching {
    DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.parse(iso).atOffset(ZoneOffset.UTC))
}.getOrNull()

/** `Name: value` with RFC 2047 encoding (and CRLF folding) when the value is not plain ASCII. */
fun headerLine(name: String, value: String): String {
    val v = value.replace("\r", " ").replace("\n", " ").trim()
    if (v.all { it.code in 32..126 }) return "$name: $v$CRLF"
    val words = encodedWords(v)
    return "$name: " + words.joinToString("$CRLF ") + CRLF
}

private fun encodedWords(s: String): List<String> {
    val out = ArrayList<String>()
    val cur = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
        if (cur.size() + bytes.size > 42) { out += encodedWord(cur.toByteArray()); cur.reset() }
        cur.write(bytes)
        i += Character.charCount(cp)
    }
    if (cur.size() > 0) out += encodedWord(cur.toByteArray())
    return out
}

private fun encodedWord(b: ByteArray) =
    "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(b) + "?="

internal fun pctEncode(s: String): String = s.toByteArray(Charsets.UTF_8).joinToString("") { b ->
    val v = b.toInt() and 0xFF
    if (v.toChar().isLetterOrDigit() && v < 128 || v.toChar() in "-._~") v.toChar().toString() else "%%%02X".format(v)
}

/** A value safe inside a quoted header parameter: no quotes, backslashes or line breaks; non-ASCII as `_`-free RFC 2047 is not valid in params, so the ASCII shadow is kept. */
internal fun headerSafe(s: String): String =
    s.map { c -> if (c == '"' || c == '\\' || c.code < 32 || c.code > 126) '_' else c }.joinToString("")

/** Quoted-printable (RFC 2045) of [text] as UTF-8, CRLF hard breaks, soft breaks before column 76. */
fun quotedPrintable(text: String): String {
    val sb = StringBuilder()
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    lines.forEachIndexed { idx, line ->
        var col = 0
        val bytes = line.toByteArray(Charsets.UTF_8)
        for ((j, b) in bytes.withIndex()) {
            val v = b.toInt() and 0xFF
            val last = j == bytes.size - 1
            val literal = (v in 33..126 && v != '='.code) || ((v == ' '.code || v == '\t'.code) && !last)
            val tok = if (literal) v.toChar().toString() else "=%02X".format(v)
            if (col + tok.length > 75) { sb.append("=").append(CRLF); col = 0 }
            sb.append(tok); col += tok.length
        }
        if (idx < lines.size - 1) sb.append(CRLF)
    }
    return sb.toString()
}

/** Streaming base64 writer: 57 input bytes become one 76-column line. Holds at most 56 bytes back. */
internal class Base64Lines(private val out: OutputStream) {
    private val pending = ByteArray(57)
    private var n = 0
    private val enc = java.util.Base64.getEncoder()

    fun write(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val take = minOf(57 - n, len - off)
            System.arraycopy(buf, off, pending, n, take)
            n += take; off += take
            if (n == 57) { flushLine() }
        }
    }

    fun finish() { if (n > 0) flushLine() }

    private fun flushLine() {
        out.write(enc.encode(pending.copyOf(n)))
        out.write(CRLF.toByteArray())
        n = 0
    }

    companion object { const val CHUNK = 57 * 1024 }
}
