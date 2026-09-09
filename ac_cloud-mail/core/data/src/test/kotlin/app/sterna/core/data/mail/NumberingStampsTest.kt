package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.RowNumbering
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [numberingStampsOfRows] EXECUTED — the decision that manufactures the stamp THE TWO #99 GUARDS
 */
class NumberingStampsTest {

    /** Records every id list the database read was handed, so "never called" is observable. */
    private class Rows(private val stamps: Map<String, Long?>) {
        val calls = mutableListOf<List<String>>()

        suspend fun read(ids: List<String>): List<RowNumbering> {
            calls += ids
            // Like the DAO: an id with no cached row simply does not come back.
            return ids.filter { it in stamps }.map { RowNumbering(it, stamps[it]) }
        }
    }

    private fun credentials(protocol: MailProtocol, id: String = "acc") =
        AccountCredentials(server = "https://mail.example.test", username = "u", password = "p", id = id, protocol = protocol)

    @Test fun `a JMAP account is stamped by nothing and never reaches the database`() = runBlocking {
        val rows = Rows(mapOf("jmap:acc:1" to 42L))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.JMAP),
            listOf("jmap:acc:1", "jmap:acc:2"),
        ) { rows.read(it) }

        assertEquals(emptyMap<String, Long?>(), stamps)
        // The return alone would still pass if the guard moved AFTER the read: on JMAP there is
        // nothing to number, so the read must not happen at all.
        assertEquals("a JMAP selection read the emails table for stamps it cannot have", 0, rows.calls.size)
    }

    @Test fun `an IMAP account is stamped with the numbering of each of its rows`() = runBlocking {
        val rows = Rows(mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to 42L, "imap:acc:INBOX:9" to 77L))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.IMAP),
            listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8", "imap:acc:INBOX:9"),
        ) { rows.read(it) }

        assertEquals(
            mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to 42L, "imap:acc:INBOX:9" to 77L),
            stamps,
        )
        assertEquals(1, rows.calls.size)
    }

    @Test fun `a row cached without a numbering keeps its key, with a null value`() = runBlocking {
        // Rows written before schema v25 have no uidValidity. PRESENT-and-null, never dropped:
        // the guard reads it as "nothing to oppose", and the two shapes must stay distinguishable.
        val rows = Rows(mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to null))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.IMAP),
            listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8"),
        ) { rows.read(it) }

        assertEquals(mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to null), stamps)
        assertTrue("the null-stamped row must still be a key of the answer", "imap:acc:INBOX:8" in stamps)
    }

    @Test fun `an id with no cached row is absent, not null-valued`() = runBlocking {
        val rows = Rows(mapOf("imap:acc:INBOX:7" to 42L))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.IMAP),
            listOf("imap:acc:INBOX:7", "imap:acc:INBOX:evicted"),
        ) { rows.read(it) }

        assertEquals(mapOf("imap:acc:INBOX:7" to 42L), stamps)
        assertTrue("an evicted row must not be invented as a key", "imap:acc:INBOX:evicted" !in stamps)
    }

    @Test fun `an empty selection never touches the database`() = runBlocking {
        val rows = Rows(emptyMap())

        val stamps = numberingStampsOfRows(credentials(MailProtocol.IMAP), emptyList()) { rows.read(it) }

        assertEquals(emptyMap<String, Long?>(), stamps)
        assertEquals("an empty selection issued an IN () against the emails table", 0, rows.calls.size)
    }

    /**
     * The whole point of the chunk, at the size that breaks: a select-all of a Trash. Below
     */
    @Test fun `a selection of more than 999 ids is read in bounded chunks, whole`() = runBlocking {
        val ids = (1..1201).map { "imap:acc:INBOX:$it" }
        val rows = Rows(ids.associateWith { id -> id.substringAfterLast(':').toLong() })

        val stamps = numberingStampsOfRows(credentials(MailProtocol.IMAP), ids) { rows.read(it) }

        assertTrue(
            "a chunk of ${rows.calls.maxOf { it.size }} ids binds past SQLite's 999 limit: the " +
                "delete crashes instead of failing",
            rows.calls.all { it.size <= 999 },
        )
        assertEquals(listOf(200, 200, 200, 200, 200, 200, 1), rows.calls.map { it.size })
        // Nothing lost, nothing read twice: the stamps must cover the selection exactly.
        assertEquals(ids, rows.calls.flatten())
        assertEquals(ids.size, stamps.size)
        assertEquals(1L, stamps["imap:acc:INBOX:1"])
        assertEquals(1201L, stamps["imap:acc:INBOX:1201"])
    }

    /**
     * A READ THAT THROWS MUST TAKE THE WHOLE GESTURE DOWN WITH IT, and the reason is the exact
     */
    @Test fun `a read that fails is not swallowed into an empty map`() {
        val boom = object {
            var calls = 0
            suspend fun read(ids: List<String>): List<RowNumbering> {
                calls++
                throw IOException("database locked while reading ${ids.size} ids")
            }
        }

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                numberingStampsOfRows(
                    credentials(MailProtocol.IMAP),
                    listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8"),
                ) { boom.read(it) }
            }
        }

        assertEquals("database locked while reading 2 ids", thrown.message)
        assertEquals("the read must really have been attempted", 1, boom.calls)
    }
}
