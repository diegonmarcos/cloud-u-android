package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * #501: the Home page's per-account counts, EXECUTED — the SQL of `EmailDao.homeCountsByAccount` as
 * shipped (read out of the DAO source by [DaoQuerySource], never restated here) against a real
 * SQLite holding a mailbox whose every answer is known. Two accounts share a bare message id, the
 * way two logins of one server do (#31), so a count that bled across accounts cannot pass.
 *
 * The second half of the ticket's proof: change the store and the numbers must follow. A tile that
 * showed the same figure whatever the cache held would pass any test of a single fixed mailbox.
 */
class HomeCountsSqlTest {
    private lateinit var db: Connection

    private data class Counts(val starred: Int, val withAttachments: Int, val recent: Int, val oldest: Long?)

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE emails(id TEXT, accountId TEXT, mailboxId TEXT, seen INTEGER, " +
                    "flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER, PRIMARY KEY(accountId, id))",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun add(account: String, id: String, flagged: Boolean = false, attachment: Boolean = false, sortKey: Long) {
        db.prepareStatement("INSERT INTO emails VALUES(?,?, 'inbox', 0, ?, ?, ?)").use {
            it.setString(1, id); it.setString(2, account)
            it.setInt(3, if (flagged) 1 else 0); it.setInt(4, if (attachment) 1 else 0)
            it.setLong(5, sortKey)
            it.executeUpdate()
        }
    }

    /** The shipped statement, its one named parameter bound to [since]. */
    private fun counts(since: Long): Map<String, Counts> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("homeCountsByAccount"))
        assertEquals("the only thing this statement may be asked for is the cut-off", listOf("sinceMillis"), order)
        return db.prepareStatement(sql).use { st ->
            st.setLong(1, since)
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val oldest = rs.getLong("oldest").takeUnless { rs.wasNull() }
                        put(rs.getString("accountId"), Counts(rs.getInt("starred"), rs.getInt("withAttachments"), rs.getInt("recent"), oldest))
                    }
                }
            }
        }
    }

    private fun knownMailbox() {
        // work: 5 messages, 2 starred, 3 with a file, 2 newer than 1000, dated 400..5000, one undated
        add("work", "m1", flagged = true, attachment = true, sortKey = 5000)
        add("work", "m2", attachment = true, sortKey = 1000)
        add("work", "m3", flagged = true, attachment = true, sortKey = 400)
        add("work", "m4", sortKey = 900)
        add("work", "m5", sortKey = 0)
        // home: the SAME ids, 1 message starred, none with a file, 1 recent
        add("home", "m1", flagged = true, sortKey = 2000)
        add("home", "m2", sortKey = 100)
    }

    @Test fun `every count of a known mailbox is the one the rows add up to, per account`() {
        knownMailbox()
        val got = counts(since = 1000)
        assertEquals(Counts(starred = 2, withAttachments = 3, recent = 2, oldest = 400), got["work"])
        assertEquals(Counts(starred = 1, withAttachments = 0, recent = 1, oldest = 100), got["home"])
        assertEquals("one row per account holding mail", setOf("work", "home"), got.keys)
    }

    @Test fun `the counts follow the store, not a constant`() {
        knownMailbox()
        val before = counts(since = 1000)
        add("work", "m6", flagged = true, attachment = true, sortKey = 9000)
        add("work", "m7", sortKey = 50)
        val after = counts(since = 1000)
        assertEquals(
            "one more starred, one more with a file, one more recent, and an older oldest",
            Counts(starred = 3, withAttachments = 4, recent = 3, oldest = 50), after["work"],
        )
        assertEquals("the other account is untouched by work's new mail", before["home"], after["home"])
    }

    @Test fun `the cut-off decides what is recent`() {
        knownMailbox()
        assertEquals(1, counts(since = 5000)["work"]!!.recent)
        assertEquals("the cut-off is inclusive", 2, counts(since = 1000)["work"]!!.recent)
        assertEquals(4, counts(since = 1)["work"]!!.recent)
    }

    @Test fun `an undated message is neither recent nor the oldest`() {
        add("a", "m1", sortKey = 0)
        val got = counts(since = 0)["a"]!!
        assertNull("no dated message, no oldest", got.oldest)
        assertEquals("sortKey 0 is 'no date': not recent, even against a cut-off of 0", 0, got.recent)
    }

    @Test fun `an account with no cached mail has no row at all, so nothing is faked for it`() {
        assertTrue(counts(since = 0).isEmpty())
        add("a", "m1", sortKey = 10)
        assertEquals(setOf("a"), counts(since = 0).keys)
    }
}
