package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.mail.SenderDomain
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

/** v30 -> v31 adds `emails.fromDomain`; the backfill indexes the rows that were cached before it. */
class FromDomainMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate("CREATE TABLE emails(id TEXT, accountId TEXT, fromEmail TEXT, PRIMARY KEY(accountId, id))")
            it.executeUpdate("INSERT INTO emails VALUES('1','a','n@Notifications.GitHub.com')")
            it.executeUpdate("INSERT INTO emails VALUES('2','a','orders@shop.loja.com.br')")
            it.executeUpdate("INSERT INTO emails VALUES('3','a',NULL)")
            it.executeUpdate("INSERT INTO emails VALUES('4','b','x@mail.example.co.uk')")
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

    private fun domainOf(id: String): String? = db.createStatement().use { st ->
        st.executeQuery("SELECT fromDomain FROM emails WHERE id='$id'").use { rs -> rs.next(); rs.getString(1) }
    }

    @Test fun `the migration adds a nullable column and keeps every row`() {
        MIGRATION_30_31.migrate(supportDb())
        val n = db.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM emails").use { rs -> rs.next(); rs.getInt(1) } }
        assertEquals(4, n)
        (1..4).forEach { assertNull("row $it is unindexed, not wrongly indexed", domainOf("$it")) }
        assertEquals(30, MIGRATION_30_31.startVersion)
        assertEquals(31, MIGRATION_30_31.endVersion)
    }

    @Test fun `the migration is registered and the schema version moved with it`() {
        assertTrue(SternaDatabase.ALL_MIGRATIONS.any { it === MIGRATION_30_31 })
        assertTrue(SCHEMA_VERSION >= 31)
    }

    @Test fun `the backfill indexes every unindexed row, the address-less one as unknown`() {
        MIGRATION_30_31.migrate(supportDb())
        val indexed = runBlocking {
            SenderDomain.backfill(
                batch = 3,
                next = { limit ->
                    db.createStatement().use { st ->
                        st.executeQuery("SELECT accountId, id, fromEmail FROM emails WHERE fromDomain IS NULL LIMIT $limit").use { rs ->
                            buildList { while (rs.next()) add(DomainIndexRow(rs.getString(1), rs.getString(2), rs.getString(3))) }
                        }
                    }
                },
                store = { rows ->
                    db.prepareStatement("UPDATE emails SET fromDomain=? WHERE accountId=? AND id=?").use { ps ->
                        rows.forEach { r -> ps.setString(1, r.fromDomain); ps.setString(2, r.accountId); ps.setString(3, r.id); ps.executeUpdate() }
                    }
                },
            )
        }
        assertEquals(4, indexed)
        assertEquals("github.com", domainOf("1"))
        assertEquals("loja.com.br", domainOf("2"))
        assertEquals("", domainOf("3"))
        assertEquals("example.co.uk", domainOf("4"))
    }

    @Test fun `both cache mappers fill the column`() {
        val jmap = java.io.File("src/main/kotlin/app/sterna/core/data/mail/EmailMapper.kt").readText()
        val imap = java.io.File("src/main/kotlin/app/sterna/core/data/mail/ImapMailService.kt").readText()
        assertTrue(jmap.contains("fromDomain = SenderDomain.indexed(sender?.email)"))
        assertTrue(imap.contains("fromDomain = SenderDomain.indexed(fromEmail)"))
    }
}
