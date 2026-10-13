package com.diegonmarcos.clouddrive.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manage ▸ Data: every listed extension lands in its subtype, MIME covers the rest, and the filter's rules. */
class FileClassifierTest {

    private val expected: Map<DataKind, List<String>> = mapOf(
        DataKind.VIDEOS to listOf("mp4", "mkv", "webm", "mov", "avi", "3gp"),
        DataKind.PICTURES to listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg", "dng"),
        DataKind.MUSIC to listOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav"),
        DataKind.PDF to listOf("pdf"),
        DataKind.OFFICE to listOf("xlsx", "docx", "pptx", "doc", "xls", "ppt", "odt", "ods", "odp", "rtf"),
        DataKind.STRUCTURED to listOf("json", "xml", "yaml", "yml", "toml", "csv", "tsv", "ini"),
        DataKind.MARKDOWN to listOf("md", "markdown"),
        DataKind.TEXT to listOf("txt", "log"),
        DataKind.CODE to listOf("kt", "java", "c", "cpp", "h", "go", "rs", "swift", "ts", "tsx", "html", "css", "sql", "nix"),
        DataKind.BINARIES to listOf("bin", "exe", "so", "dex", "iso", "img", "jar"),
        DataKind.APK to listOf("apk", "apks", "xapk", "aab"),
        DataKind.ARCHIVES to listOf("zip", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "rar"),
        DataKind.SCRIPTS to listOf("sh", "bash", "py", "js", "rb", "pl", "php", "lua", "ps1"),
    )

    @Test fun everyListedExtensionLandsInItsSubtype() {
        for ((kind, exts) in expected) for (e in exts) {
            assertEquals("file.$e", kind, FileClassifier.classify("file.$e"))
            assertEquals("upper-case FILE.${e.uppercase()}", kind, FileClassifier.classify("FILE.${e.uppercase()}"))
        }
        // and every extension the classifier declares is owned by exactly one subtype
        val all = DataKind.entries.flatMap { it.extensions }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun groupsHoldTheOwnersSubtypes() {
        assertEquals(listOf(DataKind.VIDEOS, DataKind.PICTURES, DataKind.MUSIC), DataKind.inGroup(DataGroup.MEDIA))
        assertEquals(listOf(DataKind.PDF, DataKind.OFFICE), DataKind.inGroup(DataGroup.OFFICE))
        assertEquals(listOf(DataKind.STRUCTURED, DataKind.MARKDOWN, DataKind.TEXT, DataKind.CODE), DataKind.inGroup(DataGroup.STRUCTURE))
        assertEquals(listOf(DataKind.BINARIES, DataKind.APK, DataKind.ARCHIVES, DataKind.SCRIPTS), DataKind.inGroup(DataGroup.BINS))
    }

    @Test fun mimeClassifiesWhatTheExtensionDoesNot() {
        assertEquals(DataKind.VIDEOS, FileClassifier.classify("clip", "video/mp4"))
        assertEquals(DataKind.PICTURES, FileClassifier.classify("noext", "image/png"))
        assertEquals(DataKind.MUSIC, FileClassifier.classify("x.unknown", "audio/x-foo"))
        assertEquals(DataKind.PDF, FileClassifier.classify("doc", "application/pdf"))
        assertEquals(DataKind.APK, FileClassifier.classify("pkg", "application/vnd.android.package-archive"))
        assertEquals(DataKind.OFFICE, FileClassifier.classify("sheet", "application/vnd.oasis.opendocument.spreadsheet"))
        assertEquals(DataKind.STRUCTURED, FileClassifier.classify("feed", "application/rss+xml"))
        assertEquals(DataKind.TEXT, FileClassifier.classify("README", "text/plain"))
        assertNull(FileClassifier.classify("weird"))
        assertNull(FileClassifier.classify(".hidden"))
        assertEquals("extension wins over mime", DataKind.MARKDOWN, FileClassifier.classify("a.md", "text/plain"))
    }

    @Test fun defaultFilterIsMediaOnly() {
        val f = DataFilter.DEFAULT
        assertEquals(setOf(DataKind.VIDEOS, DataKind.PICTURES, DataKind.MUSIC), f.on)
        assertEquals(GroupState.ALL, f.groupState(DataGroup.MEDIA))
        listOf(DataGroup.OFFICE, DataGroup.STRUCTURE, DataGroup.BINS).forEach { assertEquals(GroupState.NONE, f.groupState(it)) }
        assertEquals(f, DataFilter.decode(null))
    }

    @Test fun subtypesToggleIndependentlyAndTheGroupShowsPartial() {
        val noPictures = DataFilter.DEFAULT.toggle(DataKind.PICTURES)
        assertEquals(setOf(DataKind.VIDEOS, DataKind.MUSIC), noPictures.on)
        assertEquals(GroupState.PARTIAL, noPictures.groupState(DataGroup.MEDIA))
        // a subtype in another group, alone
        val pdf = noPictures.toggle(DataKind.PDF)
        assertEquals(GroupState.PARTIAL, pdf.groupState(DataGroup.OFFICE))
        assertFalse(DataKind.OFFICE in pdf.on)
        // group toggle from PARTIAL turns the whole group on; from ALL turns it off; other groups untouched
        val full = pdf.toggleGroup(DataGroup.MEDIA)
        assertEquals(GroupState.ALL, full.groupState(DataGroup.MEDIA))
        assertTrue(DataKind.PDF in full.on)
        val off = full.toggleGroup(DataGroup.MEDIA)
        assertEquals(GroupState.NONE, off.groupState(DataGroup.MEDIA))
        assertEquals(setOf(DataKind.PDF), off.on)
    }

    @Test fun filterPersistsThroughEncodeDecode() {
        val f = DataFilter.DEFAULT.toggle(DataKind.MUSIC).toggle(DataKind.SCRIPTS)
        assertEquals(f, DataFilter.decode(f.encode()))
        assertEquals(DataFilter(emptySet()), DataFilter.decode(""))
        assertEquals(DataFilter.DEFAULT, DataFilter.decode("NOT_A_KIND"))
    }
}
