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
 * **v25 → v26** ([MIGRATION_25_26]): `mailboxes` gains `isSubscribed`, what the server answered
 */
class SubscribedFolderMigrationSqlTest {
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

    /** Every table a v25 database holds rows in, so "nothing was lost" can be measured, not hoped. */
    private val allTables = listOf(
        "emails", "email_bodies", "snoozed", "mailboxes", "outbox", "scheduled_sends",
        "recent_contacts", "email_fts", "purge_snapshot", "mailbox_uidvalidity", "local_drafts",
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
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e1', 'accA', 'mb1', 'Hello', 0, 0, 0, 100)",
            )
            st.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'accA', '{}', '{}', 42)")
            // The user data a destructive fallback destroys: a snooze, and above all mail written
            // and never sent — one row waiting in the outbox, one waiting for its hour.
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e1', 'accA', 99999)")
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`attachmentsJson`, `createdAtMillis`, `notBeforeMillis`, `state`, " +
                    "`attemptCount`) VALUES ('accA', 'bob@example.org', " +
                    "'Never sent', 'see you at six', '[]', 1, 2, 'QUEUED', 0)",
            )
            st.executeUpdate(
                "INSERT INTO `scheduled_sends` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`sendAtMillis`) VALUES ('accA', 'bob@example.org', 'Later', 'body', 500)",
            )
            // The rows this step is about: folders cached by an install that never heard of
            // subscription. Two of them, so "the whole table reads back subscribed" can be said.
            st.executeUpdate("INSERT INTO `mailboxes` VALUES ('accA', 'mb1', 'Inbox', 'inbox', NULL, 0, 2, 1)")
            st.executeUpdate("INSERT INTO `mailboxes` VALUES ('accA', 'mb2', 'Projekte', NULL, NULL, 6001, 4, 0)")
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

    /** The draft that only exists on the phone — the third store a destructive fallback empties. */
    private fun fillTheV24Table() {
        db.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO `local_drafts` (`accountId`, `id`, `messageId`, `toAddresses`, " +
                    "`subject`, `textBody`, `attachmentsJson`, `bodyIsLossy`, `requestReceipt`, " +
                    "`createdAtMillis`, `updatedAtMillis`, `notBeforeMillis`, `attemptCount`, `state`) " +
                    "VALUES ('accA', 'local-draft:1', 'mid@example.org', 'bob@example.org', " +
                    "'Only here', 'never sent anywhere', '[]', 0, 0, 1, 1, 0, 0, 'PENDING')",
            )
        }
    }

    /**
     * A REAL v25 database: the published v15 schema, populated, then carried forward by the very
     */
    private fun seedV25() {
        seedPublishedV15()
        MIGRATION_15_16.migrate(supportDb())
        MIGRATION_16_17.migrate(supportDb())
        MIGRATION_17_18.migrate(supportDb())
        MIGRATION_18_19.migrate(supportDb())
        fillTheV18AndV19Tables()
        MIGRATION_19_20.migrate(supportDb())
        MIGRATION_20_21.migrate(supportDb())
        MIGRATION_21_22.migrate(supportDb())
        MIGRATION_22_23.migrate(supportDb())
        MIGRATION_23_24.migrate(supportDb())
        fillTheV24Table()
        MIGRATION_24_25.migrate(supportDb())
    }

    private fun migrate25to26() = MIGRATION_25_26.migrate(supportDb())

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getInt(1)
        }
    }

    /** The value of a single-cell query, or null — `getLong` alone reads a SQL NULL as 0. */
    private fun longOrNull(sql: String): Long? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue("no row for: $sql", rs.next())
            val value = rs.getLong(1)
            if (rs.wasNull()) null else value
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

    // --- (a) the column ---------------------------------------------------------------------------

    @Test fun v25to26_addsTheColumnToTheCachedFolders() {
        seedV25()
        migrate25to26()

        assertTrue(
            "`mailboxes.isSubscribed` is missing: a cached folder cannot say whether the server " +
                "reports it subscribed, so nothing later can hide the ones that are not",
            "isSubscribed" in columnsOf("mailboxes"),
        )
    }

    @Test fun v25to26_addsNothingElseAnywhere() {
        seedV25()
        val mailboxesBefore = columnsOf("mailboxes")
        val emailsBefore = columnShapes("emails")
        val outboxBefore = columnShapes("outbox")
        val scheduledBefore = columnShapes("scheduled_sends")
        val localDraftsBefore = columnShapes("local_drafts")

        migrate25to26()

        assertEquals(setOf("isSubscribed"), columnsOf("mailboxes") - mailboxesBefore)
        assertEquals("the cached mail is not touched", emailsBefore, columnShapes("emails"))
        // The three stores that hold text the user typed have no business in a drawer step.
        assertEquals("the outbox is not touched", outboxBefore, columnShapes("outbox"))
        assertEquals("nor the scheduled sends", scheduledBefore, columnShapes("scheduled_sends"))
        assertEquals("nor the local drafts", localDraftsBefore, columnShapes("local_drafts"))
    }

    @Test fun v25to26_everyRowOfEveryTableSurvives() {
        seedV25()
        val before = rowCounts()
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it == 0 }.keys.toList(),
        )

        migrate25to26()

        assertEquals(before, rowCounts())
        assertEquals(1, count("SELECT COUNT(*) FROM `mailboxes` WHERE `id` = 'mb2' AND `name` = 'Projekte'"))
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND " +
                    "`textBody` = 'see you at six' AND `state` = 'QUEUED'",
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM `local_drafts` WHERE `subject` = 'Only here'"))
    }

    // --- (b) what a row cached BEFORE the step reads back ------------------------------------------

    /**
     * The decision this migration carries. A folder cached before v26 was cached by a build that
     */
    @Test fun aFolderCachedBeforeTheStepReadsBackSubscribed() {
        seedV25()
        migrate25to26()

        assertEquals(
            "a folder cached before v26 must read back subscribed (1): nobody asked the server " +
                "about it, and an unknown subscription must never hide a folder",
            1L,
            longOrNull("SELECT `isSubscribed` FROM `mailboxes` WHERE `id` = 'mb1'"),
        )
        assertEquals(
            "every folder of the table, not just the one with a role",
            2,
            count("SELECT COUNT(*) FROM `mailboxes` WHERE `isSubscribed` = 1"),
        )
        assertEquals(0, count("SELECT COUNT(*) FROM `mailboxes` WHERE `isSubscribed` = 0"))
    }

    @Test fun v25to26_declaresANonNullIntegerDefaultingToOne() {
        seedV25()
        migrate25to26()

        // The entity field is a non-null `Boolean`, and SQLite requires a DEFAULT to add a NOT NULL
        // column to a populated table. Room compares defaults at open time only when the ENTITY
        assertEquals(Triple("INTEGER", 1, "1"), columnShapes("mailboxes")["isSubscribed"])
        // The witnesses: a nullable column beside it, and a non-null one with no default.
        assertEquals(Triple("TEXT", 0, null), columnShapes("mailboxes")["role"])
        assertEquals(Triple("INTEGER", 1, null), columnShapes("mailboxes")["unreadEmails"])
    }

    @Test fun anUnsubscribedFolderRoundTripsOnTheRow() {
        seedV25()
        migrate25to26()

        db.createStatement().use { st ->
            st.executeUpdate("UPDATE `mailboxes` SET `isSubscribed` = 0 WHERE `id` = 'mb2'")
        }

        assertEquals(0L, longOrNull("SELECT `isSubscribed` FROM `mailboxes` WHERE `id` = 'mb2'"))
        // Per ROW: marking one folder must not answer for its neighbour in the same account.
        assertEquals(1L, longOrNull("SELECT `isSubscribed` FROM `mailboxes` WHERE `id` = 'mb1'"))
    }

    // --- (c) the step has to be REGISTERED, not merely written -------------------------------------

    /**
     * Chains the migrations Room is really given ([SternaDatabase.ALL_MIGRATIONS]) over a published
     */
    @Test fun theRegisteredMigrationsCarryAPublishedInstallToTheSubscriptionColumn() {
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
                if (current == 24) fillTheV24Table()
            }

        assertEquals(
            "only the REGISTERED migrations ran: `mailboxes.isSubscribed` must be there, otherwise " +
                "Room has no 25→26 path and DROPS the whole database — outbox, scheduled_sends and " +
                "local_drafts included",
            Triple("INTEGER", 1, "1"),
            columnShapes("mailboxes")["isSubscribed"],
        )
        // And the chain that reaches it loses nothing on the way.
        assertEquals(2, count("SELECT COUNT(*) FROM `mailboxes`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND `state` = 'QUEUED'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `local_drafts` WHERE `subject` = 'Only here'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e1' AND `until` = 99999"))
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
        assertEquals(
            "the schema declares v27: `emails` gains the (accountId, mailboxId, sortKey) index",
            27,
            SCHEMA_VERSION,
        )
    }
}
