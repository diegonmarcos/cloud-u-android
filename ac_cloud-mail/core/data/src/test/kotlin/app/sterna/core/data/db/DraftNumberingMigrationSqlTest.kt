package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * **v22 → v23** ([MIGRATION_22_23]): `outbox` and `scheduled_sends` each gain `draftUidValidity`,
 */
class DraftNumberingMigrationSqlTest {
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

    /** Every table a v22 database holds rows in, so "nothing was lost" can be measured, not hoped. */
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
            // and never sent — one row waiting in the outbox, one waiting for its hour. Both were
            // edited from a saved draft, which is what this column is about.
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e2', 'accA', 99999)")
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`attachmentsJson`, `createdAtMillis`, `notBeforeMillis`, `state`, " +
                    "`attemptCount`, `draftEmailId`) VALUES ('accA', 'bob@example.org', " +
                    "'Never sent', 'see you at six', '[]', 1, 2, 'QUEUED', 0, 'd1')",
            )
            st.executeUpdate(
                "INSERT INTO `scheduled_sends` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`htmlBody`, `fromName`, `fromEmail`, `inReplyTo`, `references`, `sendAtMillis`, " +
                    "`draftEmailId`) VALUES ('accA', 'bob@example.org', 'Later', 'body', NULL, NULL, " +
                    "NULL, NULL, NULL, 500, 'd2')",
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
     * A REAL v22 database: the published v15 schema, populated, then carried forward by the very
     */
    private fun seedV22() {
        seedPublishedV15()
        MIGRATION_15_16.migrate(supportDb())
        MIGRATION_16_17.migrate(supportDb())
        MIGRATION_17_18.migrate(supportDb())
        MIGRATION_18_19.migrate(supportDb())
        fillTheV18AndV19Tables()
        MIGRATION_19_20.migrate(supportDb())
        MIGRATION_20_21.migrate(supportDb())
        MIGRATION_21_22.migrate(supportDb())
    }

    private fun migrate22to23() = MIGRATION_22_23.migrate(supportDb())

    /** A REAL v24 database: [seedV22] carried up by the very migration objects that ship. */
    private fun seedV24() {
        seedV22()
        MIGRATION_22_23.migrate(supportDb())
        MIGRATION_23_24.migrate(supportDb())
    }

    private fun migrate24to25() = MIGRATION_24_25.migrate(supportDb())

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

    // --- the columns -----------------------------------------------------------------------------

    @Test fun v22to23_addsTheColumnToTheOutbox() {
        seedV22()
        migrate22to23()

        assertTrue(
            "`outbox.draftUidValidity` is missing: the numbering the composer froze cannot survive " +
                "the wait, and the delivery destroys the draft under whatever it reads at the time",
            "draftUidValidity" in columnsOf("outbox"),
        )
    }

    @Test fun v22to23_addsTheColumnToTheScheduledSendsToo() {
        // The slip this states on its own: a scheduled send waits in ITS table for hours and only
        // becomes an outbox row when the worker fires. Without the column here the frozen number is
        // lost in that hand-over, and the destroy goes out blind on a folder that may have been
        // renumbered meanwhile — expunging another message in Drafts, silently.
        seedV22()
        migrate22to23()

        assertTrue(
            "`scheduled_sends.draftUidValidity` is missing: the freeze dies when the scheduled " +
                "send is handed to the outbox",
            "draftUidValidity" in columnsOf("scheduled_sends"),
        )
    }

    @Test fun v22to23_addsNothingElseToEitherTable() {
        seedV22()
        val outboxBefore = columnsOf("outbox")
        val scheduledBefore = columnsOf("scheduled_sends")

        migrate22to23()

        assertEquals(setOf("draftUidValidity"), columnsOf("outbox") - outboxBefore)
        assertEquals(setOf("draftUidValidity"), columnsOf("scheduled_sends") - scheduledBefore)
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

    @Test fun v22to23_everyRowOfEveryTableSurvives() {
        seedV22()
        val before = rowCounts()
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it == 0 }.keys.toList(),
        )

        migrate22to23()

        assertEquals(before, rowCounts())
    }

    @Test fun v22to23_theUnsentMailIsStillThereWordForWord() {
        seedV22()
        migrate22to23()

        // The two rows a destructive fallback destroys silently: a message queued in the outbox and
        // a message waiting for its hour. Both are mail the user wrote and that never left — and
        // both were edited from a draft, so both carry the id this column belongs to.
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND " +
                    "`textBody` = 'see you at six' AND `recipients` = 'bob@example.org' AND " +
                    "`state` = 'QUEUED' AND `draftEmailId` = 'd1'",
            ),
        )
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later' AND " +
                    "`textBody` = 'body' AND `sendAtMillis` = 500 AND `draftEmailId` = 'd2'",
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1' AND `subject` = 'Hello'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
    }

    @Test fun v22to23_leavesTheAutoincrementKeysAlone() {
        seedV22()
        migrate22to23()

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

    @Test fun v22to23_rowsQueuedBeforeItReadBackAsNullAndNotAsZero() {
        seedV22()
        migrate22to23()

        // NULL, deliberately, and NOT backfilled: nobody knows which numbering those rows were
        // enqueued under, and `null` destroys nothing (`UidValidity.destroyableUnderNumbering`).
        // 0 would be worse than useless — it is a numbering "the server never announced", and a
        // column defaulting to it would look like an answer while meaning "unknown".
        assertNull(longOrNull("SELECT `draftUidValidity` FROM `outbox` WHERE `subject` = 'Never sent'"))
        assertNull(longOrNull("SELECT `draftUidValidity` FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `draftUidValidity` IS NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `draftUidValidity` IS NULL"))
    }

    @Test fun v22to23_aFrozenNumberingRoundTripsThroughEachTable() {
        seedV22()
        migrate22to23()

        db.createStatement().use { st ->
            st.executeUpdate("UPDATE `outbox` SET `draftUidValidity` = 42 WHERE `subject` = 'Never sent'")
        }

        assertEquals(42L, longOrNull("SELECT `draftUidValidity` FROM `outbox` WHERE `subject` = 'Never sent'"))
        // The two tables are two separate stores: writing one must not answer for the other.
        assertNull(longOrNull("SELECT `draftUidValidity` FROM `scheduled_sends` WHERE `subject` = 'Later'"))

        db.createStatement().use { st ->
            st.executeUpdate("UPDATE `scheduled_sends` SET `draftUidValidity` = 4294967295 WHERE `subject` = 'Later'")
        }
        // A UIDVALIDITY is a 32-bit UNSIGNED integer: the column must hold 4294967295 as itself.
        assertEquals(
            4294967295L,
            longOrNull("SELECT `draftUidValidity` FROM `scheduled_sends` WHERE `subject` = 'Later'"),
        )
    }

    @Test fun v22to23_declaresBothColumnsAsNullableIntegersWithNoDefault() {
        seedV22()
        migrate22to23()

        // The entity field is a `Long?`, so the column must be NULLABLE (`notnull` = 0) with no
        // default — the shape of `draftEmailId TEXT`, not that of `requestReceipt INTEGER NOT NULL
        // DEFAULT 0`. Room compares affinity and nullability at open time: NOT NULL here fails
        // `validateMigration` on every launch, and the user's only way out takes the outbox with it.
        assertEquals(Triple("INTEGER", 0, null), columnShapes("outbox")["draftUidValidity"])
        assertEquals(Triple("INTEGER", 0, null), columnShapes("scheduled_sends")["draftUidValidity"])
        // The witnesses: the shape of the nullable column beside it, and of a non-null one.
        assertEquals(Triple("TEXT", 0, null), columnShapes("outbox")["draftEmailId"])
        assertEquals(1, columnShapes("outbox")["attemptCount"]!!.second)
    }

    // --- the migration has to be REGISTERED, not merely written ----------------------------------

    /**
     * Chains the migrations Room is really given ([SternaDatabase.ALL_MIGRATIONS]) over a published
     */
    @Test fun theRegisteredMigrationsCarryAPublishedInstallAllTheWayToTheFrozenNumbering() {
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
            "only the REGISTERED migrations ran: outbox.draftUidValidity must be there, otherwise " +
                "Room has no 22→23 path and drops the whole database, unsent mail included",
            Triple("INTEGER", 0, null),
            columnShapes("outbox")["draftUidValidity"],
        )
        assertEquals(
            "only the REGISTERED migrations ran: scheduled_sends.draftUidValidity must be there too",
            Triple("INTEGER", 0, null),
            columnShapes("scheduled_sends")["draftUidValidity"],
        )
        assertEquals(
            "and the chain must still run 24→25: emails.uidValidity must be there, otherwise Room " +
                "has no path and drops the whole database — outbox, scheduled_sends and local_drafts",
            Triple("INTEGER", 0, null),
            columnShapes("emails")["uidValidity"],
        )
        // And the chain that reaches them loses nothing on the way.
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND `state` = 'QUEUED'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
    }

    /**
     * SOURCE LINT, and the one line every migration in this file depends on: the builder must
     */
    @Test fun theBuilderReallyHandsThatArrayToRoom() {
        val code = STERNA_DATABASE.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        assertEquals(
            "SternaDatabase.build must call `.addMigrations(*ALL_MIGRATIONS)` — the array is only a " +
                "decision when Room is handed it. Missing, every migration in this file is dead " +
                "code and the fallback wipes the outbox on the next launch",
            listOf(".addMigrations(*ALL_MIGRATIONS)"),
            code.filter { "addMigrations(" in it },
        )
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
        // ⛔ There was a second assertion here pinning SCHEMA_VERSION to the literal 27. It is gone
        // on purpose. It restated the version this branch happened to land on, so every later
        // migration made it false — and because FOUR migration testers each carried their own copy,
        // v27 → v28 failed all four at once and held every Cloud Mail APK behind a red pipeline.
        // The rule above is the one worth having and it maintains itself: whatever the database
        // declares, the registered chain must reach it. A literal repeated per branch cannot.
    }

    // --- v24 → v25: the numbering each cached row was read under ---------------------------------

    @Test fun v24to25_addsTheColumnToTheCachedRows() {
        seedV24()
        migrate24to25()

        assertTrue(
            "`emails.uidValidity` is missing: a cached row cannot say which numbering its UID " +
                "belongs to, so a permanent delete has only the folder's record to oppose — and a " +
                "background pass that met a renumbering has already realigned that record (#99)",
            "uidValidity" in columnsOf("emails"),
        )
    }

    @Test fun v24to25_addsNothingElseAnywhere() {
        seedV24()
        val emailsBefore = columnsOf("emails")
        val outboxBefore = columnShapes("outbox")
        val scheduledBefore = columnShapes("scheduled_sends")
        val localDraftsBefore = columnShapes("local_drafts")

        migrate24to25()

        assertEquals(setOf("uidValidity"), columnsOf("emails") - emailsBefore)
        // The three stores that hold text the user typed have no business in a cache step.
        assertEquals("the outbox is not touched", outboxBefore, columnShapes("outbox"))
        assertEquals("nor the scheduled sends", scheduledBefore, columnShapes("scheduled_sends"))
        assertEquals("nor the local drafts", localDraftsBefore, columnShapes("local_drafts"))
    }

    @Test fun v24to25_everyRowOfEveryTableSurvives() {
        seedV24()
        val before = rowCounts()
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it == 0 }.keys.toList(),
        )

        migrate24to25()

        assertEquals(before, rowCounts())
        // The cache the column is added to keeps its rows word for word, and so does the unsent mail.
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1' AND `subject` = 'Hello'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND " +
                    "`textBody` = 'see you at six' AND `state` = 'QUEUED'",
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
    }

    @Test fun v24to25_rowsCachedBeforeItReadBackAsNullAndNotAsZero() {
        seedV24()
        migrate24to25()

        // NULL, deliberately, and NOT backfilled with the folder's current numbering: nobody
        // knows which numbering those rows were read under, and inventing one would license exactly
        assertNull(longOrNull("SELECT `uidValidity` FROM `emails` WHERE `id` = 'e1'"))
        assertEquals(2, count("SELECT COUNT(*) FROM `emails` WHERE `uidValidity` IS NULL"))
    }

    @Test fun v24to25_aNumberingRoundTripsOnTheRow() {
        seedV24()
        migrate24to25()

        db.createStatement().use { st ->
            st.executeUpdate("UPDATE `emails` SET `uidValidity` = 42 WHERE `id` = 'e1'")
        }

        assertEquals(42L, longOrNull("SELECT `uidValidity` FROM `emails` WHERE `id` = 'e1'"))
        // Per ROW, which is the whole point: stamping one row must not answer for its neighbour in
        // the same folder — that is precisely how two numberings coexist in one list after a
        // renumbering nothing pruned.
        assertNull(longOrNull("SELECT `uidValidity` FROM `emails` WHERE `id` = 'e2'"))

        db.createStatement().use { st ->
            st.executeUpdate("UPDATE `emails` SET `uidValidity` = 4294967295 WHERE `id` = 'e2'")
        }
        // A UIDVALIDITY is a 32-bit UNSIGNED integer: the column must hold 4294967295 as itself.
        assertEquals(4294967295L, longOrNull("SELECT `uidValidity` FROM `emails` WHERE `id` = 'e2'"))
    }

    @Test fun v24to25_declaresTheColumnAsANullableIntegerWithNoDefault() {
        seedV24()
        migrate24to25()

        // The entity field is a `Long?`, so the column must be NULLABLE (`notnull` = 0) with no
        // default. Room compares affinity and nullability at open time: NOT NULL here fails
        // `validateMigration` on every launch, and the user's only way out is a reinstall — which
        // takes the outbox, the scheduled sends and the local drafts with it.
        assertEquals(Triple("INTEGER", 0, null), columnShapes("emails")["uidValidity"])
        // The witnesses: a nullable text column beside it, and a non-null one.
        assertEquals(Triple("TEXT", 0, null), columnShapes("emails")["recipientsJson"])
        assertEquals(1, columnShapes("emails")["sortKey"]!!.second)
    }

    private companion object {
        private const val STERNA_DATABASE_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/db/SternaDatabase.kt"

        private val root: java.io.File by lazy {
            generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .firstOrNull { java.io.File(it, STERNA_DATABASE_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${java.io.File("").absolutePath} — the " +
                        "builder rule reads the source as text and needs a working directory " +
                        "inside the checkout",
                )
        }

        val STERNA_DATABASE: java.io.File by lazy { java.io.File(root, STERNA_DATABASE_PATH) }
    }
}
