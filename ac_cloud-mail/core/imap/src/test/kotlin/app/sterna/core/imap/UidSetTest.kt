package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/** The UID sequence-set builder / parser that lets one command act on a whole batch (Codeberg #29). */
class UidSetTest {

    @Test fun empty() {
        assertEquals("", compressUidSet(emptyList()))
    }

    @Test fun single() {
        assertEquals("7", compressUidSet(listOf(7L)))
    }

    @Test fun contiguousRangeCollapses() {
        assertEquals("1:5", compressUidSet(listOf(1L, 2L, 3L, 4L, 5L)))
    }

    @Test fun singletonsAndRangesMixed() {
        assertEquals("1:3,5,8:10", compressUidSet(listOf(1L, 2L, 3L, 5L, 8L, 9L, 10L)))
    }

    @Test fun sortsAndDedupesInput() {
        // Out of order, with duplicates — the compressed set is still canonical.
        assertEquals("1:3,5,8:10", compressUidSet(listOf(10L, 2L, 9L, 1L, 5L, 3L, 8L, 5L, 2L)))
    }

    @Test fun twoAdjacentIsAPairRangeNotSingletons() {
        assertEquals("40:41", compressUidSet(listOf(40L, 41L)))
    }

    @Test fun gapOfOneStaysTwoSingletons() {
        assertEquals("40,42", compressUidSet(listOf(40L, 42L)))
    }

    @Test fun expandSingletons() {
        assertEquals(listOf(5L, 8L, 12L), expandUidSet("5,8,12"))
    }

    @Test fun expandRange() {
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), expandUidSet("10:14"))
    }

    @Test fun expandPreservesTokenOrderNotNumericOrder() {
        // COPYUID pairing depends on order: a server may list ranges high-to-low.
        assertEquals(listOf(20L, 21L, 22L, 5L, 8L), expandUidSet("20:22,5,8"))
    }

    @Test fun expandDescendingRange() {
        assertEquals(listOf(9L, 8L, 7L), expandUidSet("9:7"))
    }

    @Test fun roundTripThroughCompressAndExpand() {
        val uids = listOf(1L, 2L, 3L, 7L, 100L, 101L, 102L, 500L)
        assertEquals(uids.sorted(), expandUidSet(compressUidSet(uids)).sorted())
    }

    @Test fun copyUidMappingPairsPositionally() {
        // COPYUID <validity> <sourceSet> <destSet>: the i-th source uid maps to the i-th dest uid.
        val map = copyUidMapping("1:3,7", "50:52,60")
        assertEquals(mapOf(1L to 50L, 2L to 51L, 3L to 52L, 7L to 60L), map)
    }

    @Test fun copyUidMappingRespectsDeclaredOrder() {
        // Source and destination sets need not be numerically sorted; they pair by position.
        val map = copyUidMapping("7,1:3", "60,50:52")
        assertEquals(mapOf(7L to 60L, 1L to 50L, 2L to 51L, 3L to 52L), map)
    }

    @Test fun copyUidMappingEmptyWhenSetsDisagreeInSize() {
        assertEquals(emptyMap<Long, Long>(), copyUidMapping("1:3", "50:51"))
        assertEquals(emptyMap<Long, Long>(), copyUidMapping("", ""))
    }
}
