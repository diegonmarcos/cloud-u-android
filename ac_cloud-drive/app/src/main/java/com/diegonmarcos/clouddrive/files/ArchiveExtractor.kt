package com.diegonmarcos.clouddrive.files

import com.github.junrar.Archive
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Row menu ▸ Unzip…: every archive type the phone can open with a pure-JVM reader, offline and
 * bundled (java.util.zip for zip/jar/apk, commons-compress + xz for tar, tar.gz, tar.xz and 7z,
 * junrar for rar). One walk per format feeds two passes:
 *
 *  1. VALIDATE — every entry name goes through [zipEntryTarget] (the Zip-Slip guard shared with
 *     [FileOps.extract]) and the free-space check BEFORE the first byte is written, so a hostile
 *     archive is refused whole instead of half extracted.
 *  2. WRITE — files stream to disk with progress in bytes and a cancel check on every buffer; a
 *     failure or cancel removes what this pass wrote.
 *
 * Links, devices and other special entries are skipped, never created: a symlink entry is how a
 * later entry would otherwise be written through it to somewhere outside the destination.
 * Pure JVM on purpose, so ArchiveExtractorTest runs the real thing on the CI runner.
 */
object ArchiveExtractor {

    enum class Kind(val label: String) { ZIP("zip"), TAR("tar"), TAR_GZ("tar.gz"), TAR_XZ("tar.xz"), SEVEN_Z("7z"), RAR("rar") }

    class Result(val files: Int, val bytes: Long, val skipped: Int)

    /** Longest suffix first, so `a.tar.gz` is a tar.gz and not a ".gz". */
    private val SUFFIXES: List<Pair<String, Kind>> = listOf(
        ".tar.gz" to Kind.TAR_GZ, ".tgz" to Kind.TAR_GZ,
        ".tar.xz" to Kind.TAR_XZ, ".txz" to Kind.TAR_XZ,
        ".tar" to Kind.TAR,
        ".zip" to Kind.ZIP, ".jar" to Kind.ZIP, ".apk" to Kind.ZIP,
        ".7z" to Kind.SEVEN_Z,
        ".rar" to Kind.RAR,
    )

    /** The archive type a file name says it is, or null: the menu shows "Unzip…" only when this is non-null. */
    fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return SUFFIXES.firstOrNull { n.endsWith(it.first) && n.length > it.first.length }?.second
    }

    /** `photos.tar.gz` → `photos`: the sibling folder Unzip… proposes. */
    fun defaultFolderName(name: String): String {
        val n = name.lowercase()
        val hit = SUFFIXES.firstOrNull { n.endsWith(it.first) && n.length > it.first.length }
        return if (hit == null) name.substringBeforeLast('.', name) else name.substring(0, name.length - hit.first.length)
    }

    private class Item(val name: String, val isDirectory: Boolean, val size: Long, val special: Boolean)

    private fun interface Source { fun copyTo(out: OutputStream) }

    private fun interface Visitor { fun visit(item: Item, source: Source) }

    @Throws(IOException::class)
    fun extract(
        archive: File,
        destination: File,
        progress: FileOps.Progress,
        cancelled: () -> Boolean,
        freeBytes: Long = Long.MAX_VALUE,
    ): Result {
        val kind = kindOf(archive.name) ?: throw IOException("not an archive type this app can open: ${archive.name}")
        if (!archive.isFile) throw IOException("archive is gone: ${archive.name}")

        // Pass 1: names and sizes only. Nothing is written until every entry has been vetted.
        var total = 0L
        walk(archive, kind) { item, _ ->
            if (cancelled()) throw IOException("cancelled")
            if (!item.special && zipEntryTarget(destination, item.name) == null) {
                throw IOException("the archive tries to write outside the destination: ${item.name}")
            }
            if (!item.special && !item.isDirectory) total += item.size.coerceAtLeast(0L)
        }
        if (total > freeBytes) throw FileOps.NotEnoughSpace(total, freeBytes)

        val createdDestination = !destination.exists()
        destination.mkdirs()
        if (!destination.isDirectory) throw IOException("not a folder: ${destination.name}")

        // Pass 2: write.
        val written = ArrayList<File>()
        val madeDirs = ArrayList<File>()
        var files = 0
        var skipped = 0
        var done = 0L
        try {
            walk(archive, kind) { item, source ->
                if (cancelled()) throw IOException("cancelled")
                if (item.special) { skipped++; return@walk }
                val target = zipEntryTarget(destination, item.name) ?: throw IOException("the archive tries to write outside the destination: ${item.name}")
                if (item.isDirectory) {
                    if (!target.exists() && target.mkdirs()) madeDirs += target
                    return@walk
                }
                target.parentFile?.let { p -> if (!p.exists() && p.mkdirs()) madeDirs += p }
                val existed = target.exists()
                FileOutputStream(target).use { fos ->
                    if (!existed) written += target
                    source.copyTo(object : OutputStream() {
                        override fun write(b: Int) { fos.write(b); done++ }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (cancelled()) throw IOException("cancelled")
                            fos.write(b, off, len); done += len
                            progress.report(done, total, target.name)
                        }
                    })
                }
                files++
                progress.report(done, total, target.name)
            }
        } catch (e: Exception) {
            written.reversed().forEach { it.delete() }
            madeDirs.sortedByDescending { it.path.length }.forEach { it.delete() }
            if (createdDestination) destination.delete() // only succeeds while empty
            throw if (e is IOException) e else IOException(e.message ?: "the extraction did not finish")
        }
        return Result(files, done, skipped)
    }

    // ── one walk per format ──────────────────────────────────────────────────

    private fun walk(archive: File, kind: Kind, visitor: Visitor) {
        when (kind) {
            Kind.ZIP -> ZipFile(archive).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    visitor.visit(Item(e.name, e.isDirectory, e.size, special = false)) { out -> z.getInputStream(e).use { it.copyTo(out, BUFFER) } }
                }
            }
            Kind.TAR, Kind.TAR_GZ, Kind.TAR_XZ -> {
                val raw = BufferedInputStream(FileInputStream(archive), BUFFER)
                val plain: InputStream = try {
                    when (kind) {
                        Kind.TAR_GZ -> GZIPInputStream(raw, BUFFER)
                        Kind.TAR_XZ -> XZCompressorInputStream(raw)
                        else -> raw
                    }
                } catch (e: IOException) { raw.close(); throw e }
                TarArchiveInputStream(plain).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        val special = e.isSymbolicLink || e.isLink || !(e.isFile || e.isDirectory)
                        visitor.visit(Item(e.name, e.isDirectory, e.size, special)) { out -> tar.copyTo(out, BUFFER) }
                    }
                }
            }
            Kind.SEVEN_Z -> SevenZFile(archive).use { sz ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val e = sz.nextEntry ?: break
                    visitor.visit(Item(e.name, e.isDirectory, e.size, special = false)) { out ->
                        while (true) { val n = sz.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    }
                }
            }
            Kind.RAR -> Archive(archive).use { rar ->
                while (true) {
                    val h = rar.nextFileHeader() ?: break
                    if (h.isEncrypted) throw IOException("password-protected archives are not supported: ${h.fileName}")
                    visitor.visit(Item(h.fileName, h.isDirectory, h.fullUnpackSize, special = false)) { out -> rar.extractFile(h, out) }
                }
            }
        }
    }

    private const val BUFFER = 64 * 1024
}
