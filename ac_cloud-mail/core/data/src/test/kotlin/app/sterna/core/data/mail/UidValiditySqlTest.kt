package app.sterna.core.data.mail

import app.sterna.core.data.db.BODY_CACHE_ID_PREFIX_SQL
import app.sterna.core.data.db.EMAIL_BODIES_CREATE_SQL
import app.sterna.core.data.db.MAILBOX_UIDVALIDITY_CREATE_SQL
import app.sterna.core.data.db.PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL
import app.sterna.core.data.db.PURGE_SNAPSHOT_CREATE_SQL
import app.sterna.core.data.db.PurgeSnapshotEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The storage side of the UIDVALIDITY record (Codeberg #99), against real in-memory SQLite and
 */
class UidValiditySqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            // The v18 schema, exactly as the released migration left it…
            st.executeUpdate(PURGE_SNAPSHOT_CREATE_SQL)
            st.executeUpdate(EMAIL_BODIES_CREATE_SQL)
        }
    }

    @After fun tearDown() = db.close()

    /** The 18→19 migration, statement for statement. */
    private fun migrate() = db.createStatement().use { st ->
        st.executeUpdate(PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL)
        st.executeUpdate(MAILBOX_UIDVALIDITY_CREATE_SQL)
    }

    private fun insertV18Snapshot(purgeId: String, emailId: String, mailboxId: String = "Trash") {
        db.prepareStatement("INSERT INTO purge_snapshot VALUES(?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, purgeId); ps.setString(2, "accA"); ps.setString(3, mailboxId)
            ps.setString(4, emailId); ps.setLong(5, 1_000L)
            ps.executeUpdate()
        }
    }

    private fun insertRows(rows: List<PurgeSnapshotEntity>) {
        db.prepareStatement("INSERT OR REPLACE INTO purge_snapshot VALUES(?, ?, ?, ?, ?, ?)").use { ps ->
            rows.forEach { row ->
                ps.setString(1, row.purgeId); ps.setString(2, row.accountId)
                ps.setString(3, row.mailboxId); ps.setString(4, row.emailId)
                ps.setLong(5, row.createdAt)
                val validity = row.uidValidity
                if (validity == null) ps.setNull(6, java.sql.Types.INTEGER) else ps.setLong(6, validity)
                ps.executeUpdate()
            }
        }
    }

    /** [app.sterna.core.data.db.PurgeSnapshotDao.head], as the DAO declares it. */
    private fun head(purgeId: String): Pair<String, Long?>? =
        db.prepareStatement(
            "SELECT mailboxId, uidValidity FROM purge_snapshot " +
                "WHERE purgeId = ? AND accountId = ? LIMIT 1",
        ).use { ps ->
            ps.setString(1, purgeId); ps.setString(2, "accA")
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else {
                    val mailbox = rs.getString(1)
                    val validity = rs.getLong(2)
                    mailbox to if (rs.wasNull()) null else validity
                }
            }
        }

    private fun body(accountId: String, id: String) {
        db.prepareStatement("INSERT OR REPLACE INTO email_bodies VALUES(?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, "{}")
            ps.setString(4, "{}"); ps.setLong(5, 1L)
            ps.executeUpdate()
        }
    }

    private fun bodies(): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT id FROM email_bodies ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    /** [app.sterna.core.data.db.EmailBodyDao.deleteForIdPrefix], through the shipped statement. */
    private fun dropFolderBodies(accountId: String, mailboxId: String) {
        val sql = BODY_CACHE_ID_PREFIX_SQL.replace(Regex(":\\w+"), "?")
        db.prepareStatement(sql).use { ps ->
            ps.setString(1, accountId)
            ps.setString(2, UidValidity.bodyCacheIdPrefix(accountId, mailboxId))
            ps.executeUpdate()
        }
    }

    // ---- the upgrade ------------------------------------------------------------------------

    /**
     * A purge confirmed on the previous version, still pending when the app upgrades. It comes
     */
    @Test fun `a snapshot written before the column survives the upgrade unverifiable`() {
        insertV18Snapshot("p1", "imap:accA:Trash:7")

        migrate()

        assertEquals("Trash" to null, head("p1"))
        assertFalse(UidValidity.mayDestroy(head("p1")?.second))
    }

    @Test fun `a snapshot taken after the upgrade carries its numbering`() {
        migrate()
        insertRows(TrashPurge.snapshotRows("p2", "accA", "Trash", listOf("imap:accA:Trash:7"), 1_000L, uidValidity = 42L))

        assertEquals("Trash" to 42L, head("p2"))
        assertTrue(UidValidity.mayDestroy(head("p2")?.second))
    }

    @Test fun `a purge with no snapshot at all answers nothing`() {
        migrate()

        assertNull(head("never-confirmed"))
    }

    // ---- the per-folder record --------------------------------------------------------------

    @Test fun `the numbering is remembered per account and folder`() {
        migrate()
        db.prepareStatement("INSERT OR REPLACE INTO mailbox_uidvalidity VALUES(?, ?, ?)").use { ps ->
            listOf(Triple("accA", "Trash", 1L), Triple("accB", "Trash", 9L), Triple("accA", "INBOX", 3L))
                .forEach { (account, mailbox, validity) ->
                    ps.setString(1, account); ps.setString(2, mailbox); ps.setLong(3, validity)
                    ps.executeUpdate()
                }
        }

        val read = db.prepareStatement(
            "SELECT uidValidity FROM mailbox_uidvalidity WHERE accountId = ? AND mailboxId = ?",
        ).use { ps ->
            ps.setString(1, "accB"); ps.setString(2, "Trash")
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

        // Same folder name, two accounts on one server (issue #31): two rows, two numberings.
        assertEquals(9L, read)
    }

    // ---- what a renumbering drops -----------------------------------------------------------

    @Test fun `only the renumbered folder loses its cached bodies`() {
        migrate()
        body("accA", "imap:accA:Trash:1")
        body("accA", "imap:accA:Trash:2")
        body("accA", "imap:accA:INBOX:1")
        body("accB", "imap:accB:Trash:1")

        dropFolderBodies("accA", "Trash")

        assertEquals(listOf("imap:accA:INBOX:1", "imap:accB:Trash:1"), bodies())
    }

    /** `_` is a SQL wildcard: without the escape, emptying `a_b` would take `axb` with it. */
    @Test fun `a folder name containing a SQL wildcard matches only itself`() {
        migrate()
        body("accA", "imap:accA:a_b:1")
        body("accA", "imap:accA:axb:1")

        dropFolderBodies("accA", "a_b")

        assertEquals(listOf("imap:accA:axb:1"), bodies())
    }
}
