package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import app.sterna.core.data.mail.folderRoleMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The (account, folder) → role map the rows of an unfolded conversation are judged by (#115), run
 */
class FolderRolesSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE mailboxes(
                    accountId TEXT, id TEXT, name TEXT, role TEXT, parentId TEXT,
                    sortOrder INTEGER, totalEmails INTEGER, unreadEmails INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun folder(accountId: String, id: String, role: String?, name: String = id) =
        db.prepareStatement(
            "INSERT INTO mailboxes(accountId, id, name, role, parentId, sortOrder, totalEmails, " +
                "unreadEmails) VALUES(?, ?, ?, ?, NULL, 0, 0, 0)",
        ).use { ps ->
            ps.setString(1, accountId); ps.setString(2, id); ps.setString(3, name)
            if (role == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, role)
            ps.executeUpdate()
        }

    /** The shipped `observeRoles` statement, run for [accountIds] and mapped exactly as the
     *  repository maps it — [folderRoleMap], the projection the ViewModel receives. */
    private fun roles(accountIds: List<String>): Map<Pair<String, String>, String> {
        val sql = DaoQuerySource.daoQuery("MailboxDao", "observeRoles")
        val (positional, order) = DaoQuerySource.bindOrder(sql, mapOf("accountIds" to accountIds.size))
        assertEquals(List(accountIds.size) { "accountIds" }, order)
        val rows = db.prepareStatement(positional).use { ps ->
            accountIds.forEachIndexed { i, id -> ps.setString(i + 1, id) }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(AccountMailboxRole(rs.getString("accountId"), rs.getString("id"), rs.getString("role")))
                    }
                }
            }
        }
        return folderRoleMap(rows)
    }

    @Test fun `the inbox is in the map, with the role that sends a row to its sender`() {
        // The mutation this is here for: excluding 'inbox' from the query is one clause, and it is
        // the whole of #115 — your own message echoed back into the Inbox goes back to reading
        // "To: …" as a child row while the reader it opens says the sender.
        folder("acc", "f1", "inbox")
        folder("acc", "f2", "sent")

        assertEquals(
            mapOf(("acc" to "f1") to "inbox", ("acc" to "f2") to "sent"),
            roles(listOf("acc")),
        )
    }

    @Test fun `a folder with no role is left out, and that is not the same as unknown`() {
        // A user folder answers nothing about incoming or outgoing, so it is absent — and a caller
        // that finds no entry must fall back, not conclude. Keeping such a row with an empty role
        // would turn "I don't know" into "not outgoing" for every message in it.
        folder("acc", "f1", "inbox")
        folder("acc", "f2", null, name = "Receipts")

        assertEquals(mapOf(("acc" to "f1") to "inbox"), roles(listOf("acc")))
    }

    @Test fun `a folder that claimed the trash and lost it is left out too`() {
        // `~trash` is the mark a second trash is stored with (a personal Trash plus a shared
        // Shared/…/Trash) so search skips it. The statement CANNOT drop it — it keeps every
        folder("acc", "f1", "inbox")
        folder("acc", "f2", "~trash")
        folder("acc", "f3", "~junk")
        folder("acc", "f4", "sent")

        assertEquals(
            mapOf(("acc" to "f1") to "inbox", ("acc" to "f4") to "sent"),
            roles(listOf("acc")),
        )
    }

    @Test fun `the elected trash is in the map, mark or no mark`() {
        // The witness: the erasure keys on the mark, not on the word "trash".
        folder("acc", "f1", "trash")
        folder("acc", "f2", "junk")

        assertEquals(
            mapOf(("acc" to "f1") to "trash", ("acc" to "f2") to "junk"),
            roles(listOf("acc")),
        )
    }

    @Test fun `an account outside the scope contributes nothing`() {
        folder("acc", "f1", "inbox")
        folder("other", "f1", "sent")

        assertEquals(mapOf(("acc" to "f1") to "inbox"), roles(listOf("acc")))
    }

    @Test fun `two accounts numbering their folders alike stay apart`() {
        // Servers number mailboxes per account (#121/#31): Stalwart hands two accounts the same
        // "f1". Keyed on the folder id alone, one account's Sent would make the other's Inbox read
        // as outgoing — every message in it showing its recipients instead of its sender.
        folder("acc", "f1", "inbox")
        folder("other", "f1", "sent")

        assertEquals(
            mapOf(("acc" to "f1") to "inbox", ("other" to "f1") to "sent"),
            roles(listOf("acc", "other")),
        )
    }

    @Test fun `an account that has never opened its inbox still has its folders judged`() {
        // The scoping half (FolderRolesScopeTest) makes sure such an account is asked about at all;
        // this is the other end of it — nothing in the statement requires an inbox to exist.
        folder("fresh", "f9", "archive")

        assertEquals(mapOf(("fresh" to "f9") to "archive"), roles(listOf("fresh")))
    }
}
