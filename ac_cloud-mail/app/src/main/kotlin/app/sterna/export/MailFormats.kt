package app.sterna.export

import java.io.OutputStream

/** .eml: the raw RFC 822 source exactly as received, byte for byte - no re-encoding, no added headers. */
fun writeEml(out: OutputStream, raw: ByteArray) {
    out.write(raw)
    out.flush()
}

/**
 * .mbox (mboxrd): each message preceded by a `From ` separator line, every body line that starts with
 * `From ` (after any run of `>`) quoted with one more `>`, and a blank line after each message.
 * Line endings of the raw source are kept as they are; a message lacking a final newline gets one.
 */
fun writeMbox(
    out: OutputStream,
    messages: List<Pair<String, ByteArray>>,
    isCancelled: () -> Boolean = { false },
) {
    val w = out.buffered(64 * 1024)
    for ((fromLine, raw) in messages) {
        if (isCancelled()) throw ExportCancelled()
        w.write("From $fromLine\n".toByteArray(Charsets.ISO_8859_1))
        var start = 0
        while (start < raw.size) {
            var end = start
            while (end < raw.size && raw[end] != '\n'.code.toByte()) end++
            if (isFromLine(raw, start, end)) w.write('>'.code)
            w.write(raw, start, end - start)
            if (end < raw.size) w.write('\n'.code) else w.write("\n".toByteArray())
            start = end + 1
        }
        w.write("\n".toByteArray())
    }
    w.flush()
}

private fun isFromLine(b: ByteArray, s: Int, e: Int): Boolean {
    var i = s
    while (i < e && b[i] == '>'.code.toByte()) i++
    val tag = "From ".toByteArray()
    if (e - i < tag.size) return false
    return tag.indices.all { b[i + it] == tag[it] }
}

/** The mbox separator's "sender date" text: `MAILER-DAEMON` plus the asctime form of the arrival day. */
fun mboxFromLine(dateIso: String?): String {
    val d = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US)
            .format(java.time.Instant.parse(dateIso).atOffset(java.time.ZoneOffset.UTC))
    }.getOrDefault("Thu Jan 1 00:00:00 1970")
    return "MAILER-DAEMON $d"
}
