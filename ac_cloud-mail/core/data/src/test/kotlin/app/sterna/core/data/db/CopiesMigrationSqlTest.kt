package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.jmap.model.EmailAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * **v20 → v21** ([MIGRATION_20_21]): `emails` gains `ccJson` and `bccJson`, the recipients put in
 */
class CopiesMigrationSqlTest {
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

    /** Every table a v20 database holds rows in, so "nothing was lost" can be measured, not hoped. */
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
            // The user data a destructive fallback destroys: a snooze, and above all a QUEUED
            // outbox row — mail the user wrote and that has never left the phone.
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
     * A REAL v20 database: the published v15 schema, populated, then carried forward by the very
     */
    private fun seedV20() {
        seedPublishedV15()
        MIGRATION_15_16.migrate(supportDb())
        MIGRATION_16_17.migrate(supportDb())
        MIGRATION_17_18.migrate(supportDb())
        MIGRATION_18_19.migrate(supportDb())
        fillTheV18AndV19Tables()
        MIGRATION_19_20.migrate(supportDb())
    }

    private fun migrate20to21() = MIGRATION_20_21.migrate(supportDb())

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
     * The DECLARED shape of each column: `type`, `notnull`, `dflt_value` from `PRAGMA table_info`,
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

    /** PRAGMA reports the pk position (1-based) per column; 0 means "not part of the PK". */
    private fun pkPositions(table: String): Map<String, Int> {
        val pkPos = mutableMapOf<String, Int>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) pkPos[rs.getString("name")] = rs.getInt("pk")
            }
        }
        return pkPos
    }

    private fun stringOrNull(sql: String): String? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getString(1)
        }
    }

    // --- the columns -----------------------------------------------------------------------------

    @Test fun v20to21_addsExactlyTheTwoCopyColumns() {
        seedV20()
        migrate20to21()

        assertEquals(
            setOf(
                "id", "accountId", "mailboxId", "threadId", "subject", "preview", "receivedAt",
                "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
                "recipientsJson", "replyToJson", "ccJson", "bccJson",
            ),
            columnsOf("emails"),
        )
    }

    /**
     * Each column on its own line, named in its own assertion. Adding one of the two and forgetting
     */
    @Test fun v20to21_addsTheCcColumnAndTheBccColumn_separately() {
        seedV20()
        migrate20to21()

        assertTrue("`ccJson` is missing: a draft reopens with its Cc emptied", "ccJson" in columnsOf("emails"))
        assertTrue("`bccJson` is missing: a draft reopens with its Bcc emptied", "bccJson" in columnsOf("emails"))
    }

    // --- the stake: nothing is lost --------------------------------------------------------------

    @Test fun v20to21_everyRowOfEveryTableSurvives() {
        seedV20()
        val before = rowCounts()
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it == 0 }.keys.toList(),
        )

        migrate20to21()

        assertEquals(before, rowCounts())
    }

    @Test fun v20to21_theUnsentMailAndTheSnoozeAreStillThereWordForWord() {
        seedV20()
        migrate20to21()

        // A destructive fallback would take these two with it, silently: an outbox row is mail the
        // user wrote and that never left, a snooze is a message hidden until a chosen hour.
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND " +
                    "`textBody` = 'see you at six' AND `recipients` = 'bob@example.org' AND `state` = 'QUEUED'",
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `accountId` = 'accA' AND `until` = 99999"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later' AND `sendAtMillis` = 500"))
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1' AND `subject` = 'Hello' AND `sortKey` = 100"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_bodies` WHERE `id` = 'e1' AND `fetchedAt` = 42"))
        assertEquals(1, count("SELECT COUNT(*) FROM `purge_snapshot` WHERE `purgeId` = 'p1' AND `uidValidity` = 77"))
        assertEquals(1, count("SELECT COUNT(*) FROM `mailbox_uidvalidity` WHERE `mailboxId` = 'mb1' AND `uidValidity` = 77"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
    }

    @Test fun v20to21_leavesTheCompositeKeyAndTheIndexAlone() {
        seedV20()
        migrate20to21()

        // Room re-checks keys and indexes at open time; a mismatch is itself a destructive fallback.
        assertEquals(1, pkPositions("emails")["accountId"])
        assertEquals(2, pkPositions("emails")["id"])
        assertEquals(0, pkPositions("emails")["ccJson"])
        assertEquals(0, pkPositions("emails")["bccJson"])
        val indexes = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`emails`)").use { rs ->
                while (rs.next()) indexes += rs.getString("name")
            }
        }
        assertTrue("index_emails_mailboxId must survive the ALTERs", "index_emails_mailboxId" in indexes)
    }

    // --- what the new columns read back ------------------------------------------------------------

    @Test fun v20to21_preExistingRowsReadBackAsNobodyInCopy() {
        seedV20()
        migrate20to21()

        // No backfill: the addresses are not held locally, so an old row is NULL until its next
        // sync, and NULL decodes to "nobody in copy" — the answer this cache already gave.
        assertEquals(2, count("SELECT COUNT(*) FROM `emails` WHERE `ccJson` IS NULL"))
        assertEquals(2, count("SELECT COUNT(*) FROM `emails` WHERE `bccJson` IS NULL"))
        assertEquals(
            emptyList<EmailAddress>(),
            EmailRecipients.decode(stringOrNull("SELECT `ccJson` FROM `emails` WHERE `id` = 'e1'")),
        )
        assertEquals(
            emptyList<EmailAddress>(),
            EmailRecipients.decode(stringOrNull("SELECT `bccJson` FROM `emails` WHERE `id` = 'e1'")),
        )
    }

    /**
     * The four addressing columns are four distinct stores. Written with four DIFFERENT address
     */
    @Test fun v20to21_theFourAddressColumnsStayFourSeparateStores() {
        seedV20()
        migrate20to21()

        val bob = listOf(EmailAddress(name = "Bob", email = "bob@example.org"))
        val carol = listOf(EmailAddress(name = "Carol", email = "carol@example.org"))
        val dave = listOf(EmailAddress(name = "Dave", email = "dave@example.org"))
        val support = listOf(EmailAddress(email = "support@example.org"))
        db.prepareStatement(
            "UPDATE `emails` SET `recipientsJson` = ?, `ccJson` = ?, `bccJson` = ?, " +
                "`replyToJson` = ? WHERE `id` = 'e1'",
        ).use { st ->
            st.setString(1, EmailRecipients.encode(bob))
            st.setString(2, EmailRecipients.encode(carol))
            st.setString(3, EmailRecipients.encode(dave))
            st.setString(4, EmailRecipients.encode(support))
            st.executeUpdate()
        }

        fun read(column: String) =
            EmailRecipients.decode(stringOrNull("SELECT `$column` FROM `emails` WHERE `id` = 'e1'"))

        assertEquals(bob, read("recipientsJson"))
        assertEquals(carol, read("ccJson"))
        assertEquals(dave, read("bccJson"))
        assertEquals(support, read("replyToJson"))
        // The blind copy is the one that must never surface anywhere but its own column.
        assertNotEquals(read("bccJson"), read("ccJson"))
        assertNotEquals(read("bccJson"), read("recipientsJson"))
        // …and the other row is untouched by all of it.
        assertEquals(null, stringOrNull("SELECT `ccJson` FROM `emails` WHERE `id` = 'e2'"))
        assertEquals(null, stringOrNull("SELECT `bccJson` FROM `emails` WHERE `id` = 'e2'"))
    }

    @Test fun v20to21_declaresBothColumnsAsNullableTextWithNoDefault() {
        seedV20()
        migrate20to21()

        // Room compares the AFFINITY, the nullability and the default at open time. A column of
        // the right name and the wrong type throws
        // IllegalStateException("Migration didn't properly handle: emails …") on every launch,
        // and the only way out for the user is to reinstall — the outbox lost by the side door.
        assertEquals(Triple("TEXT", 0, null), columnShapes("emails")["ccJson"])
        assertEquals(Triple("TEXT", 0, null), columnShapes("emails")["bccJson"])
        // The v17 column they are modelled on, as a witness that this is the shape Room expects.
        assertEquals(Triple("TEXT", 0, null), columnShapes("emails")["recipientsJson"])
    }

    // --- the migration has to be REGISTERED, not merely written ----------------------------------

    /**
     * Chains the migrations Room is really given ([SternaDatabase.ALL_MIGRATIONS]) over a published
     */
    @Test fun theRegisteredMigrationsCarryAPublishedInstallAllTheWayToTheCopyColumns() {
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
            "only the REGISTERED migrations ran: ccJson must be there, otherwise Room has no " +
                "20→21 path and drops the whole database, unsent mail included",
            Triple("TEXT", 0, null),
            columnShapes("emails")["ccJson"],
        )
        assertEquals(
            "only the REGISTERED migrations ran: bccJson must be there, otherwise Room has no " +
                "20→21 path and drops the whole database, unsent mail included",
            Triple("TEXT", 0, null),
            columnShapes("emails")["bccJson"],
        )
        // And the chain that reaches them loses nothing on the way.
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND `state` = 'QUEUED'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
    }

    /**
     * Reads [SCHEMA_VERSION], the constant `@Database(version = …)` itself is written from — the
     * annotation is not retained at runtime, so it cannot be read reflectively.
     */
    @Test fun theRegisteredMigrationsReachTheDeclaredSchemaVersion() {
        val reached = SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion }

        // Bumping the version without registering its step is the destructive case: Room asks for
        // a path to the declared version and, finding none, drops every table.
        assertEquals(
            "the registered chain stops at v$reached while the database declares v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
    }
}
