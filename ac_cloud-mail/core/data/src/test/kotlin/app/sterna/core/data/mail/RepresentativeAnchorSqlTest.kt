package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * Verifies, against in-memory SQLite, the anchor selection of
 */
class RepresentativeAnchorSqlTest {
    private lateinit var db: Connection

    private val anchorSql = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("oldestRepresentativeEmailId"))

    private val countSql = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("representativeCountForMailbox"))

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String, threadId: String?, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', 0, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setLong(5, sortKey)
            ps.executeUpdate()
        }
    }

    /** Prepare one of the shipped statements, its `?` filled in the order [DaoQuerySource] gives. */
    private fun prepare(
        query: Pair<String, List<String>>,
        accountId: String,
        mailbox: String,
    ): PreparedStatement {
        val (sql, order) = query
        val ps = db.prepareStatement(sql)
        order.forEachIndexed { i, name ->
            ps.setString(
                i + 1,
                when (name) {
                    "accountId" -> accountId
                    "mailboxId" -> mailbox
                    else -> error("Unexpected parameter ':$name' in the representative queries")
                },
            )
        }
        return ps
    }

    private fun anchor(accountId: String = "acc", mailbox: String = "inbox"): String? =
        prepare(anchorSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun representativeCount(accountId: String = "acc", mailbox: String = "inbox"): Int =
        prepare(countSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test fun skipsOlderThreadMemberAndAnchorsOnItsRepresentative() {
        insert("m1", threadId = "T1", sortKey = 100) // oldest ROW, but not T1's newest
        insert("m2", threadId = "T1", sortKey = 300) // T1's representative
        insert("solo", threadId = null, sortKey = 200) // its own representative
        // The raw oldest row (m1) must NOT be the anchor: the collapsed query never lists it.
        assertEquals("solo", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun threadlessOldestRowIsItsOwnAnchor() {
        insert("a", threadId = null, sortKey = 100)
        insert("b", threadId = null, sortKey = 200)
        assertEquals("a", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun scopesToAccountAndMailbox() {
        insert("in1", threadId = "T1", sortKey = 100)
        insert("sent1", threadId = "T1", sortKey = 300, mailbox = "sent") // newer, other folder
        // The sibling account's member is NEWER than in1 (issue #31: same-server accounts share
        insert("other1", threadId = "T1", sortKey = 500, accountId = "accB")
        // Within (acc, inbox), in1 is T1's newest — neither the Sent reply nor the sibling
        // account's newer member demotes it.
        assertEquals("in1", anchor())
        assertEquals(1, representativeCount())
    }

    @Test fun aSiblingAccountsIdenticalCopyIsNeitherAnchorNorCounted() {
        // The case [scopesToAccountAndMailbox] cannot see, and the reason the OUTER
        // `accountId = :accountId AND mailboxId = :mailboxId` has to stay: both parameters survive
        //
        // Unless they TIE. Two sub-accounts of one server (issue #31) receive the same message —
        // same receivedAt, so the same sortKey, and the same server thread id — and the sibling's
        insert("B-dup", threadId = "T-dup", sortKey = 100, accountId = "accB")
        insert("A-dup", threadId = "T-dup", sortKey = 100)
        insert("A-new", threadId = "T-a", sortKey = 300)

        assertEquals("A-dup", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun emptyMailboxYieldsNoAnchor() {
        assertEquals(null, anchor())
        assertEquals(0, representativeCount())
    }
}
