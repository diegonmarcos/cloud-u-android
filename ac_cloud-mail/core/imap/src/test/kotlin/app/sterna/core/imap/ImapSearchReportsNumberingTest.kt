package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A search walk SELECTs every folder it looks in, and what that SELECT states about the folder's
 */
class ImapSearchReportsNumberingTest {

    private val query = buildImapSearch(
        ImapSearchCriteria(
            from = "alex.rivera@masto.top",
            afterMillis = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        ),
    )

    @Test
    fun `each folder's hits carry the numbering its own SELECT stated`() {
        // Two folders, two numberings: a single value carried for the whole walk would pass the
        // first assertion and hand the second folder the first one's number — which is worse than
        // carrying nothing, since it would license an action under a foreign folder's numbering.
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT \"Archive\"") -> selectResponse(tag, exists = 3, uidValidity = 42L)
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3, uidValidity = 7L)
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(1L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("Archive", "INBOX"), query, requireAttachment = false, limit = 5)
            }

            assertEquals(listOf("Archive", "INBOX"), hits.map { it.mailbox })
            assertEquals(
                "the numbering must be the one THAT folder's SELECT stated, folder by folder",
                listOf(42L, 7L),
                hits.map { it.uidValidity },
            )
        }
    }

    @Test
    fun `a folder whose server states no numbering is still searched, and reports none`() {
        // The other half, and the one that must NOT become a refusal here: a non-conforming server
        // still answers the search, still hands over its hits, and reports 0 — "nothing stated".
        // `UidValidityStore.record` refuses to write a value at or below 0, so 0 travels safely.
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") ->
                    "* 3 EXISTS\r\n* OK [UIDNEXT 4] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(1L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), query, requireAttachment = false, limit = 5)
            }

            assertEquals("the folder is still searched, and still answers", 1, hits.single().messages.size)
            assertEquals("and it reports no numbering rather than inventing one", 0L, hits.single().uidValidity)
        }
    }

    @Test
    fun `a folder that could not be selected reports no numbering at all`() {
        // It never got in, so it has nothing to say about the folder's numbering — and 0 is what
        // stops the layer above from recording anything for it.
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> "$tag NO [NONEXISTENT] no such mailbox\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("Gone"), query, requireAttachment = false, limit = 5)
            }

            assertEquals("a folder that could not be searched still reports itself", listOf("Gone"), hits.map { it.mailbox })
            assertEquals(0L, hits.single().uidValidity)
        }
    }
}
