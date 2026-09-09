package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, the member-selection semantics of
 */
class UnarchiveMembersSqlTest {
    private lateinit var db: Connection

    // Two thread ids in the IN-list, matching how the DAO expands `threadId IN (:threadIds)`.
    private val sql =
        "SELECT id FROM emails WHERE accountId = ? AND mailboxId = ? AND threadId IN (?, ?)"

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT PRIMARY KEY, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String, threadId: String?, mailbox: String,
        accountId: String = "acc", sortKey: Long = 0,
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', 0, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setLong(5, sortKey)
            ps.executeUpdate()
        }
    }

    private fun members(accountId: String, mailbox: String, t1: String, t2: String): Set<String> =
        db.prepareStatement(sql).use { ps ->
            ps.setString(1, accountId); ps.setString(2, mailbox); ps.setString(3, t1); ps.setString(4, t2)
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString("id")) } }
        }

    @Test fun selectsOnlyArchivedMembersOfTheRepliedThreads() {
        insert("a1", threadId = "T1", mailbox = "archive")
        insert("a2", threadId = "T1", mailbox = "archive")
        insert("i1", threadId = "T1", mailbox = "inbox") // the new reply — already home
        insert("o1", threadId = "T9", mailbox = "archive") // other thread, no reply — stays archived
        assertEquals(setOf("a1", "a2"), members("acc", "archive", "T1", "T2"))
    }

    @Test fun membersInOtherFoldersAreUntouched() {
        insert("s1", threadId = "T1", mailbox = "sent")
        insert("t1", threadId = "T1", mailbox = "trash")
        insert("a1", threadId = "T1", mailbox = "archive")
        assertEquals(setOf("a1"), members("acc", "archive", "T1", "T2"))
    }

    @Test fun threadlessArchivedMailNeverMatches() {
        // threadId IS NULL never satisfies IN (...) — a thread-less message can't have
        // received a reply, so it must stay archived.
        insert("n1", threadId = null, mailbox = "archive")
        assertEquals(emptySet<String>(), members("acc", "archive", "T1", "T2"))
    }

    @Test fun scopedToTheActingAccount() {
        // Same-server accounts can share bare mailbox and thread ids — a sibling
        // account's archive must not be raided.
        insert("a1", threadId = "T1", mailbox = "archive", accountId = "acc")
        insert("b1", threadId = "T1", mailbox = "archive", accountId = "other")
        assertEquals(setOf("a1"), members("acc", "archive", "T1", "T2"))
    }

    @Test fun selectsAcrossAllRepliedThreadsInOnePass() {
        insert("a1", threadId = "T1", mailbox = "archive")
        insert("b1", threadId = "T2", mailbox = "archive")
        insert("c1", threadId = "T3", mailbox = "archive")
        assertEquals(setOf("a1", "b1"), members("acc", "archive", "T1", "T2"))
    }
}
