package app.sterna.core.imap

import java.util.Base64

    /**
     * Modified UTF-7, the encoding IMAP mailbox names travel in (RFC 3501 §5.1.3). A folder called
     */

/** The base64 alphabet of modified UTF-7: standard, with `,` where base64 has `/`. */
private const val MODIFIED_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

/** Printable US-ASCII represents itself; everything else is shifted into base64. */
private fun Char.isDirect(): Boolean = code in 0x20..0x7E && this != '&'

private fun Char.isModifiedBase64(): Boolean = this in MODIFIED_BASE64

    /**
     * A Unicode mailbox name as it must appear on the wire. Pure ASCII (bar `&`) comes back untouched,
     */
fun encodeModifiedUtf7(name: String): String {
    if (name.all { it.isDirect() }) return name
    val out = StringBuilder(name.length + 8)
    var i = 0
    while (i < name.length) {
        val c = name[i]
        when {
            c == '&' -> { out.append("&-"); i++ }
            c.isDirect() -> { out.append(c); i++ }
            else -> {
                val start = i
                while (i < name.length && !name[i].isDirect() && name[i] != '&') i++
                out.append('&').append(base64Utf16(name, start, i)).append('-')
            }
        }
    }
    return out.toString()
}

    /**
     * A mailbox name as `LIST` announced it, turned back into Unicode. A malformed shift sequence
     */
fun decodeModifiedUtf7(name: String): String {
    if ('&' !in name) return name
    val out = StringBuilder(name.length)
    var i = 0
    while (i < name.length) {
        val c = name[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        var end = i + 1
        while (end < name.length && name[end].isModifiedBase64()) end++
        val chunk = name.substring(i + 1, end)
        val next = if (end < name.length && name[end] == '-') end + 1 else end
        when {
            // "&-" is a literal ampersand; a trailing "&" is one too.
            chunk.isEmpty() -> out.append('&')
            else -> {
                val decoded = decodeBase64Utf16(chunk)
                if (decoded == null) out.append(name, i, next) else out.append(decoded)
            }
        }
        i = next
    }
    return out.toString()
}

    /**
     * A mailbox path as the app will hold it: the decoding when it is faithful, the RAW WIRE FORM when
     */
fun decodeMailboxPath(raw: String): String {
    val decoded = decodeModifiedUtf7(raw)
    return if (encodeModifiedUtf7(decoded) == raw) decoded else raw
}

/** UTF-16BE bytes of `name[from until to]`, base64 without padding, `/` written as `,`. */
private fun base64Utf16(name: String, from: Int, to: Int): String {
    val bytes = ByteArray((to - from) * 2)
    var b = 0
    for (i in from until to) {
        val code = name[i].code
        bytes[b++] = (code shr 8).toByte()
        bytes[b++] = (code and 0xFF).toByte()
    }
    return Base64.getEncoder().withoutPadding().encodeToString(bytes).replace('/', ',')
}

/** The text of one shift sequence, or null when it is not decodable (caller keeps it verbatim). */
private fun decodeBase64Utf16(chunk: String): String? {
    // A base64 run of length %4 == 1 cannot exist; padding is omitted on the wire, so add it back.
    val pad = when (chunk.length % 4) {
        0 -> ""
        2 -> "=="
        3 -> "="
        else -> return null
    }
    val bytes = runCatching {
        Base64.getDecoder().decode(chunk.replace(',', '/') + pad)
    }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size % 2 != 0) return null
    val out = StringBuilder(bytes.size / 2)
    var i = 0
    while (i < bytes.size) {
        out.append(((bytes[i].toInt() and 0xFF) shl 8 or (bytes[i + 1].toInt() and 0xFF)).toChar())
        i += 2
    }
    return out.toString()
}
