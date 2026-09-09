package app.sterna.core.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the exact 9→10 migration statement ([OUTBOX_CREATE_SQL]) against a real SQLite engine to
 * confirm it creates the `outbox` table additively, leaving pre-existing user data untouched.
 */
class OutboxMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    @Test fun createsOutboxTableWithExpectedColumns() {
        db.createStatement().use { it.executeUpdate(OUTBOX_CREATE_SQL) }

        val columns = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`outbox`)").use { rs ->
                while (rs.next()) columns += rs.getString("name")
            }
        }
        val expected = setOf(
            "id", "accountId", "recipients", "cc", "bcc", "subject", "textBody", "htmlBody",
            "fromName", "fromEmail", "inReplyTo", "references", "attachmentsJson",
            "createdAtMillis", "notBeforeMillis", "state", "attemptCount", "lastError", "lastAttemptMillis",
        )
        assertEquals(expected, columns)
    }

    @Test fun migrationDoesNotTouchExistingData() {
        // Stand in for a v9 table holding user data (e.g. scheduled_sends).
        db.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE scheduled_sends(id INTEGER PRIMARY KEY, subject TEXT)")
            st.executeUpdate("INSERT INTO scheduled_sends(id, subject) VALUES (1, 'keep me')")
        }
        // The additive migration only adds the new table.
        db.createStatement().use { it.executeUpdate(OUTBOX_CREATE_SQL) }

        db.createStatement().use { st ->
            st.executeQuery("SELECT subject FROM scheduled_sends WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals("keep me", rs.getString(1))
            }
        }
    }
}
