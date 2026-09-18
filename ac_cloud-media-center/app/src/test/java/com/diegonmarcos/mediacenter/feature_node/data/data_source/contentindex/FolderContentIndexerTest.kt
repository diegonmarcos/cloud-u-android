/*
 * Task #460 — the incremental folder walk. FolderContentIndexer is pure JVM
 * logic over a DAO interface and a scanner fun-interface, so the whole walk is
 * pinned here: unchanged files are NEVER re-read, changed files are re-scanned
 * and upserted (REPLACE), scan failures are counted and skip the row, progress
 * is reported per image, and cancellation stops the walk mid-flight.
 */

package com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderContentIndexerTest {

    private val albumId = 7L

    private fun item(
        id: Long,
        size: Long = 100L,
        dateModified: Long = 1_700_000_000L,
        uri: String = "content://media/external/images/media/$id"
    ) = IndexableMedia(id = id, size = size, dateModified = dateModified, uri = uri)

    private fun row(id: Long, fingerprint: String, album: Long = albumId) = MediaContentIndexEntity(
        mediaId = id,
        albumId = album,
        uri = "content://media/external/images/media/$id",
        fingerprint = fingerprint
    )

    private class FakeDao : MediaContentIndexDao {
        val rows = mutableMapOf<Long, MediaContentIndexEntity>()
        val upserts = mutableListOf<MediaContentIndexEntity>()
        override suspend fun byMediaId(mediaId: Long) = rows[mediaId]
        override suspend fun forAlbum(albumId: Long) = rows.values.filter { it.albumId == albumId }
        override suspend fun countForAlbum(albumId: Long) = rows.values.count { it.albumId == albumId }
        override suspend fun upsert(entity: MediaContentIndexEntity) {
            rows[entity.mediaId] = entity
            upserts.add(entity)
        }
        override suspend fun deleteForAlbum(albumId: Long) {
            rows.entries.removeAll { it.value.albumId == albumId }
        }
    }

    private class CountingScanner(
        var result: IndexedContent = IndexedContent(),
        var failure: Exception? = null
    ) : IndexScanner {
        var calls = 0
        override suspend fun scan(item: IndexableMedia): IndexedContent {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    @Test
    fun emptyFolderWalksNothing() = runBlocking {
        val dao = FakeDao()
        val scanner = CountingScanner()
        val summary = FolderContentIndexer(dao, scanner).indexFolder(albumId, emptyList())
        assertEquals(IndexSummary(total = 0, unchanged = 0, updated = 0), summary)
        assertEquals(0, scanner.calls)
        assertTrue(dao.upserts.isEmpty())
    }

    @Test
    fun unchangedItemIsNotRescanned() = runBlocking {
        val dao = FakeDao().apply { rows[1L] = row(1L, fingerprint = item(1L).fingerprint()) }
        val scanner = CountingScanner()
        val summary = FolderContentIndexer(dao, scanner).indexFolder(albumId, listOf(item(1L)))
        assertEquals(1, summary.unchanged)
        assertEquals(0, summary.updated)
        assertEquals(0, scanner.calls)
        assertTrue(dao.upserts.isEmpty())
    }

    @Test
    fun changedItemIsRescannedAndUpserted() = runBlocking {
        val dao = FakeDao().apply { rows[1L] = row(1L, fingerprint = "1:1") } // stale
        val scanner = CountingScanner(result = IndexedContent(ocrText = "new"))
        val summary = FolderContentIndexer(dao, scanner).indexFolder(albumId, listOf(item(1L)))
        assertEquals(0, summary.unchanged)
        assertEquals(1, summary.updated)
        assertEquals(1, scanner.calls)
        assertEquals(item(1L).fingerprint(), dao.rows.getValue(1L).fingerprint)
        assertEquals("new", dao.rows.getValue(1L).ocrText)
    }

    @Test
    fun sameMediaInAnotherAlbumIsRescanned() = runBlocking {
        // row exists but under a DIFFERENT album: the media may be identical,
        // but this folder's index must still get its own row.
        val dao = FakeDao().apply {
            rows[1L] = row(1L, fingerprint = item(1L).fingerprint(), album = 999L)
        }
        val scanner = CountingScanner()
        val summary = FolderContentIndexer(dao, scanner).indexFolder(albumId, listOf(item(1L)))
        assertEquals(0, summary.unchanged)
        assertEquals(1, summary.updated)
        assertEquals(1, scanner.calls)
    }

    @Test
    fun newItemWithBarcodeAndTextCountsBoth() = runBlocking {
        val dao = FakeDao()
        val scanner = CountingScanner(result = IndexedContent(
            barcodeFormat = "QR_CODE",
            barcodeValue = "https://example.com",
            payloadAction = "url",
            ocrText = "HELLO",
            language = "en"
        ))
        val summary = FolderContentIndexer(dao, scanner).indexFolder(albumId, listOf(item(1L)))
        assertEquals(1, summary.updated)
        assertEquals(1, summary.barcodes)
        assertEquals(1, summary.withText)
        val stored = dao.rows.getValue(1L)
        assertEquals("QR_CODE", stored.barcodeFormat)
        assertEquals("https://example.com", stored.barcodeValue)
        assertEquals("url", stored.payloadAction)
        assertEquals("HELLO", stored.ocrText)
        assertEquals("en", stored.language)
    }

    @Test
    fun noBarcodeNoTextCountsNeither() = runBlocking {
        val dao = FakeDao()
        val scanner = CountingScanner(result = IndexedContent())
        val summary = FolderContentIndexer(dao, scanner).indexFolder(albumId, listOf(item(1L)))
        assertEquals(0, summary.barcodes)
        assertEquals(0, summary.withText)
    }

    @Test
    fun unchangedRowKeepsItsBarcodeAndTextCounts() = runBlocking {
        val dao = FakeDao().apply {
            rows[1L] = row(1L, fingerprint = item(1L).fingerprint()).copy(
                barcodeFormat = "CODE_128",
                ocrText = "kept"
            )
        }
        val scanner = CountingScanner() // would return nothing if called
        val summary = FolderContentIndexer(dao, scanner).indexFolder(albumId, listOf(item(1L)))
        assertEquals(1, summary.unchanged)
        assertEquals(1, summary.barcodes)
        assertEquals(1, summary.withText)
        assertEquals(0, scanner.calls)
    }

    @Test
    fun scanFailureIsCountedAndSkipsRow() = runBlocking {
        val dao = FakeDao()
        val scanner = CountingScanner(failure = Exception("boom"))
        val summary = FolderContentIndexer(dao, scanner).indexFolder(
            albumId,
            listOf(item(1L), item(2L))
        )
        assertEquals(2, summary.failed)
        assertEquals(0, summary.updated)
        assertTrue(dao.rows.isEmpty())
        assertEquals(2, scanner.calls)
    }

    @Test
    fun progressReportsAfterEveryItem() = runBlocking {
        val dao = FakeDao()
        val scanner = CountingScanner()
        val progress = mutableListOf<Pair<Int, Int>>()
        FolderContentIndexer(dao, scanner).indexFolder(
            albumId,
            listOf(item(1L), item(2L), item(3L)),
            onProgress = { indexed, total -> progress.add(indexed to total) }
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun cancellationStopsTheWalkAndIsRethrown() = runBlocking {
        val dao = FakeDao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scanner = IndexScanner { _ ->
            entered.complete(Unit)
            release.await()
            IndexedContent()
        }
        val indexer = FolderContentIndexer(dao, scanner)
        var rethrown: CancellationException? = null
        val job = launch {
            try {
                indexer.indexFolder(albumId, listOf(item(1L), item(2L)))
            } catch (error: CancellationException) {
                rethrown = error
                throw error
            }
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue("cancellation must propagate to the caller", rethrown != null)
        // second item never scanned
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun fingerprintChangesWhenSizeOrModDateChanges() {
        val base = item(1L, size = 100L, dateModified = 1_000L)
        assertEquals("100:1000", base.fingerprint())
        assertEquals("200:1000", item(1L, size = 200L, dateModified = 1_000L).fingerprint())
        assertEquals("100:2000", item(1L, size = 100L, dateModified = 2_000L).fingerprint())
    }

    @Test
    fun scannerFailureLeavesNoOrphanRowForTheNextWalk() = runBlocking {
        val dao = FakeDao()
        val scanner = CountingScanner(failure = Exception("boom"))
        FolderContentIndexer(dao, scanner).indexFolder(albumId, listOf(item(1L)))
        assertNull(dao.rows[1L])
    }
}