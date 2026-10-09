package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.mail.AuthIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/** v29 -> v30 adds `emails.authClass`; the backfill classifies the rows that were cached before it. */
class AuthClassMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE emails(id TEXT, accountId TEXT, subject TEXT, preview TEXT, PRIMARY KEY(accountId, id))",
            )
            it.executeUpdate("INSERT INTO emails VALUES('1','a','Your code','Your code is 482913.')")
            it.executeUpdate("INSERT INTO emails VALUES('2','a','Reset your password','Reset your password: https://x.io/reset?t=resettoken')")
            it.executeUpdate("INSERT INTO emails VALUES('3','a','Weekly','Ten gardening tips for spring')")
            it.executeUpdate("INSERT INTO emails VALUES('4','a','No preview yet',NULL)")
        }
    }

    @After fun tearDown() = db.close()

    private fun supportDb(): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when {
            method.name == "execSQL" && args?.size == 1 -> { db.createStatement().use { it.executeUpdate(args[0] as String) }; null }
            method.name == "toString" -> "SupportSQLiteDatabase(jdbc)"
            method.name == "hashCode" -> 0
            method.name == "equals" -> false
            else -> error("Unexpected SupportSQLiteDatabase.${method.name} in a migration")
        }
    } as SupportSQLiteDatabase

    private fun authOf(id: String): Int? = db.createStatement().use { st ->
        st.executeQuery("SELECT authClass FROM emails WHERE id='$id'").use { rs -> rs.next(); rs.getInt(1).takeIf { !rs.wasNull() } }
    }

    @Test fun `the migration adds a nullable column and keeps every row`() {
        MIGRATION_29_30.migrate(supportDb())
        val n = db.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM emails").use { rs -> rs.next(); rs.getInt(1) } }
        assertEquals(4, n)
        (1..4).forEach { assertNull("row $it is unclassified, not wrongly classified", authOf("$it")) }
        assertEquals(30, MIGRATION_29_30.endVersion)
        assertEquals(29, MIGRATION_29_30.startVersion)
    }

    @Test fun `the migration is registered and the schema version moved with it`() {
        assertTrue(SternaDatabase.ALL_MIGRATIONS.any { it === MIGRATION_29_30 })
        assertEquals(30, SCHEMA_VERSION)
    }

    @Test fun `the backfill classifies the unclassified rows with a preview and leaves the rest`() {
        MIGRATION_29_30.migrate(supportDb())
        val classified = runBlocking {
            AuthIndex.backfill(
                batch = 2,
                next = { limit ->
                    db.createStatement().use { st ->
                        st.executeQuery("SELECT accountId, id, subject, preview FROM emails WHERE authClass IS NULL AND preview IS NOT NULL LIMIT $limit").use { rs ->
                            buildList { while (rs.next()) add(AuthIndexRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
                        }
                    }
                },
                store = { acc, id, cls ->
                    db.createStatement().use { it.executeUpdate("UPDATE emails SET authClass=$cls WHERE accountId='$acc' AND id='$id'") }
                },
            )
        }
        assertEquals(3, classified)
        assertEquals(1, authOf("1"))
        assertEquals(2, authOf("2"))
        assertEquals(0, authOf("3"))
        assertNull("no preview yet: judged when it arrives", authOf("4"))
    }
}
