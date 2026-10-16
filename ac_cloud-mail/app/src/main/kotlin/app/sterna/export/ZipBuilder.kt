package app.sterna.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File names for a set of attachments: each cleaned of path separators and other hostile characters
 * (no zip-slip), a missing name replaced by "attachment", and clashes (case-insensitively) numbered
 * before the extension: `a.pdf`, `a (2).pdf`, `a (3).pdf`. Order is kept.
 */
fun dedupeNames(names: List<String?>): List<String> {
    val taken = HashSet<String>()
    return names.map { raw ->
        val base = (raw ?: "").replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.').ifEmpty { "attachment" }
        var candidate = base
        var k = 2
        while (!taken.add(candidate.lowercase())) {
            val dot = base.lastIndexOf('.')
            candidate = if (dot > 0) base.substring(0, dot) + " ($k)" + base.substring(dot) else "$base ($k)"
            k++
        }
        candidate
    }
}

/** All [parts] into one zip, STREAMED entry by entry in 64 KiB chunks, original names, deduped. */
fun writeAttachmentsZip(
    out: OutputStream,
    parts: List<ExportPart>,
    progress: ExportProgress = ExportProgress.NONE,
    isCancelled: () -> Boolean = { false },
) {
    val names = dedupeNames(parts.map { it.name })
    val total = parts.sumOf { it.size }
    var done = 0L
    val chunk = ByteArray(64 * 1024)
    ZipOutputStream(out.buffered(64 * 1024)).use { zip ->
        parts.forEachIndexed { i, part ->
            if (isCancelled()) throw ExportCancelled()
            zip.putNextEntry(ZipEntry(names[i]))
            part.open().use { input ->
                copyChunked(input, chunk, isCancelled) { buf, n ->
                    zip.write(buf, 0, n); done += n; progress.update(done, total)
                }
            }
            zip.closeEntry()
        }
    }
}
