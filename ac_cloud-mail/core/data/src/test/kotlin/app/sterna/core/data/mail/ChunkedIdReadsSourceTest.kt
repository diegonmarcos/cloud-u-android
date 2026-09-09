package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT. It is a last resort, not a proof of execution: `MailRepository`
 */
class ChunkedIdReadsSourceTest {

    private val source = DaoQuerySource.mailSource("MailRepository")

    /** Every `emailDao.emailsByIds(…)` call in the file, as the raw text of its arguments. */
    private fun emailsByIdsCalls(): List<String> {
        val call = "emailDao.emailsByIds("
        val out = mutableListOf<String>()
        var i = source.indexOf(call)
        while (i >= 0) {
            var depth = 0
            var j = i + call.length - 1
            val start = j + 1
            do {
                when (source[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            } while (depth > 0)
            out += source.substring(start, j - 1)
            i = source.indexOf(call, j)
        }
        return out
    }

    /** The last argument of a call — the id list, in both `emailsByIds` overloads. */
    private fun lastArg(args: String): String {
        var depth = 0
        var last = 0
        args.forEachIndexed { k, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) last = k + 1
            }
        }
        return args.substring(last).trim()
    }

    @Test fun noIdListReachesTheDatabaseWithoutABound() {
        val calls = emailsByIdsCalls()
        // The file really does read emails by id in several places; an empty result here would
        // mean the scan stopped matching and the whole check silently passed.
        assertTrue(
            "only ${calls.size} emailsByIds calls found — did the call shape change?",
            calls.size >= 10,
        )

        // Two shapes are safe, and nothing else is:
        //  - `listOf(<one id>)`     — a single message, one binding;
        val unbounded = calls.map { lastArg(it) }
            .filterNot { it == "chunk" || it == "slice" || it.startsWith("listOf(") }

        assertEquals(
            "these emailsByIds calls bind a list of unbounded size into one IN (...): route them " +
                "through byIdsChunked",
            emptyList<String>(), unbounded,
        )
    }

    @Test fun everyBulkPathReadsItsRowsThroughTheChunkingHelper() {
        // The paths whose id list is the user's whole selection (or a whole undo, or a whole
        // search page). Each must hand it to byIdsChunked rather than to the DAO directly.
        val routed = listOf(
            "cachedEmailsByIds",
            "jmapMoveAll",
            "jmapDestroyAll",
            "markSelectionRead",
            "markReadOnMoveOutOfInbox",
            "restoreCounts",
            "imapMoveGroup",
            "imapDestroyGroup",
            "search",
            "fetchThreadMembers",
            // The freeze of a held-back destroy (`numberingRowsWereReadUnder`, #99) is NOT in
            // this list any more, and that is a tightening, not a hole: its id list IS the
            "setReadAll",
        )
        val missing = routed.filterNot {
            "byIdsChunked(" in DaoQuerySource.mailFunctionBody("MailRepository", it)
        }
        assertEquals(
            "these MailRepository paths no longer chunk their id reads (a path may leave this " +
                "list only by handing its split to a helper a JVM test EXECUTES, the way " +
                "numberingRowsWereReadUnder did)",
            emptyList<String>(), missing,
        )
    }

    @Test fun thePathsThatChunkWithTheirOwnLoopStillDo() {
        // These three read, write and count CHUNK BY CHUNK for reasons of their own (per-chunk
        // count nudges, per-chunk Email/set), so they carry the loop themselves instead of
        // delegating. They must still carry one: their `chunk` argument is only safe because of it.
        val missing = listOf("pruneServerGone", "setReadAll", "evictAll").filterNot {
            ".chunked(" in DaoQuerySource.mailFunctionBody("MailRepository", it)
        }
        assertEquals(
            "these MailRepository paths lost the loop that bounds their `chunk`",
            emptyList<String>(), missing,
        )
    }

    @Test fun theBulkSeenPathTakesItsBatchSizeFromTheSession() {
        // The Email/set chunking of "mark all read" used to be a hard-coded 500 that called itself
        // "maxObjectsInSet floor" while reading nothing. It must come from the session.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "setReadAll")
        assertTrue(
            "setReadAll no longer sizes its Email/set batches from the server's advertised limit",
            "setBatchSize()" in body,
        )
    }
}
