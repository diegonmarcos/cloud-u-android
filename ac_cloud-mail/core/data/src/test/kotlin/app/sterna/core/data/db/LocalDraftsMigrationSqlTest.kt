package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * **v23 → v24** ([MIGRATION_23_24]): the `local_drafts` table (#95), where a draft saved without a
 */
class LocalDraftsMigrationSqlTest {
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
     * Every table a v23 database holds rows in, so "nothing was lost" can be measured, not hoped.
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
     * A REAL v23 database: the published v15 schema, populated, then carried forward by the very
     */
    private fun seedV23() {
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
    }

    private fun migrate23to24() = MIGRATION_23_24.migrate(supportDb())

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getInt(1)
        }
    }

    private fun rowCounts(): Map<String, Int> =
        allTables.associateWith { count("SELECT COUNT(*) FROM `$it`") }

    private fun tableNames(): Set<String> {
        val names = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                while (rs.next()) names += rs.getString(1)
            }
        }
        return names
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

    /** The primary-key columns of [table], in KEY order — `pk` is 1-based, not a boolean. */
    private fun primaryKeyOf(table: String): List<String> {
        val key = mutableListOf<Pair<Int, String>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    val position = rs.getInt("pk")
                    if (position > 0) key += position to rs.getString("name")
                }
            }
        }
        return key.sortedBy { it.first }.map { it.second }
    }

    /** One local draft, written with plain SQL — the columns the entity declares non-null. */
    private fun insertDraft(
        accountId: String,
        id: String,
        subject: String = "Half written",
        textBody: String = "the sentence that must not die",
    ) {
        db.prepareStatement(
            "INSERT INTO `local_drafts` (`accountId`, `id`, `messageId`, `toAddresses`, `subject`, " +
                "`textBody`, `attachmentsJson`, `bodyIsLossy`, `requestReceipt`, `createdAtMillis`, " +
                "`updatedAtMillis`, `notBeforeMillis`, `attemptCount`, `state`) " +
                "VALUES (?, ?, 'mid@example.org', 'bob@example.org', ?, ?, '[]', 0, 0, 1, 1, 0, 0, 'PENDING')",
        ).use {
            it.setString(1, accountId); it.setString(2, id)
            it.setString(3, subject); it.setString(4, textBody)
            it.executeUpdate()
        }
    }

    // --- the table -------------------------------------------------------------------------------

    @Test fun v23to24_createsTheLocalDraftsTable() {
        seedV23()
        assertTrue("v23 must not already know the table this step adds", "local_drafts" !in tableNames())

        migrate23to24()

        assertTrue("local_drafts is missing: a draft saved offline has nowhere to go", "local_drafts" in tableNames())
        assertEquals(
            "the table is created EMPTY — a v23 install kept no draft locally",
            0, count("SELECT COUNT(*) FROM `local_drafts`"),
        )
    }

    @Test fun v23to24_declaresEveryColumnWithTheShapeTheEntityNeeds() {
        // Room compares affinity and nullability at open time (TableInfo.Column.equals). A single
        // wrong one fails validateMigration on EVERY launch, and the only way out a user has is a
        // reinstall — which destroys the outbox. No column carries a DEFAULT: the table is created
        // empty, and the entity declares no @ColumnInfo(defaultValue = …) to compare one against.
        seedV23()
        migrate23to24()

        assertEquals(
            mapOf(
                "accountId" to Triple("TEXT", 1, null),
                "id" to Triple("TEXT", 1, null),
                "messageId" to Triple("TEXT", 1, null),
                "toAddresses" to Triple("TEXT", 1, null),
                "cc" to Triple("TEXT", 0, null),
                "bcc" to Triple("TEXT", 0, null),
                "subject" to Triple("TEXT", 1, null),
                "textBody" to Triple("TEXT", 1, null),
                "htmlBody" to Triple("TEXT", 0, null),
                "fromName" to Triple("TEXT", 0, null),
                "fromEmail" to Triple("TEXT", 0, null),
                "inReplyTo" to Triple("TEXT", 0, null),
                "references" to Triple("TEXT", 0, null),
                "attachmentsJson" to Triple("TEXT", 1, null),
                "replacesEmailId" to Triple("TEXT", 0, null),
                "replacesUidValidity" to Triple("INTEGER", 0, null),
                "bodyIsLossy" to Triple("INTEGER", 1, null),
                "requestReceipt" to Triple("INTEGER", 1, null),
                "createdAtMillis" to Triple("INTEGER", 1, null),
                "updatedAtMillis" to Triple("INTEGER", 1, null),
                "notBeforeMillis" to Triple("INTEGER", 1, null),
                "attemptCount" to Triple("INTEGER", 1, null),
                "lastError" to Triple("TEXT", 0, null),
                "lastAttemptMillis" to Triple("INTEGER", 0, null),
                "state" to Triple("TEXT", 1, null),
            ),
            columnShapes("local_drafts"),
        )
    }

    @Test fun v23to24_keysTheTableOnTheAccountAndTheIdInThatOrder() {
        seedV23()
        migrate23to24()

        assertEquals(
            "the key must be composite and account-first, like `emails` since #31",
            listOf("accountId", "id"),
            primaryKeyOf("local_drafts"),
        )
    }

    @Test fun v23to24_letsTwoAccountsHoldTheSameDraftId() {
        // The key, EXECUTED rather than read: two accounts of one server mint ids in their own
        // space (#31). Under a single-column key the second write REPLACES the first and one of the
        // two users loses the text they typed — silently, since an upsert reports nothing.
        seedV23()
        migrate23to24()

        insertDraft("accA", "local-draft:same", textBody = "written from the first account")
        insertDraft("accB", "local-draft:same", textBody = "written from the second account")

        assertEquals(2, count("SELECT COUNT(*) FROM `local_drafts` WHERE `id` = 'local-draft:same'"))
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `local_drafts` WHERE `accountId` = 'accA' AND " +
                    "`textBody` = 'written from the first account'",
            ),
        )
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `local_drafts` WHERE `accountId` = 'accB' AND " +
                    "`textBody` = 'written from the second account'",
            ),
        )
    }

    @Test fun v23to24_refusesADraftWithNoBodyAtAll() {
        // NOT NULL, executed. The entity field is a non-null String: a NULL that the schema let in
        // would be read back as a null into a `String`, which throws where the draft is opened —
        // and the text is unreachable either way. There is no "unknown body": an empty draft is ''.
        seedV23()
        migrate23to24()

        val refused = assertThrows(SQLException::class.java) {
            db.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO `local_drafts` (`accountId`, `id`, `messageId`, `toAddresses`, " +
                        "`subject`, `textBody`, `attachmentsJson`, `bodyIsLossy`, `requestReceipt`, " +
                        "`createdAtMillis`, `updatedAtMillis`, `notBeforeMillis`, `attemptCount`, `state`) " +
                        "VALUES ('accA', 'local-draft:1', 'mid@example.org', 'bob@example.org', 's', " +
                        "NULL, '[]', 0, 0, 1, 1, 0, 0, 'PENDING')",
                )
            }
        }
        assertTrue(
            "the engine refused for some other reason than the NOT NULL body: ${refused.message}",
            refused.message.orEmpty().contains("NOT NULL", ignoreCase = true),
        )
        // …and an empty body is a perfectly good draft, which is why the column is NOT NULL and not
        // "required to be non-blank".
        insertDraft("accA", "local-draft:2", textBody = "")
        assertEquals(1, count("SELECT COUNT(*) FROM `local_drafts` WHERE `textBody` = ''"))
    }

    // --- the stake: nothing is lost --------------------------------------------------------------

    @Test fun v23to24_everyRowOfEveryTableSurvives() {
        seedV23()
        val before = rowCounts()
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it == 0 }.keys.toList(),
        )

        migrate23to24()

        assertEquals(before, rowCounts())
    }

    @Test fun v23to24_theUnsentMailIsStillThereWordForWord() {
        seedV23()
        migrate23to24()

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

    @Test fun v23to24_addsTheTableAndTouchesNothingElse() {
        seedV23()
        val before = tableNames()
        val outboxBefore = columnShapes("outbox")
        val emailsBefore = columnShapes("emails")

        migrate23to24()

        assertEquals(setOf("local_drafts"), tableNames() - before)
        assertEquals(emptySet<String>(), before - tableNames())
        assertEquals("the step has no business altering the outbox", outboxBefore, columnShapes("outbox"))
        assertEquals("nor the cache", emailsBefore, columnShapes("emails"))
    }

    // --- the migration has to be REGISTERED, not merely written ----------------------------------

    /**
     * Chains the migrations Room is really given ([SternaDatabase.ALL_MIGRATIONS]) over a published
     */
    @Test fun theRegisteredMigrationsCarryAPublishedInstallAllTheWayToTheLocalDraftsTable() {
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

        assertTrue(
            "only the REGISTERED migrations ran, and they did not create local_drafts — so Room has " +
                "no 23→24 path and drops the whole database on upgrade, unsent mail included",
            "local_drafts" in tableNames(),
        )
        assertEquals(listOf("accountId", "id"), primaryKeyOf("local_drafts"))
        assertEquals(Triple("TEXT", 1, null), columnShapes("local_drafts")["textBody"])
        // And the chain that reaches it loses nothing on the way.
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
        // entity declares a table the schema has not got, and validateMigration fails at open.
        assertEquals(
            "the registered chain stops at v$reached while the database declares v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
        assertEquals("the schema now declares v27 (the emails index (accountId, mailboxId, sortKey))", 27, SCHEMA_VERSION)
    }
}
