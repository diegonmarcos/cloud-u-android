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
 * **v21 → v22** ([MIGRATION_21_22]): `outbox` and `scheduled_sends` each gain `requestReceipt`, the
 */
class ReadReceiptMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    // --- the published v15 schema (what an install carried before the key widening) --------------

    private val publishedEmailsCreate =
        "CREATE TABLE `emails` (" +
            "`id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
            "`threadId` TEXT, `subject` TEXT, `preview` TEXT, `receivedAt` TEXT, `fromName` TEXT, " +
            "`fromEmail` TEXT, `seen` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, " +
            "`hasAttachment` INTEGER NOT NULL, `sortKey` INTEGER NOT NULL)"
    private val publishedBodiesCreate =
        "CREATE TABLE `email_bodies` (" +
            "`id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `bodyJson` TEXT NOT NULL, " +
            "`inlineImagesJson` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL)"
    private val publishedSnoozedCreate =
        "CREATE TABLE `snoozed` (" +
            "`emailId` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `until` INTEGER NOT NULL)"
    private val publishedMailboxesCreate =
        "CREATE TABLE `mailboxes` (" +
            "`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `role` TEXT, " +
            "`parentId` TEXT, `sortOrder` INTEGER NOT NULL, `totalEmails` INTEGER NOT NULL, " +
            "`unreadEmails` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`))"
    private val publishedScheduledCreate =
        "CREATE TABLE `scheduled_sends` (" +
            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `accountId` TEXT NOT NULL, " +
            "`recipients` TEXT NOT NULL, `cc` TEXT, `bcc` TEXT, `subject` TEXT NOT NULL, " +
            "`textBody` TEXT NOT NULL, `htmlBody` TEXT, `fromName` TEXT, `fromEmail` TEXT, " +
            "`inReplyTo` TEXT, `references` TEXT, `sendAtMillis` INTEGER NOT NULL, " +
            "`draftEmailId` TEXT)"
    private val publishedContactsCreate =
        "CREATE TABLE `recent_contacts` (" +
            "`email` TEXT NOT NULL PRIMARY KEY, `name` TEXT, `lastSeen` INTEGER NOT NULL)"

    /**
     * Every table a v21 database holds rows in, so "nothing was lost" can be measured, not hoped.
     */
    private val allTables = listOf(
        "emails", "email_bodies", "snoozed", "mailboxes", "outbox", "scheduled_sends",
        "recent_contacts", "email_fts", "purge_snapshot", "mailbox_uidvalidity",
    )

    // --- running the real Migration objects ------------------------------------------------------

    private fun supportDb(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when {
                method.name == "execSQL" && args?.size == 1 -> {
                    db.createStatement().use { it.executeUpdate(args[0] as String) }
                    null
                }
                method.name == "toString" -> "SupportSQLiteDatabase(jdbc)"
                method.name == "hashCode" -> System.identityHashCode(this)
                method.name == "equals" -> false
                else -> error("Unexpected SupportSQLiteDatabase.${method.name} in a migration")
            }
        } as SupportSQLiteDatabase

    /** The published v15 schema, populated — the state a real install carries before any of this. */
    private fun seedPublishedV15() {
        db.createStatement().use { st ->
            st.executeUpdate(publishedEmailsCreate)
            st.executeUpdate("CREATE INDEX `index_emails_mailboxId` ON `emails` (`mailboxId`)")
            st.executeUpdate(publishedBodiesCreate)
            st.executeUpdate("CREATE INDEX `index_email_bodies_accountId` ON `email_bodies` (`accountId`)")
            st.executeUpdate(publishedSnoozedCreate)
            st.executeUpdate(publishedMailboxesCreate)
            st.executeUpdate(OUTBOX_CREATE_SQL)
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
            st.executeUpdate(publishedScheduledCreate)
            st.executeUpdate(publishedContactsCreate)
            st.executeUpdate(EMAIL_FTS_CREATE_SQL)

            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `fromName`, " +
                    "`fromEmail`, `seen`, `flagged`, `hasAttachment`, `sortKey`) " +
                    "VALUES ('e1', 'accA', 'mb1', 'Hello', 'Alex', 'alex@example.org', 0, 0, 0, 100)",
            )
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e2', 'accA', 'mb1', 'World', 1, 0, 0, 200)",
            )
            st.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'accA', '{}', '{}', 42)")
            // The user data a destructive fallback destroys: a snooze, and above all mail written
            // and never sent — one row waiting in the outbox, one waiting for its hour.
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e2', 'accA', 99999)")
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`attachmentsJson`, `createdAtMillis`, `notBeforeMillis`, `state`, `attemptCount`) " +
                    "VALUES ('accA', 'bob@example.org', 'Never sent', 'see you at six', '[]', 1, 2, 'QUEUED', 0)",
            )
            st.executeUpdate(
                "INSERT INTO `scheduled_sends` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`htmlBody`, `fromName`, `fromEmail`, `inReplyTo`, `references`, `sendAtMillis`) " +
                    "VALUES ('accA', 'bob@example.org', 'Later', 'body', NULL, NULL, NULL, NULL, NULL, 500)",
            )
            st.executeUpdate("INSERT INTO `mailboxes` VALUES ('accA', 'mb1', 'Inbox', 'inbox', NULL, 0, 2, 1)")
            st.executeUpdate("INSERT INTO `recent_contacts` VALUES ('bob@example.org', 'Bob', 7)")
            st.executeUpdate(
                "INSERT INTO `email_fts`(emailId, accountId, mailboxId, threadId, subject, sender, " +
                    "body, preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                    "VALUES ('e1', 'accA', 'mb1', NULL, 'Hello', 'Alex alex@example.org', '', NULL, " +
                    "NULL, 'Alex', 'alex@example.org', 0, 0, 0, 100)",
            )
        }
    }

    /** The rows the v18/v19 tables hold, so those are watched too. Call once the tables exist. */
    private fun fillTheV18AndV19Tables() {
        db.createStatement().use { st ->
            st.executeUpdate("INSERT INTO `purge_snapshot` VALUES ('p1', 'accA', 'trash', 'm1', 1, 77)")
            st.executeUpdate("INSERT INTO `mailbox_uidvalidity` VALUES ('accA', 'mb1', 77)")
        }
    }

    /**
     * A REAL v21 database: the published v15 schema, populated, then carried forward by the very
     */
    private fun seedV21() {
        seedPublishedV15()
        MIGRATION_15_16.migrate(supportDb())
        MIGRATION_16_17.migrate(supportDb())
        MIGRATION_17_18.migrate(supportDb())
        MIGRATION_18_19.migrate(supportDb())
        fillTheV18AndV19Tables()
        MIGRATION_19_20.migrate(supportDb())
        MIGRATION_20_21.migrate(supportDb())
    }

    private fun migrate21to22() = MIGRATION_21_22.migrate(supportDb())

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getInt(1)
        }
    }

    private fun rowCounts(): Map<String, Int> =
        allTables.associateWith { count("SELECT COUNT(*) FROM `$it`") }

    private fun columnsOf(table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) columns += rs.getString("name")
            }
        }
        return columns
    }

    /**
     * The DECLARED shape of each column: `type`, `notnull`, `dflt_value` from `PRAGMA table_info`.
     */
    private fun columnShapes(table: String): Map<String, Triple<String, Int, String?>> {
        val shapes = mutableMapOf<String, Triple<String, Int, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    shapes[rs.getString("name")] =
                        Triple(rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"))
                }
            }
        }
        return shapes
    }

    private fun intOf(sql: String): Int = count(sql)

    // --- the columns -----------------------------------------------------------------------------

    @Test fun v21to22_addsTheColumnToTheOutbox() {
        seedV21()
        migrate21to22()

        assertTrue(
            "`outbox.requestReceipt` is missing: a queued message loses the receipt it asked for " +
                "the moment the composer is gone",
            "requestReceipt" in columnsOf("outbox"),
        )
    }

    @Test fun v21to22_addsTheColumnToTheScheduledSendsToo() {
        // The slip this states on its own: a scheduled send waits in ITS table for hours and only
        // becomes an outbox row when the worker fires. Without the column here the request is lost
        // in that hand-over, and the mail goes out asking nothing.
        seedV21()
        migrate21to22()

        assertTrue(
            "`scheduled_sends.requestReceipt` is missing: the request dies when the scheduled " +
                "send is handed to the outbox",
            "requestReceipt" in columnsOf("scheduled_sends"),
        )
    }

    @Test fun v21to22_addsNothingElseToEitherTable() {
        seedV21()
        val outboxBefore = columnsOf("outbox")
        val scheduledBefore = columnsOf("scheduled_sends")

        migrate21to22()

        assertEquals(setOf("requestReceipt"), columnsOf("outbox") - outboxBefore)
        assertEquals(setOf("requestReceipt"), columnsOf("scheduled_sends") - scheduledBefore)
        // …and no other table is touched at all.
        assertEquals(
            "emails, snoozed and the rest have no business in this step",
            emptySet<String>(),
            columnsOf("emails") - setOf(
                "id", "accountId", "mailboxId", "threadId", "subject", "preview", "receivedAt",
                "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
                "recipientsJson", "replyToJson", "ccJson", "bccJson",
            ),
        )
    }

    // --- the stake: nothing is lost --------------------------------------------------------------

    @Test fun v21to22_everyRowOfEveryTableSurvives() {
        seedV21()
        val before = rowCounts()
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it == 0 }.keys.toList(),
        )

        migrate21to22()

        assertEquals(before, rowCounts())
    }

    @Test fun v21to22_theUnsentMailIsStillThereWordForWord() {
        seedV21()
        migrate21to22()

        // The two rows a destructive fallback destroys silently: a message queued in the outbox and
        // a message waiting for its hour. Both are mail the user wrote and that never left.
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND " +
                    "`textBody` = 'see you at six' AND `recipients` = 'bob@example.org' AND `state` = 'QUEUED'",
            ),
        )
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later' AND " +
                    "`textBody` = 'body' AND `recipients` = 'bob@example.org' AND `sendAtMillis` = 500",
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1' AND `subject` = 'Hello'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
    }

    @Test fun v21to22_leavesTheAutoincrementKeysAlone() {
        seedV21()
        migrate21to22()

        // The outbox id keys the durable attachment directory on disk; a rebuilt key is a message
        // that can no longer find its files.
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`outbox`)").use { rs ->
                var pk: String? = null
                while (rs.next()) if (rs.getInt("pk") == 1) pk = rs.getString("name")
                assertEquals("id", pk)
            }
        }
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `id` = 1"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `id` = 1"))
    }

    // --- what the new column reads back ----------------------------------------------------------

    @Test fun v21to22_rowsQueuedBeforeItReadBackAsAskingForNothing() {
        seedV21()
        migrate21to22()

        // No backfill and nothing to backfill from: a row queued under v21 never carried the
        // choice, and 0 is what it was queued as. NOT NULL, so it reads back as a value and not as
        // "unknown" — the delivery has no third answer to handle.
        assertEquals(1, intOf("SELECT COUNT(*) FROM `outbox` WHERE `requestReceipt` = 0"))
        assertEquals(1, intOf("SELECT COUNT(*) FROM `scheduled_sends` WHERE `requestReceipt` = 0"))
        assertEquals(0, intOf("SELECT COUNT(*) FROM `outbox` WHERE `requestReceipt` IS NULL"))
        assertEquals(0, intOf("SELECT COUNT(*) FROM `scheduled_sends` WHERE `requestReceipt` IS NULL"))
    }

    @Test fun v21to22_aRequestedReceiptRoundTripsThroughEachTable() {
        seedV21()
        migrate21to22()

        db.createStatement().use { st ->
            st.executeUpdate("UPDATE `outbox` SET `requestReceipt` = 1 WHERE `subject` = 'Never sent'")
        }

        assertEquals(1, intOf("SELECT `requestReceipt` FROM `outbox` WHERE `subject` = 'Never sent'"))
        // The two tables are two separate stores: writing one must not answer for the other.
        assertEquals(0, intOf("SELECT `requestReceipt` FROM `scheduled_sends` WHERE `subject` = 'Later'"))

        db.createStatement().use { st ->
            st.executeUpdate("UPDATE `scheduled_sends` SET `requestReceipt` = 1 WHERE `subject` = 'Later'")
        }
        assertEquals(1, intOf("SELECT `requestReceipt` FROM `scheduled_sends` WHERE `subject` = 'Later'"))
    }

    @Test fun v21to22_declaresBothColumnsAsNotNullIntegersDefaultingToZero() {
        seedV21()
        migrate21to22()

        // The entity field is a non-null Boolean, so the column must be NOT NULL; SQLite then
        // requires a DEFAULT to add it to a populated table. Room compares defaults only when the
        assertEquals(Triple("INTEGER", 1, "0"), columnShapes("outbox")["requestReceipt"])
        assertEquals(Triple("INTEGER", 1, "0"), columnShapes("scheduled_sends")["requestReceipt"])
        // The witness: the shape Room already expects for a non-null Int/Boolean here.
        assertEquals(1, columnShapes("outbox")["attemptCount"]!!.second)
        assertEquals("INTEGER", columnShapes("outbox")["attemptCount"]!!.first)
    }

    // --- the migration has to be REGISTERED, not merely written ----------------------------------

    /**
     * Chains the migrations Room is really given ([SternaDatabase.ALL_MIGRATIONS]) over a published
     */
    @Test fun theRegisteredMigrationsCarryAPublishedInstallAllTheWayToTheReceiptColumns() {
        seedPublishedV15()

        var current = 15
        SternaDatabase.ALL_MIGRATIONS
            .filter { it.startVersion >= 15 }
            .sortedBy { it.startVersion }
            .forEach { step ->
                assertEquals(
                    "the registered migrations must form an unbroken chain from v15; " +
                        "a gap is a destructive fallback",
                    current,
                    step.startVersion,
                )
                step.migrate(supportDb())
                current = step.endVersion
                if (current == 19) fillTheV18AndV19Tables()
            }

        assertEquals(
            "only the REGISTERED migrations ran: outbox.requestReceipt must be there, otherwise " +
                "Room has no 21→22 path and drops the whole database, unsent mail included",
            Triple("INTEGER", 1, "0"),
            columnShapes("outbox")["requestReceipt"],
        )
        assertEquals(
            "only the REGISTERED migrations ran: scheduled_sends.requestReceipt must be there too",
            Triple("INTEGER", 1, "0"),
            columnShapes("scheduled_sends")["requestReceipt"],
        )
        // And the chain that reaches them loses nothing on the way.
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND `state` = 'QUEUED'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
    }

    /**
     * Reads [SCHEMA_VERSION], the constant `@Database(version = …)` itself is written from — the
     * annotation is not retained at runtime, so it cannot be read reflectively.
     */
    @Test fun theRegisteredMigrationsReachTheDeclaredSchemaVersion() {
        val reached = SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion }

        // Bumping the version without registering its step is the destructive case: Room asks for a
        // path to the declared version and, finding none, drops every table. The converse — a step
        // registered while the version stays behind — is just as wrong: the step never runs, the
        // entity declares a column the schema has not got, and validateMigration fails at open.
        assertEquals(
            "the registered chain stops at v$reached while the database declares v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
        // The receipt columns are v22's, and they stay v22's however far the schema goes on: a step
        // renumbered after the fact would run against a database that has already had it.
        assertEquals("the receipt columns arrive at v22", 22, MIGRATION_21_22.endVersion)
    }
}
