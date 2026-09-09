package app.sterna.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCacheTest {
    @Test
    fun `keeps what was put in`() {
        val cache = PhotoCache<String>(maxEntries = 4, maxBytes = 1000)
        cache.put("content://photo/1", "one", 10)
        assertEquals("one", cache.get("content://photo/1"))
        assertTrue(cache.contains("content://photo/1"))
        assertFalse(cache.contains("content://photo/2"))
        assertNull(cache.get("content://photo/2"))
    }

    @Test
    fun `a remembered miss is an entry, not an absence`() {
        val cache = PhotoCache<String?>(maxEntries = 4, maxBytes = 1000)
        cache.put("content://photo/1", null, 0)
        assertTrue(cache.contains("content://photo/1"))
        assertNull(cache.get("content://photo/1"))
        // A miss still weighs something, so a crowd of them cannot pile up unbounded.
        assertEquals(1L, cache.byteSize())
    }

    @Test
    fun `evicts the least recently used past the entry bound`() {
        val cache = PhotoCache<String>(maxEntries = 2, maxBytes = 1000)
        cache.put("a", "A", 10)
        cache.put("b", "B", 10)
        cache.get("a") // "a" is now the most recent
        cache.put("c", "C", 10)
        assertEquals(2, cache.size())
        assertTrue(cache.contains("a"))
        assertFalse(cache.contains("b"))
        assertTrue(cache.contains("c"))
    }

    @Test
    fun `evicts past the byte bound even when few entries are held`() {
        val cache = PhotoCache<String>(maxEntries = 100, maxBytes = 100)
        cache.put("a", "A", 60)
        cache.put("b", "B", 60)
        assertEquals(1, cache.size())
        assertFalse(cache.contains("a"))
        assertEquals(60L, cache.byteSize())
    }

    @Test
    fun `re-putting a key replaces its weight instead of adding to it`() {
        val cache = PhotoCache<String>(maxEntries = 4, maxBytes = 1000)
        cache.put("a", "A", 30)
        cache.put("a", "A2", 10)
        assertEquals(1, cache.size())
        assertEquals(10L, cache.byteSize())
        assertEquals("A2", cache.get("a"))
    }

    @Test
    fun `an entry heavier than the whole budget is kept rather than re-decoded forever`() {
        val cache = PhotoCache<String>(maxEntries = 4, maxBytes = 100)
        cache.put("huge", "H", 500)
        assertTrue(cache.contains("huge"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `subsampling brings an oversized picture under the target`() {
        assertEquals(1, sampleSize(96, 96, 128))
        assertEquals(1, sampleSize(128, 128, 128))
        assertEquals(2, sampleSize(256, 256, 128))
        assertEquals(4, sampleSize(512, 480, 128))
        assertEquals(16, sampleSize(2048, 1024, 128))
    }
}
