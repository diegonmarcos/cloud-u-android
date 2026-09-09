package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paginated folder walk against a scripted server on loopback: what really leaves the client,
 */
class FolderWalkOnTheWireTest {

    /**
     * A folder of [exists] messages whose UID equals its sequence number, answering each `FETCH
     */
    private fun folder(
        exists: Int,
        deleted: Set<Long> = emptySet(),
        onNoop: String = "",
        inFetch: String = "",
        uidsFor: (IntRange) -> List<Long> = { range -> range.map { it.toLong() } },
    ) = FakeImapServer { tag, line ->
        when {
            line.startsWith("SELECT") -> selectResponse(tag, exists = exists)
            line.startsWith("SEARCH UNSEEN") -> searchResponse(tag, emptyList())
            line.startsWith("NOOP") -> onNoop + ok(tag)
            line.startsWith("FETCH") -> inFetch + fetchResponse(
                tag,
                uidsFor(rangeOf(line)),
                flags = { uid -> if (uid in deleted) "\\Seen \\Deleted" else "\\Seen" },
            ) { false }
            else -> ok(tag)
        }
    }

    /** The sequence range a `FETCH <lo>:<hi> (...)` command line asks for. */
    private fun rangeOf(fetch: String): IntRange {
        val set = fetch.removePrefix("FETCH ").substringBefore(' ')
        return set.substringBefore(':').toInt()..set.substringAfter(':').toInt()
    }

    /** One run of the walk: the pages as they were handed over, and the walk's own answer. */
    private class Walked {
        val pages = mutableListOf<List<Long>>()
        val fetchesWhenPageLanded = mutableListOf<Int>()
        lateinit var walk: ImapFolderWalk
    }

    private fun walk(server: FakeImapServer, limit: Int, pageSize: Int): Walked {
        val out = Walked()
        server.session().use { session ->
            val status = session.select("INBOX")
            out.walk = runBlocking {
                session.walkFolder(status, limit, pageSize) { page ->
                    out.pages += page.map { it.uid }
                    out.fetchesWhenPageLanded += server.issued().count { it.startsWith("FETCH") }
                }
            }
        }
        return out
    }

    private fun FakeImapServer.fetches(): List<IntRange> =
        issued().filter { it.startsWith("FETCH") }.map { rangeOf(it) }

    // -- the window, on the wire -----------------------------------------------------------------

    @Test fun `a thousand-message window goes out as several requests, covering it exactly once`() {
        // The symptom: one `FETCH 1:1000` whose whole answer is parsed and held before a single
        // message is read out of it. Five requests of two hundred instead, and between them the
        // client is holding one page.
        folder(exists = 1000).use { server ->
            val walked = walk(server, limit = 1000, pageSize = 200)

            assertEquals(
                listOf(801..1000, 601..800, 401..600, 201..400, 1..200),
                server.fetches(),
            )
            assertEquals(5, walked.pages.size)
            assertEquals(List(5) { 200 }, walked.pages.map { it.size })
            assertEquals((1000L downTo 1L).toList(), walked.walk.uids)
        }
    }

    @Test fun `the window the CALLER asked for is what reaches the network, not the folder's size`() {
        // The account setting travels: 300 of a 1 000-message folder stops at sequence 701. A
        // mutation that dropped `limit` on the way to the wire would fetch the whole folder here.
        folder(exists = 1000).use { server ->
            val walked = walk(server, limit = 300, pageSize = 200)

            assertEquals(listOf(801..1000, 701..800), server.fetches())
            assertEquals(300, walked.walk.uids.size)
            assertEquals(701L, walked.walk.uids.last())
        }
    }

