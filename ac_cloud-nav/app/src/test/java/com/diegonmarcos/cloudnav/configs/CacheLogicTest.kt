package com.diegonmarcos.cloudnav.configs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Cache page's arithmetic: the size words and the directory walk it reports. */
class CacheLogicTest {
    @Test fun sizeWords() {
        assertEquals("0 B", CacheFormat.size(0))
        assertEquals("1023 B", CacheFormat.size(1023))
        assertEquals("1.0 KiB", CacheFormat.size(1024))
        assertEquals("1.50 MiB", CacheFormat.size(1_572_864))
        assertEquals("2.00 GiB", CacheFormat.size(2L * 1_073_741_824))
    }

    @Test fun dirSizeSumsFilesRecursivelyAndMissingIsZero() {
        val root = java.nio.file.Files.createTempDirectory("cachelogic").toFile()
        try {
            File(root, "a").writeBytes(ByteArray(10))
            File(root, "sub").mkdir()
            File(root, "sub/b").writeBytes(ByteArray(5))
            assertEquals(15L, CacheDisk.dirSize(root))
            assertEquals(0L, CacheDisk.dirSize(File(root, "nope")))
        } finally { root.deleteRecursively() }
    }
}
