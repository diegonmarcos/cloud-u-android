package app.sterna.core.data.mail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a cached message body. The rule: a row we cannot read costs one refetch, never a
 */
class CachedBodyReadTest {

    /** What SQLite raises for a row past the cursor window; the type is irrelevant, the shape isn't. */
    private class BlobTooBig : RuntimeException("Row too big to fit into CursorWindow")

    @Test fun `a readable row is returned and kept`() = runTest {
        var purged = false
        val body = readCachedOrPurge(read = { "cached body" }, purge = { purged = true })
        assertEquals("cached body", body)
        assertFalse(purged)
    }

    @Test fun `a missing row is a plain miss, not a purge`() = runTest {
        var purged = false
        assertNull(readCachedOrPurge<String>(read = { null }, purge = { purged = true }))
        assertFalse(purged)
    }

    @Test fun `an unreadable row is dropped instead of failing forever`() = runTest {
        var rows = 1
        val read: suspend () -> String? = { if (rows > 0) throw BlobTooBig() else null }
        val purge: suspend () -> Unit = { rows = 0 }

        // First open: the read throws, the caller gets a miss (and refetches), the row is gone.
        assertNull(readCachedOrPurge(read, purge))
        assertEquals(0, rows)
        // Every later open is an ordinary cache miss — no exception escapes to the reader.
        assertNull(readCachedOrPurge(read, purge))
    }

    @Test fun `a failing purge still yields a miss rather than an exception`() = runTest {
        val body = readCachedOrPurge<String>(
            read = { throw BlobTooBig() },
            purge = { throw IllegalStateException("database is locked") },
        )
        assertNull(body)
    }

    // ---- Not writing the poison row in the first place --------------------------------------

    @Test fun `an ordinary body is cacheable`() {
        assertTrue(fitsBodyCache("{\"id\":\"m1\"}", "{}"))
    }

    @Test fun `a body past the row limit is not written`() {
        // Inline images are stored as base64 data: URIs, so a handful of them is what actually
        // gets a row past SQLite's window.
        val inline = "x".repeat(MAX_CACHED_BODY_CHARS)
        assertFalse(fitsBodyCache("{\"id\":\"m1\"}", inline))
    }

    @Test fun `the row limit stays under SQLite's 2 MB cursor window`() {
        assertTrue(MAX_CACHED_BODY_CHARS < 2 * 1024 * 1024)
    }
}