    @Test fun `an unbounded window walks the folder to its first message`() {
        // The IMAP half of a window bigger than the folder, ON THE WIRE — the twin of
        // `JmapClientTest.queryEmailsWindow_walksAWholeFolderWhenTheWindowIsUnbounded`.
        //
        // What this closes: `folderWindowLowest(exists, minOf(limit, 1000))` — one line, at the
        // top of `walkFolder`, restoring the old cap for every IMAP account. Nothing else in the
        // repo sees it: this file never walked past `limit = 1000`, the decision test calls the
        // pure function directly, and the wiring lints all live in `core:data`, a layer above.
        //
        // The arithmetic that has to hold: `(exists - Int.MAX_VALUE + 1).coerceAtLeast(1)` is 1,
        // not a wrapped negative — so the walk's floor is sequence 1 and the last request is
        // `1:200`.
        folder(exists = 1100).use { server ->
            val walked = walk(server, limit = Int.MAX_VALUE, pageSize = 200)

            assertEquals(
                "the unbounded window stopped short of the folder: the caller reconciles against " +
                    "these uids, so every message below the last range is DELETED from the cache",
                listOf(901..1100, 701..900, 501..700, 301..500, 101..300, 1..100),
                server.fetches(),
            )
            assertEquals(1100, walked.walk.uids.size)
            assertEquals("the oldest message of the folder must be in the walk", 1L, walked.walk.uids.last())
            assertFalse("a quiet folder must stay reconcilable", walked.walk.moved)
        }
    }

    @Test fun `an unbounded window on a folder smaller than one page is still one request`() {
        // The other end of the same rule: no window arithmetic may turn "everything" into a
        // request for sequence 0 or a negative range on a small folder.
        folder(exists = 12).use { server ->
            val walked = walk(server, limit = Int.MAX_VALUE, pageSize = 200)

            assertEquals(listOf(1..12), server.fetches())
            assertEquals(12, walked.walk.uids.size)
        }
    }

    @Test fun `a fifty-message window is still one request, as it always was`() {
        // The unified refresh and the folder-list refresh pass small windows; pagination must be
        // invisible to them — same single FETCH, and not even a NOOP.
        folder(exists = 1000).use { server ->
            walk(server, limit = 50, pageSize = 200)

            assertEquals(listOf(951..1000), server.fetches())
            assertEquals(emptyList<String>(), server.issued().filter { it.startsWith("NOOP") })
        }
    }

    @Test fun `each page is handed over before the next is asked for`() {
        // "Write each page as it lands" is only true if the pages land one at a time. If the walk
        // collected first and handed over afterwards, every page would arrive with all five
        // requests already issued — which is the memory shape this volet exists to end.
        folder(exists = 1000).use { server ->
            val walked = walk(server, limit = 1000, pageSize = 200)

            assertEquals(listOf(1, 2, 3, 4, 5), walked.fetchesWhenPageLanded)
        }
    }

    // -- the folder moving under the walk ---------------------------------------------------------

    @Test fun `an EXPUNGE between two pages leaves the walk unreconcilable`() {
        // The IMAP-specific test. The server reports the expunge on the NOOP the walk makes
        // before its second page; from there its sequence numbers are one off, so a message may
        // have been skipped and the ids below cannot be reconciled against.
        folder(exists = 1000, onNoop = "* 3 EXPUNGE\r\n").use { server ->
            val walked = walk(server, limit = 1000, pageSize = 200)

            assertTrue("the walk claims a renumbered folder held still", walked.walk.moved)
            // And it kept going: the mail is still cached, only the DELETE is off.
            assertEquals(5, walked.pages.size)
            assertEquals(1000, walked.walk.uids.size)
        }
    }

    @Test fun `an EXISTS that changed leaves the walk unreconcilable`() {
        folder(exists = 1000, onNoop = "* 1001 EXISTS\r\n").use { server ->
            assertTrue(walk(server, limit = 1000, pageSize = 200).walk.moved)
        }
    }

