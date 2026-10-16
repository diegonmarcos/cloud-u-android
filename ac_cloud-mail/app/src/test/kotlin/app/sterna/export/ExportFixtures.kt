package app.sterna.export

import java.io.ByteArrayInputStream

internal val LABELS = ExportLabels("From:", "To:", "Cc:", "Date:")

internal fun part(name: String?, bytes: ByteArray, type: String = "application/octet-stream", cid: String? = null, inline: Boolean = false) =
    ExportPart(name, type, cid, inline, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

internal fun message(
    parts: List<ExportPart> = emptyList(),
    body: String = "<p>Héllo wörld</p><img src=\"cid:logo@x\">",
    subject: String = "Rapport d'été",
) = ExportMessage(
    subject = subject, from = "Alex <alex@example.org>", to = "me@example.org", cc = "",
    dateIso = "2026-09-24T10:00:00Z", messageId = "abc@example.org", bodyHtml = body, parts = parts,
)

/** A tiny MIME splitter for assertions: the top header block and the parts between boundaries. */
internal class Mime(val topHeaders: String, val boundary: String, val parts: List<Pair<Map<String, String>, String>>)

internal fun parseMime(raw: String): Mime {
    val top = raw.substringBefore("\r\n\r\n")
    val boundary = Regex("boundary=\"([^\"]+)\"").find(top)!!.groupValues[1]
    val chunks = raw.split("--$boundary").drop(1).filter { !it.startsWith("--") }
    val parts = chunks.map { c ->
        val body = c.removePrefix("\r\n")
        val head = body.substringBefore("\r\n\r\n")
        val content = body.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        val h = head.split("\r\n").associate { it.substringBefore(": ").lowercase() to it.substringAfter(": ") }
        h to content
    }
    return Mime(top, boundary, parts)
}

internal fun decodeQp(s: String): String {
    val joined = s.replace("=\r\n", "")
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < joined.length) {
        val c = joined[i]
        if (c == '=' ) { out.write(joined.substring(i + 1, i + 3).toInt(16)); i += 3 } else { out.write(c.code); i++ }
    }
    return out.toString(Charsets.UTF_8)
}
