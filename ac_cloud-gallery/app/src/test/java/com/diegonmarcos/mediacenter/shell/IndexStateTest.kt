package com.diegonmarcos.mediacenter.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexStateTest {

    @Test
    fun aBlockedIndexStaysBlockedWhateverElseIsTrue() {
        val status = IndexReducer.fromWork(IndexBlock.MODEL_MISSING, running = true, progress = 50f, produced = 9)
        assertEquals(IndexStatus.Blocked(IndexBlock.MODEL_MISSING), status)
        assertEquals(
            IndexStatus.Blocked(IndexBlock.ENGINE_MISSING),
            IndexReducer.fromCounts(IndexBlock.ENGINE_MISSING, running = true, indexed = 5, total = 10),
        )
    }

    @Test
    fun aRunningJobReportsItsOwnPercent() {
        assertEquals(IndexStatus.Running(42), IndexReducer.fromWork(null, true, 42.9f, 0))
    }

    @Test
    fun aRunningJobWithoutAFigureIsIndeterminate() {
        assertEquals(IndexStatus.Running(null), IndexReducer.fromWork(null, true, -1f, 0))
        assertEquals(IndexStatus.Running(null), IndexReducer.fromWork(null, true, 101f, 0))
    }

    @Test
    fun anIdleJobIsDoneOnlyIfItLeftSomething() {
        assertEquals(IndexStatus.Done(12), IndexReducer.fromWork(null, false, -1f, 12))
        assertEquals(IndexStatus.Idle, IndexReducer.fromWork(null, false, -1f, 0))
    }

    @Test
    fun countedIndexMovesFromIdleThroughPartialToDone() {
        assertEquals(IndexStatus.Idle, IndexReducer.fromCounts(null, false, 0, 100))
        assertEquals(IndexStatus.Partial(40, 100), IndexReducer.fromCounts(null, false, 40, 100))
        assertEquals(IndexStatus.Done(100), IndexReducer.fromCounts(null, false, 100, 100))
    }

    @Test
    fun anEmptyLibraryHasNothingToIndex() {
        assertEquals(IndexStatus.Idle, IndexReducer.fromCounts(null, false, 0, 0))
    }

    @Test
    fun aRunningCountedIndexReportsTheShareDone() {
        assertEquals(IndexStatus.Running(25), IndexReducer.fromCounts(null, true, 25, 100))
        assertEquals(IndexStatus.Running(null), IndexReducer.fromCounts(null, true, 0, 0))
        assertEquals(IndexStatus.Running(100), IndexReducer.fromCounts(null, true, 120, 100))
    }

    @Test
    fun overviewKnowsWhenSomethingIsRunning() {
        val running = IndexOverview(IndexKind.entries.associateWith { if (it == IndexKind.TEXT) IndexStatus.Running(3) else IndexStatus.Idle })
        assertTrue(running.anyRunning)
        assertFalse(IndexOverview.EMPTY.anyRunning)
    }

    @Test
    fun overviewIsSettledWhenEverythingThatCanRunIsDone() {
        val done = IndexOverview(
            mapOf(
                IndexKind.FACES to IndexStatus.Blocked(IndexBlock.MODEL_MISSING),
                IndexKind.SCENES to IndexStatus.Done(4),
                IndexKind.TEXT to IndexStatus.Done(9),
                IndexKind.DOCUMENTS to IndexStatus.Done(1),
            )
        )
        assertTrue(done.settled)
        assertEquals(listOf(IndexKind.FACES), done.blockedKinds)
    }

    @Test
    fun overviewIsNotSettledWhileAnyLiveIndexIsOpen() {
        val open = IndexOverview(
            mapOf(
                IndexKind.FACES to IndexStatus.Done(1),
                IndexKind.SCENES to IndexStatus.Partial(1, 5),
                IndexKind.TEXT to IndexStatus.Done(9),
                IndexKind.DOCUMENTS to IndexStatus.Done(1),
            )
        )
        assertFalse(open.settled)
    }

    @Test
    fun overviewOfOnlyBlockedIndexesIsNotSettled() {
        val blocked = IndexOverview(IndexKind.entries.associateWith { IndexStatus.Blocked(IndexBlock.MODEL_MISSING) })
        assertFalse(blocked.settled)
        assertFalse(IndexOverview(emptyMap()).settled)
    }

    @Test
    fun wordsCountOnlyTokensWithLettersOrDigits() {
        assertEquals(0, ContentKinds.wordCount(null))
        assertEquals(0, ContentKinds.wordCount("  \n "))
        assertEquals(3, ContentKinds.wordCount("Total due 42"))
        assertEquals(2, ContentKinds.wordCount("hello -- world ***"))
    }

    @Test
    fun aPageOfTextIsADocumentAndASignIsNot() {
        val page = (1..ContentKinds.DOCUMENT_MIN_WORDS).joinToString(" ") { "word$it" }
        assertTrue(ContentKinds.isDocument(page))
        assertFalse(ContentKinds.isDocument("EXIT"))
        assertFalse(ContentKinds.isDocument(page.substringBeforeLast(" ")))
        assertFalse(ContentKinds.isDocument(null))
    }

    @Test
    fun searchMatchesEveryTermAnywhereCaseInsensitive() {
        val text = "Invoice 2026-03 from Acme GmbH\nTotal: 120.00 EUR"
        assertTrue(ContentKinds.matches(text, "acme total"))
        assertTrue(ContentKinds.matches(text, "  EUR  "))
        assertFalse(ContentKinds.matches(text, "acme refund"))
    }

    @Test
    fun aBlankQueryMatchesEverythingButNothingMatchesMissingText() {
        assertTrue(ContentKinds.matches("anything", "  "))
        assertTrue(ContentKinds.matches(null, ""))
        assertFalse(ContentKinds.matches(null, "x"))
    }
}
