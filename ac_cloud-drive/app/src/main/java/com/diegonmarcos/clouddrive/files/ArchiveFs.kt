package com.diegonmarcos.clouddrive.files

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * #579 a zip browsed AS A FOLDER (X-plore's defining trick). [ZipFile] reads the
 * central directory only, so entering a 2 GB archive costs one seek, not a read;
 * folders that the archive never wrote as entries are synthesised from entry
 * paths, so `a/b/c.txt` alone still shows `a` › `b` › `c.txt`. Pure java.util.zip;
 * JVM-tested (ArchivePathTest) against archives written on the spot.
 */
object ArchiveFs {

    /** The entries directly under [location] inside its zip, folders first by the caller's sort. */
    @Throws(IOException::class)
    fun list(location: Location.Archive, mimes: FileOps.MimeResolver): List<FileOps.Entry> {
        val zip = File(location.zipPath)
        if (!zip.isFile) throw IOException("archive is gone: ${zip.name}")
        val prefix = location.prefix
        val folders = LinkedHashMap<String, Int>()
        val files = ArrayList<FileOps.Entry>()
        ZipFile(zip).use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val name = e.name.replace('\\', '/')
                if (!name.startsWith(prefix) || name.length == prefix.length) continue
                val rest = name.substring(prefix.length)
                val slash = rest.indexOf('/')
                if (slash >= 0) {
                    val folder = rest.substring(0, slash)
                    if (folder.isNotEmpty() && folder != "." && folder != "..") folders[folder] = (folders[folder] ?: 0) + (if (rest.length > slash + 1) 1 else 0)
                } else if (!e.isDirectory) {
                    if (rest == "." || rest == "..") continue
                    val ext = FileOps.extensionOf(rest)
                    files += FileOps.Entry(
                        location = location.child(rest), name = rest, isDirectory = false,
                        size = e.size.coerceAtLeast(0L), modified = e.time, mime = mimes.mimeOf(ext) ?: FileOps.OCTET_MIME,
                        extension = ext, hidden = rest.startsWith("."), compressedSize = e.compressedSize.takeIf { it >= 0 },
                    )
                }
            }
        }
        val dirs = folders.map { (folder, count) ->
            FileOps.Entry(location = location.child(folder), name = folder, isDirectory = true, size = 0L, modified = zip.lastModified(), mime = FileOps.DIRECTORY_MIME, extension = "", hidden = folder.startsWith("."), childCount = count)
        }
        return dirs + files
    }

    /** The entry-name prefixes a selection of archive locations covers, for [FileOps.extract]'s `onlyUnder`. */
    fun prefixesOf(selected: Collection<Location.Archive>): Set<String> = selected.map { it.inner }.filter { it.isNotEmpty() }.toSet()

    fun isArchiveName(name: String, archiveExtensions: Set<String>): Boolean = FileOps.extensionOf(name) in archiveExtensions
}
