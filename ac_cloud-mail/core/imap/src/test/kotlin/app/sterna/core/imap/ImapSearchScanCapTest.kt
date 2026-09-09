package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The attachment scan's ceiling, driven on a real socket: when a folder is deeper than the walk is
 */
class ImapSearchScanCapTest {

    private val query = buildImapSearch(
        ImapSearchCriteria(
            from = "alex.rivera@masto.top",
            afterMillis = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        ),
    )

    /**
     * A folder the walk got through end to end: every candidate was examined, so what it kept IS
     */
    @Test
    fun `a folder walked to the end under the scan cap answers in full`() {
        val candidates = (1L..200L).toList()
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 200)
                line.startsWith("UID SEARCH") -> searchResponse(tag, candidates)
                // Two of the two hundred carry a file.
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { uid -> uid % 100 == 0L }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), query, requireAttachment = true, limit = 5)
            }
            assertEquals(listOf(200L, 100L), hits.single().messages.map { it.uid })
            assertFalse("nothing was left unlooked-at, so this is a total", hits.single().incomplete)
        }
    }

    /**
     * The same folder, deeper than the walk may go: it keeps what it found on the way and SAYS the
     */
    @Test
    fun `a folder deeper than the scan cap keeps what it found and says the answer is short`() {
        val candidates = (1L..1_500L).toList()
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1_500)
                line.startsWith("UID SEARCH") -> searchResponse(tag, candidates)
                // Exactly one file, on the newest message, so the walk finds something and still
                // runs out of allowance long before the folder runs out of candidates.
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { uid -> uid == 1_500L }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), query, requireAttachment = true, limit = 5)
            }
            assertEquals(listOf(1_500L), hits.single().messages.map { it.uid })
            assertTrue("the walk stopped with candidates left: not a total", hits.single().incomplete)
        }
    }

    /**
     * THE witness that matters, and the case no test covered: several folders, ONE of them
     */
    @Test
    fun `one saturated folder makes the walk short without costing the other folders their hits`() {
        var selected = ""
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> {
                    selected = line.removePrefix("SELECT ").trim('"')
                    selectResponse(tag)
                }
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (selected == "Huge") (1L..1_500L).toList() else listOf(11L, 12L))
                // In "Huge" only the newest message carries a file, so the walk keeps one and gives
                // up; the small folders are all attachments and are walked to the end.
                line.startsWith("UID FETCH") ->
                    fetchResponse(tag, uidsOf(line)) { uid -> uid == 1_500L || uid < 100L }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("Huge", "Archive", "Sent"), query, requireAttachment = true, limit = 5)
            }

            assertEquals(listOf("Huge", "Archive", "Sent"), hits.map { it.mailbox })
            assertTrue("the whole walk is short as soon as one folder is", hits.any { it.incomplete })

            val huge = hits.first { it.mailbox == "Huge" }
            assertTrue(huge.incomplete)
            assertEquals(listOf(1_500L), huge.messages.map { it.uid })

            // The point of the test: the good folders are untouched by the neighbour's saturation.
            listOf("Archive", "Sent").forEach { name ->
                val folder = hits.first { it.mailbox == name }
                assertFalse("$name answered in full and must not be marked", folder.incomplete)
                assertEquals(listOf(12L, 11L), folder.messages.map { it.uid })
            }
        }
    }

    /**
     * A folder whose `UID SEARCH` is refused (`NO`) while its neighbours answer. It must come back
     */
    @Test
    fun `a folder whose search is refused comes back empty and marked while the others answer`() {
        var selected = ""
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> {
                    selected = line.removePrefix("SELECT ").trim('"')
                    selectResponse(tag)
                }
                line.startsWith("UID SEARCH") ->
                    if (selected == "Broken") "$tag NO unsupported search key\r\n"
                    else searchResponse(tag, listOf(4L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { true }
                else -> ok(tag)
            }
        }.use { server ->
            val failures = mutableListOf<String>()
            val hits = server.session().use { session ->
                session.searchFolders(
                    listOf("INBOX", "Broken", "Archive"),
                    query,
                    requireAttachment = true,
                    limit = 5,
                ) { mailbox, _ -> failures += mailbox }
            }

            assertEquals(listOf("INBOX", "Broken", "Archive"), hits.map { it.mailbox })
            assertEquals(listOf("Broken"), failures)

            val broken = hits.first { it.mailbox == "Broken" }
            assertTrue(broken.messages.isEmpty())
            assertTrue("a folder that could not be searched is not a folder with no matches", broken.incomplete)

            // The witness: the neighbours answered and are not marked.
            listOf("INBOX", "Archive").forEach { name ->
                assertFalse(hits.first { it.mailbox == name }.incomplete)
                assertEquals(listOf(4L), hits.first { it.mailbox == name }.messages.map { it.uid })
            }
        }
    }

    /**
     * What keeps the scan affordable: the attachment filter narrows a set the SERVER already cut
     */
    @Test
    fun `the attachment scan filters what the server already narrowed rather than replacing the query`() {
        val narrowed = listOf(31L, 32L, 33L)
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 900)
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if ("FROM" in line && "SINCE" in line) narrowed else (1L..900L).toList())
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { uid -> uid == 32L }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), query, requireAttachment = true, limit = 5)
            }

            assertEquals(
                listOf("""UID SEARCH FROM "alex.rivera@masto.top" SINCE 1-Jun-2026"""),
                server.issued().filter { it.startsWith("UID SEARCH") },
            )
            // One FETCH, of the three the server picked — not a crawl over the folder.
            val fetches = server.issued().filter { it.startsWith("UID FETCH") }
            assertEquals(1, fetches.size)
            assertEquals(narrowed, uidsOf(fetches.single()).sorted())

            assertEquals(listOf(32L), hits.single().messages.map { it.uid })
            assertFalse("every candidate the server returned was examined", hits.single().incomplete)
        }
    }
}