    @Test fun `⭐ a one-page walk has no NOOP, so the FETCH response is the only detector`() {
        // The reserve this closes. A 50-message window over a big folder — the unified refresh,
        // a small folder, any account left on the default — is ONE request: no NOOP is ever sent.
        folder(exists = 1000, inFetch = "* 3 EXPUNGE\r\n").use { server ->
            val walked = walk(server, limit = 50, pageSize = 200)

            assertEquals("this fixture is not a one-page walk", listOf(951..1000), server.fetches())
            assertEquals(emptyList<String>(), server.issued().filter { it.startsWith("NOOP") })
            assertTrue("the only announcement the server made was ignored", walked.walk.moved)
        }
    }

    @Test fun `an EXISTS inside a FETCH response is read too, on a one-page walk`() {
        // The other announcement, on the channel the RFC does allow it on: an arrival while the
        // walk runs. It renumbers nothing, but the push path caches new mail by itself and does
        // not go through the recently-mutated spare, so the reconcile would delete what it wrote.
        folder(exists = 1000, inFetch = "* 1001 EXISTS\r\n").use { server ->
            assertTrue(walk(server, limit = 50, pageSize = 200).walk.moved)
        }
    }

    @Test fun `a one-page walk over a quiet folder still reconciles — the witness`() {
        // Without this, the two tests above would pass on a walk that answers "moved" for every
        // small window, and a 50-message account would never have its cache trimmed again.
        folder(exists = 1000).use { server ->
            val walked = walk(server, limit = 50, pageSize = 200)

            assertEquals(listOf(951..1000), server.fetches())
            assertFalse("a one-page walk over an untouched folder counts as moved", walked.walk.moved)
            assertEquals(50, walked.walk.uids.size)
        }
    }

