package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The header search a move between accounts falls back on (#189): when B's server names no uid
 */
class UidSearchHeaderOnTheWireTest {

    private fun searchAgainst(uids: List<Long>, act: (ImapSession) -> List<Long>): Pair<List<Long>, List<String>> =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID SEARCH") -> searchResponse(tag, uids)
                else -> ok(tag)
            }
        }.use { server ->
            val found = server.session().use { session ->
                session.select("Projets")
                act(session)
            }
            found to server.issued()
        }

    @Test fun `the search goes out as UID SEARCH HEADER, name and value quoted`() {
        val (found, issued) = searchAgainst(listOf(12L)) { it.uidSearchHeader("Message-ID", "<a\"b@x>") }
        assertEquals(listOf(12L), found)
        assertEquals(
            listOf("CAPABILITY", "SELECT \"Projets\"", "UID SEARCH HEADER \"Message-ID\" \"<a\\\"b@x>\"", "LOGOUT"),
            issued,
        )
    }

    @Test fun `the cap keeps the first two of a longer answer, and an empty answer is empty`() {
        val (found, _) = searchAgainst(listOf(7L, 9L, 11L)) { it.uidSearchHeader("Message-ID", "<a@x>") }
        assertEquals(listOf(7L, 9L), found)
        val (none, _) = searchAgainst(emptyList()) { it.uidSearchHeader("Message-ID", "<a@x>") }
        assertEquals(emptyList<Long>(), none)
    }
}
