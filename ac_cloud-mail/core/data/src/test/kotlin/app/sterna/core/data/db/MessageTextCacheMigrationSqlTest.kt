package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * **v28 -> v29**: the `message_text_cache` table. Run for real, as the REGISTERED migration, against
 * a SQLite database that already holds a v28 `emails` row: the step must add the table, leave every
 * existing row alone, and the table must take and replace a row per (account, message, kind, language).
 */
class MessageTextCacheMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    private fun supportDb(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, m, args ->
            when {
                m.name == "execSQL" && args?.size == 1 -> { db.createStatement().use { it.executeUpdate(args[0] as String) }; null }
                m.name == "toString" -> "SupportSQLiteDatabase(jdbc)"
                else -> error("the migration called ${m.name}, which this fake does not support")
            }
        } as SupportSQLiteDatabase

    private fun count(sql: String): Int = db.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }

    @Test fun theStepIsRegistered() {
        val step = SternaDatabase.ALL_MIGRATIONS.firstOrNull { it.startVersion == 28 }
        assertTrue("no migration starting at v28 is registered", step != null)
        assertEquals(29, step!!.endVersion)
    }

    @Test fun theStepAddsTheTableAndTouchesNothingElse() {
        db.createStatement().use {
            it.executeUpdate("CREATE TABLE `emails` (`id` TEXT NOT NULL PRIMARY KEY, `subject` TEXT)")
            it.executeUpdate("INSERT INTO `emails` VALUES ('e1', 'kept')")
        }
        SternaDatabase.ALL_MIGRATIONS.first { it.startVersion == 28 }.migrate(supportDb())
        assertEquals("the existing row survives", 1, count("SELECT COUNT(*) FROM `emails` WHERE `subject` = 'kept'"))
        assertEquals(0, count("SELECT COUNT(*) FROM `message_text_cache`"))
        val columns = buildList {
            db.createStatement().use { st -> st.executeQuery("PRAGMA table_info(`message_text_cache`)").use { while (it.next()) add(it.getString("name")) } }
        }
        assertEquals(listOf("accountId", "emailId", "kind", "lang", "sourceHash", "payload", "createdAt"), columns)
    }

    @Test fun oneRowPerMessageKindAndLanguage() {
        SternaDatabase.ALL_MIGRATIONS.first { it.startVersion == 28 }.migrate(supportDb())
        fun put(lang: String, payload: String) = db.createStatement().use {
            it.executeUpdate("INSERT OR REPLACE INTO `message_text_cache` VALUES ('a','m1','translation','$lang','h','$payload',1)")
        }
        put("es", "one"); put("de", "two"); put("es", "three")
        assertEquals("a second write for the same language replaces the first", 2, count("SELECT COUNT(*) FROM `message_text_cache`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `message_text_cache` WHERE `lang` = 'es' AND `payload` = 'three'"))
    }
}