    @Test fun `an announcement in a middle page's FETCH response is read as well`() {
        // The multi-page case of the same line: the server answers the second FETCH with an
        // EXPUNGE it should have held back. A walk that only trusted its NOOPs would take that
        // page's numbering at face value.
        var fetches = 0
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 600)
                line.startsWith("FETCH") ->
                    (if (fetches++ == 1) "* 7 EXPUNGE\r\n" else "") +
                        fetchResponse(tag, rangeOf(line).map { it.toLong() }) { false }
                else -> ok(tag)
            }
        }.use { server ->
            assertTrue(walk(server, limit = 600, pageSize = 200).walk.moved)
        }
    }

    @Test fun `a quiet folder walks clean — the witness`() {
        // Without this, "moved" could be hard-wired to true and every assertion above would still
        // pass, while the cache would never again be trimmed to the window.
        folder(exists = 1000, onNoop = "* 1000 EXISTS\r\n").use { server ->
            val walked = walk(server, limit = 1000, pageSize = 200)

            assertFalse("a folder that reported nothing new counts as moved", walked.walk.moved)
            assertEquals(1000, walked.walk.uids.size)
        }
    }

    @Test fun `the walk asks between pages, which is the only moment a server may answer`() {
        // The mechanism behind the two tests above, pinned as an order: FETCH, NOOP, FETCH, NOOP…
        // A server may not report an EXPUNGE while answering a FETCH, so a walk that never asked
        // would be told nothing and would reconcile against a renumbered folder.
        folder(exists = 600).use { server ->
            walk(server, limit = 600, pageSize = 200)

            assertEquals(
                listOf("FETCH 401:600", "NOOP", "FETCH 201:400", "NOOP", "FETCH 1:200"),
                server.issued().filter { it.startsWith("FETCH") || it.startsWith("NOOP") }
                    .map { it.substringBefore(" (") },
            )
        }
    }

    // -- overlapping pages, and what is never counted twice ------------------------------------------

    @Test fun `a page that repeats what the previous one held is handed over once`() {
        // What a renumbering does in practice: ten messages went, so the second range comes back
        // holding ten UIDs the walk already has. They must not be written twice, and above all
        // must not count twice against the window.
        folder(exists = 400, uidsFor = { range -> if (range.last == 400) range.map { it.toLong() } else range.map { it + 190L } })
            .use { server ->
                val walked = walk(server, limit = 400, pageSize = 200)

                assertEquals(listOf(200, 10), walked.pages.map { it.size })
                assertEquals((200L downTo 191L).toList(), walked.pages[1])
                assertEquals(210, walked.walk.uids.size)
                assertEquals(
                    "a UID was counted twice",
                    walked.walk.uids.size,
                    walked.walk.uids.toSet().size,
                )
            }
    }

    @Test fun `de-duplication is by UID and not by position`() {
        // The same message at a different sequence number is the SAME message. A walk that
        // de-duplicated on position would hand it over again — and, worse, a walk that de-
        // duplicated on position would think it had seen a message it never fetched.
        folder(exists = 400, uidsFor = { range -> range.map { if (range.last == 400) it.toLong() else it + 200L } })
            .use { server ->
                val walked = walk(server, limit = 400, pageSize = 200)

                // The second request answered with UIDs 201..400 — every one already held.
                assertEquals(listOf(200), walked.pages.map { it.size })
                assertEquals((400L downTo 201L).toList(), walked.walk.uids)
            }
    }

    // -- the rules the walk must not have loosened ----------------------------------------------------

    @Test fun `a message flagged deleted stays out of every page, and out of the walk`() {
        // HIDING IS NOT DELETING (Codeberg #99): the filter lives in the one FETCH funnel, and
        // pagination must go through it. A walk that read the untagged lines itself would put
        // those messages back in the list — and into the ids the cache is reconciled against.
        folder(exists = 400, deleted = setOf(250L, 100L)).use { server ->
            val walked = walk(server, limit = 400, pageSize = 200)

            assertFalse("a \\Deleted message reached the cache", walked.pages.flatten().contains(250L))
            assertFalse("a \\Deleted message reached the cache", walked.pages.flatten().contains(100L))
            assertFalse(250L in walked.walk.uids)
            assertFalse(100L in walked.walk.uids)
            assertEquals(398, walked.walk.uids.size)
        }
    }

    @Test fun `a hidden message does not shorten the walk`() {
        // The step is counted in sequence positions, not in messages returned. Counting what came
        // back would leave the walk asking for the same slice for ever on a folder holding one
        // hidden message — or, with a guard against that, stopping short of the window.
        folder(exists = 400, deleted = setOf(250L)).use { server ->
            walk(server, limit = 400, pageSize = 200)

            assertEquals(listOf(201..400, 1..200), server.fetches())
        }
    }

    @Test fun `a walk that fails mid-way throws, and never answers with what it had so far`() {
        // The red line, seen from the only place that can break it silently. Everything above
        // this walk — `fullQueryWriteThrough`, and the DELETE at the end of it — treats "the walk
        var answered = 0
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 600)
                line.startsWith("FETCH") ->
                    if (answered++ == 0) fetchResponse(tag, rangeOf(line).map { it.toLong() }) { false }
                    else "$tag NO server is busy\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val pages = mutableListOf<List<Long>>()
            val thrown = server.session().use { session ->
                val status = session.select("INBOX")
                runCatching {
                    runBlocking {
                        session.walkFolder(status, limit = 600, pageSize = 200) { page ->
                            pages += page.map { it.uid }
                        }
                    }
                }.exceptionOrNull()
            }

            assertTrue("the walk swallowed a failed page and answered anyway: $thrown", thrown is ImapException)
            assertEquals("the page that DID land was not handed over", 1, pages.size)
            assertEquals(200, pages.single().size)
        }
    }

    @Test fun `an empty folder is walked without a single FETCH`() {
        folder(exists = 0).use { server ->
            val walked = walk(server, limit = 1000, pageSize = 200)

            assertEquals(emptyList<IntRange>(), server.fetches())
            assertEquals(emptyList<Long>(), walked.walk.uids)
            assertFalse(walked.walk.moved)
        }
    }
}
