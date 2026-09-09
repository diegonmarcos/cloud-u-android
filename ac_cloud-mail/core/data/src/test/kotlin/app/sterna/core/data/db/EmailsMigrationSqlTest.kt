package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.jmap.model.EmailAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the REAL migration objects against a real SQLite engine, starting from the schema a
 */
class EmailsMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    // --- the published schemas -----------------------------------------------------------------

    /** `emails` as released up to v15: primary key on the email id alone. */
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
     * The v14 shape of the search index: the SAME columns as v15, but built with
     */
    private val v14FtsCreate = EMAIL_FTS_CREATE_SQL.replace("remove_diacritics=1", "remove_diacritics=2")

    /** Seed a database in the PUBLISHED v15 shape, with a row in every table. */
    private fun seedV15(ftsCreateSql: String = EMAIL_FTS_CREATE_SQL) {
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
            st.executeUpdate(ftsCreateSql)

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
            // User data that a destructive fallback would destroy: a snooze, a queued send and a
            // scheduled send.
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e2', 'accA', 99999)")
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`attachmentsJson`, `createdAtMillis`, `notBeforeMillis`, `state`, `attemptCount`) " +
                    "VALUES ('accA', 'bob@example.org', 'Queued', 'body', '[]', 1, 2, 'QUEUED', 0)",
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

    // --- running the real Migration objects ----------------------------------------------------

    /**
     * A [SupportSQLiteDatabase] that only knows how to `execSQL` onto the JDBC connection —
     */
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

    private fun migrate15to16() = MIGRATION_15_16.migrate(supportDb())

    private fun migrate14to15() = MIGRATION_14_15.migrate(supportDb())

    private fun migrate16to17() = MIGRATION_16_17.migrate(supportDb())

    private fun migrate17to18() = MIGRATION_17_18.migrate(supportDb())

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getInt(1)
        }
    }

    private fun columnsOf(table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) columns += rs.getString("name")
            }
        }
        return columns
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

    // --- v15 → v16: the upgrade every published install takes -----------------------------------

    @Test fun v15to16_preservesRowsAndColumns() {
        seedV15()
        migrate15to16()

        assertEquals(
            setOf(
                "id", "accountId", "mailboxId", "threadId", "subject", "preview", "receivedAt",
                "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
            ),
            columnsOf("emails"),
        )

        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1' AND `subject` = 'Hello' AND `sortKey` = 100"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_bodies` WHERE `fetchedAt` = 42"))
        // Snoozes are user data: the row must survive the rebuild bit-for-bit.
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
    }

    @Test fun v15to16_keepsUnsentMailAndTheRestOfTheDatabase() {
        seedV15()
        migrate15to16()

        // Queued and scheduled mail is unsent USER data — the whole reason this migration exists
        // instead of a destructive fallback.
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Queued' AND `state` = 'QUEUED'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later' AND `sendAtMillis` = 500"))
        // Tables the migration does not touch stay exactly as they were.
        assertEquals(1, count("SELECT COUNT(*) FROM `mailboxes` WHERE `accountId` = 'accA' AND `id` = 'mb1'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `recent_contacts` WHERE `email` = 'bob@example.org'"))
    }

    @Test fun v15to16_leavesTheSearchIndexIntactAndSearchable() {
        seedV15()
        migrate15to16()

        // `email_fts` is a STANDALONE FTS4 table (no content= link to `emails`, no triggers), so
        // rebuilding `emails` under it must neither drop it nor empty it.
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
    }

    @Test fun v15to16_primaryKeysBecomeAccountIdThenEmailId() {
        seedV15()
        migrate15to16()

        assertEquals(1, pkPositions("emails")["accountId"])
        assertEquals(2, pkPositions("emails")["id"])
        assertEquals(1, pkPositions("email_bodies")["accountId"])
        assertEquals(2, pkPositions("email_bodies")["id"])
        assertEquals(1, pkPositions("snoozed")["accountId"])
        assertEquals(2, pkPositions("snoozed")["emailId"])
    }

    @Test fun v15to16_leavesExactlyTheIndexesRoomExpects() {
        seedV15()
        migrate15to16()

        fun indexesOf(table: String): Set<String> {
            val indexes = mutableSetOf<String>()
            db.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                    while (rs.next()) indexes += rs.getString("name")
                }
            }
            return indexes
        }
        // Room compares the index set at open time: a missing one — or a leftover one — throws
        // "Migration didn't properly handle" and takes the app down on first launch.
        assertTrue("index_emails_mailboxId must exist after the rebuild", "index_emails_mailboxId" in indexesOf("emails"))
        assertTrue(
            "index_email_bodies_accountId is subsumed by the composite key and must be gone",
            indexesOf("email_bodies").none { it == "index_email_bodies_accountId" },
        )
    }

    @Test fun v15to16_sameEmailIdCoexistsAcrossAccounts() {
        seedV15()
        migrate15to16()

        // A second account (sub-account of the same login) caches a message whose JMAP id equals
        // an existing row's id. Under the old single-column PKs these INSERTs would have collided;
        // with the composite keys they are distinct rows.
        db.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e1', 'accB', 'mb9', 0, 0, 0, 300)",
            )
            st.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'accB', '{\"b\":1}', '{}', 43)")
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e2', 'accB', 11111)")
        }

        assertEquals(2, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1'"))
        assertEquals(2, count("SELECT COUNT(*) FROM `email_bodies` WHERE `id` = 'e1'"))
        assertEquals(2, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2'"))
    }

    @Test fun v15to16_duplicateCompositeKeyIsRejected() {
        seedV15()
        migrate15to16()

        var conflicted = false
        try {
            db.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `seen`, `flagged`, " +
                        "`hasAttachment`, `sortKey`) VALUES ('e1', 'accA', 'mb1', 0, 0, 0, 999)",
                )
            }
        } catch (e: Exception) {
            conflicted = true
        }
        assertTrue("Re-inserting the same (accountId, id) must violate the composite PK", conflicted)
    }

    // --- v16 → v17: the To: recipients become a real column (#63) -------------------------------

    /** A v16 database: the published v15 schema with [MIGRATION_15_16] already applied. */
    private fun seedV16() {
        seedV15()
        migrate15to16()
    }

    private fun stringOrNull(sql: String): String? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getString(1)
        }
    }

    @Test fun v16to17_addsTheRecipientsColumnAndKeepsEveryRow() {
        seedV16()
        migrate16to17()

        assertEquals(
            setOf(
                "id", "accountId", "mailboxId", "threadId", "subject", "preview", "receivedAt",
                "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
                "recipientsJson",
            ),
            columnsOf("emails"),
        )

        // Purely additive: every cached row and every scrap of user data survives untouched.
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1' AND `subject` = 'Hello' AND `sortKey` = 100"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_bodies` WHERE `fetchedAt` = 42"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Queued'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
    }

    @Test fun v16to17_leavesTheCompositeKeyAndIndexAlone() {
        seedV16()
        migrate16to17()

        // Room re-checks keys and indexes at open time; an ALTER must disturb neither.
        assertEquals(1, pkPositions("emails")["accountId"])
        assertEquals(2, pkPositions("emails")["id"])
        assertEquals(0, pkPositions("emails")["recipientsJson"])
        val indexes = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`emails`)").use { rs ->
                while (rs.next()) indexes += rs.getString("name")
            }
        }
        assertTrue("index_emails_mailboxId must survive the ALTER", "index_emails_mailboxId" in indexes)
    }

    @Test fun v16to17_preExistingRowsReadBackAsNoRecipients() {
        seedV16()
        migrate16to17()

        // No backfill (the addresses aren't held locally): an old row is simply NULL, which the
        // codec turns into an empty list — exactly the pre-v17 "fall back to the sender" state.
        assertEquals(2, count("SELECT COUNT(*) FROM `emails` WHERE `recipientsJson` IS NULL"))
        assertEquals(emptyList<Any>(), EmailRecipients.decode(stringOrNull("SELECT `recipientsJson` FROM `emails` WHERE `id` = 'e1'")))
    }

    @Test fun v16to17_newRowsRoundTripTheirRecipients() {
        seedV16()
        migrate16to17()

        val recipients = listOf(
            EmailAddress(name = "Bob", email = "bob@example.org"),
            EmailAddress(email = "carol@example.org"),
        )
        db.prepareStatement("UPDATE `emails` SET `recipientsJson` = ? WHERE `id` = 'e1'").use { st ->
            st.setString(1, EmailRecipients.encode(recipients))
            st.executeUpdate()
        }

        assertEquals(recipients, EmailRecipients.decode(stringOrNull("SELECT `recipientsJson` FROM `emails` WHERE `id` = 'e1'")))
        // The sibling row is untouched.
        assertEquals(null, stringOrNull("SELECT `recipientsJson` FROM `emails` WHERE `id` = 'e2'"))
    }

    @Test fun v15to17_chainedFromAPublishedInstall() {
        seedV15()

        migrate15to16()
        migrate16to17()

        assertTrue("recipientsJson" in columnsOf("emails"))
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Queued'"))
        assertEquals(1, pkPositions("emails")["accountId"])
        assertEquals(2, pkPositions("emails")["id"])
    }

    // --- v17 → v18: the Empty-trash destroy list gets a table (#99) ------------------------------

    /** A v17 database: the published v15 schema with 15→16 and 16→17 applied. */
    private fun seedV17() {
        seedV16()
        migrate16to17()
    }

    @Test fun v17to18_addsThePurgeSnapshotTableAndTouchesNothingElse() {
        seedV17()
        migrate17to18()

        assertEquals(
            setOf("purgeId", "accountId", "mailboxId", "emailId", "createdAt"),
            columnsOf("purge_snapshot"),
        )
        // Purely additive: no existing row moves, user data above all.
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Queued'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
    }

    @Test fun v17to18_startsEmptySoAnOlderPurgeDestroysNothing() {
        seedV17()
        migrate17to18()

        // A purge confirmed on the previous version carries no snapshot. No snapshot, no order:
        // it destroys nothing rather than falling back to "whatever is in Trash now" (#99).
        assertEquals(0, count("SELECT COUNT(*) FROM `purge_snapshot`"))
    }

    @Test fun v17to18_keysTheSnapshotByPurgeAccountAndEmail() {
        seedV17()
        migrate17to18()

        // The account is part of the key: email ids are unique only within their account
        // (issue #31), so two accounts must be able to hold the same id in one purge.
        assertEquals(1, pkPositions("purge_snapshot")["purgeId"])
        assertEquals(2, pkPositions("purge_snapshot")["accountId"])
        assertEquals(3, pkPositions("purge_snapshot")["emailId"])
        assertEquals(0, pkPositions("purge_snapshot")["mailboxId"])

        db.createStatement().use { st ->
            st.executeUpdate("INSERT INTO `purge_snapshot` VALUES ('p1', 'accA', 'trash', 'm1', 1)")
            st.executeUpdate("INSERT INTO `purge_snapshot` VALUES ('p1', 'accB', 'trash', 'm1', 1)")
        }
        assertEquals(2, count("SELECT COUNT(*) FROM `purge_snapshot` WHERE `emailId` = 'm1'"))
    }

    @Test fun v15to18_chainedFromAPublishedInstall() {
        seedV15()

        migrate15to16()
        migrate16to17()
        migrate17to18()

        assertTrue("recipientsJson" in columnsOf("emails"))
        assertTrue("purgeId" in columnsOf("purge_snapshot"))
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Queued'"))
    }

    // --- v14 → v15 → v16: an install that skipped 1.3.11–1.3.13 ---------------------------------

    @Test fun v14to16_chainedMigrationsPreserveEverything() {
        // Same tables, but the v14 search index (remove_diacritics=2) the FTS migration rebuilds.
        seedV15(ftsCreateSql = v14FtsCreate)

        migrate14to15()
        migrate15to16()

        // The FTS rebuild repopulated the index from `emails`, and the key widening followed
        // without disturbing it.
        assertEquals(2, count("SELECT COUNT(*) FROM `email_fts`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'World*'"))
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Queued'"))
        assertEquals(1, pkPositions("emails")["accountId"])
        assertEquals(2, pkPositions("emails")["id"])
    }
}
