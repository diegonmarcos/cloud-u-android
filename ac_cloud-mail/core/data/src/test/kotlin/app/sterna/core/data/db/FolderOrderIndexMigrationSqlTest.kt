package app.sterna.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * **v26 → v27**: `emails` gains the composite index `(accountId, mailboxId, sortKey)` — the three
 */
class FolderOrderIndexMigrationSqlTest {
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

    /** Every table a v26 database holds rows in, so "nothing was lost" can be measured, not hoped. */
    private val allTables = listOf(
        "emails", "email_bodies", "snoozed", "mailboxes", "outbox", "scheduled_sends",
        "recent_contacts", "email_fts", "purge_snapshot", "mailbox_uidvalidity", "local_drafts",
    )

    /** The tables whose declared columns are compared before/after (`email_fts` is virtual). */
    private val shapedTables = allTables - "email_fts"

    // --- running the real, REGISTERED Migration objects ------------------------------------------

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

    /**
     * The step Room really has for `from` → …, read out of [SternaDatabase.ALL_MIGRATIONS].
     */
    private fun registeredStep(from: Int): Migration {
        val step = SternaDatabase.ALL_MIGRATIONS.firstOrNull { it.startVersion == from }
        assertNotNull(
            "no migration starting at v$from is registered in SternaDatabase.ALL_MIGRATIONS " +
                "(registered: " +
                SternaDatabase.ALL_MIGRATIONS.joinToString { "${it.startVersion}->${it.endVersion}" } +
                "). A step that is written but not registered never runs: Room finds no path and " +
                "fallbackToDestructiveMigration() DROPS every table, outbox included.",
            step,
        )
        return step!!
    }

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

    /**
     * Two accounts holding the same email id in the same server-assigned mailbox id — the reason
     */
    private fun fillTheTwinRowOfASiblingAccount() {
        db.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e1', 'accB', 'mb1', 'Twin', 0, 0, 0, 90)",
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
     * Runs the registered steps from v15 up to (but not including) [upTo], filling the tables the
     * later steps create as they appear. Returns the version actually reached.
     */
    private fun chainRegisteredFrom15(upTo: Int): Int {
        var current = 15
        SternaDatabase.ALL_MIGRATIONS
            .filter { it.startVersion >= 15 && it.startVersion < upTo }
            .sortedBy { it.startVersion }
            .forEach { step ->
                assertEquals(
                    "the registered migrations must form an unbroken chain from v15; a gap is not a " +
                        "degraded upgrade, it is a destructive fallback",
                    current,
                    step.startVersion,
                )
                step.migrate(supportDb())
                current = step.endVersion
                if (current == 16) fillTheTwinRowOfASiblingAccount()
                if (current == 19) fillTheV18AndV19Tables()
                if (current == 24) fillTheV24Table()
            }
        return current
    }

    /** A REAL v26 database: the published v15 schema, populated, carried forward by shipped steps. */
    private fun seedV26() {
        seedPublishedV15()
        assertEquals(
            "the registered chain must reach v26 before this branch's step can be measured",
            26,
            chainRegisteredFrom15(upTo = 26),
        )
    }

    private fun migrate26to27() = registeredStep(26).migrate(supportDb())

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

    /** The declared shape of each column — Room compares affinity and nullability at open time. */
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

    private fun allShapes(): Map<String, Map<String, Triple<String, Int, String?>>> =
        shapedTables.associateWith { columnShapes(it) }

    /** Every index SQLite holds, by name, with the table it belongs to. */
    private fun indexNames(): Map<String, String> {
        val indexes = mutableMapOf<String, String>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT `name`, `tbl_name` FROM `sqlite_master` WHERE `type` = 'index'").use { rs ->
                while (rs.next()) indexes[rs.getString("name")] = rs.getString("tbl_name")
            }
        }
        return indexes
    }

    /**
     * The full SHAPE of [index], as one comparable string: `unique=<0|1> partial=<0|1>` then the
     */
    private fun indexShape(index: String): String {
        var unique = "?"
        var partial = "?"
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`emails`)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name") == index) {
                        unique = rs.getInt("unique").toString()
                        partial = rs.getInt("partial").toString()
                    }
                }
            }
        }
        val bySeq = sortedMapOf<Int, String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_xinfo(`$index`)").use { rs ->
                while (rs.next()) {
                    if (rs.getInt("key") != 1) continue
                    val dir = if (rs.getInt("desc") == 1) "DESC" else "ASC"
                    bySeq[rs.getInt("seqno")] = "${rs.getString("name")} $dir ${rs.getString("coll")}"
                }
            }
        }
        return "unique=$unique partial=$partial " + bySeq.values.joinToString(", ")
    }

    /** The indexed columns of [index] alone, **in index order** — [indexShape] without the rest. */
    private fun indexColumns(index: String): List<String> =
        indexShape(index).substringAfter(" partial=").substringAfter(" ")
            .split(", ").filter { it.isNotBlank() }.map { it.substringBefore(" ") }

    // --- (a) the index, under the name Room derives ------------------------------------------------

    /**
     * The name is not decoration. A fresh install builds its schema from the `@Entity` annotation
     */
    @Test fun v26to27_createsTheIndexUnderTheNameRoomDerives() {
        seedV26()
        assertEquals(
            "the composite index must not exist before its step runs — otherwise this test is " +
                "measuring the seed, not the migration",
            null,
            indexNames()["index_emails_accountId_mailboxId_sortKey"],
        )

        migrate26to27()

        assertEquals(
            "the 26->27 step must create `index_emails_accountId_mailboxId_sortKey` on `emails`: " +
                "that is the name Room derives from @Entity(indices = [Index(\"accountId\", " +
                "\"mailboxId\", \"sortKey\")]), and a different name fails validateMigration",
            "emails",
            indexNames()["index_emails_accountId_mailboxId_sortKey"],
        )
    }

    // --- (b) the ORDER of the columns, which is the whole point -------------------------------------

    /**
     * Scope first, order last. `accountId` then `mailboxId` is the pair `folderScopeSql` binds
     */
    @Test fun v26to27_theIndexColumnsAreScopeThenOrder() {
        seedV26()
        migrate26to27()

        assertEquals(
            "the column ORDER is the whole value of this index: (accountId, mailboxId) is the scope " +
                "folderScopeSql binds, sortKey last is the list's ORDER BY and the representative " +
                "join; any other order indexes the same three columns and serves none of the three. " +
                "And the SHAPE around them is pinned too — plain ASC, not unique, not partial, " +
                "BINARY collation — because that is what `Index(value = [...])` on the entity makes " +
                "Room build on a fresh install, and Room compares uniqueness and direction at open " +
                "time: a `DESC` or a `UNIQUE` here alone would fail validateMigration on every " +
                "updated install, whose only way out is a reinstall that takes the outbox with it",
            "unique=0 partial=0 accountId ASC BINARY, mailboxId ASC BINARY, sortKey ASC BINARY",
            indexShape("index_emails_accountId_mailboxId_sortKey"),
        )
    }

    // --- (c) nothing else moved ---------------------------------------------------------------------

    @Test fun v26to27_keepsTheMailboxIdIndexAndAddsNothingElse() {
        seedV26()
        val indexesBefore = indexNames()
        assertEquals(
            "the historical single-column index is the starting state of every install",
            "emails",
            indexesBefore["index_emails_mailboxId"],
        )

        migrate26to27()

        assertEquals(
            "the step is additive: exactly one new index, and `index_emails_mailboxId` is NOT dropped " +
                "(dropping it would need a DROP INDEX, an unproven TableInfo tolerance and seven " +
                "fixtures rewritten)",
            mapOf("index_emails_accountId_mailboxId_sortKey" to "emails"),
            indexNames() - indexesBefore.keys,
        )
        assertEquals("no index disappears", emptySet<String>(), indexesBefore.keys - indexNames().keys)
    }

    @Test fun v26to27_touchesNoTableAndNoColumn() {
        seedV26()
        val shapesBefore = allShapes()
        val tablesBefore = tableNames()

        migrate26to27()

        assertEquals("no table is created or dropped", tablesBefore, tableNames())
        assertEquals(
            "an index step adds no column anywhere — least of all to the three stores holding text " +
                "the user typed (outbox, scheduled_sends, local_drafts)",
            shapesBefore,
            allShapes(),
        )
        assertEquals("the cached mail keeps its exact columns", columnsOf("emails"), shapesBefore["emails"]!!.keys)
    }

    private fun tableNames(): Set<String> {
        val tables = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT `name` FROM `sqlite_master` WHERE `type` = 'table'").use { rs ->
                while (rs.next()) tables += rs.getString("name")
            }
        }
        return tables
    }

    // --- (d) every row survives ---------------------------------------------------------------------

    @Test fun v26to27_everyRowOfEveryTableSurvives() {
        seedV26()
        val before = rowCounts()
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it == 0 }.keys.toList(),
        )

        migrate26to27()

        assertEquals(before, rowCounts())
        // The canary read back word for word: mail written and never sent is what a destructive
        // fallback takes away, and a row count alone would not notice a rewritten body.
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND " +
                    "`textBody` = 'see you at six' AND `state` = 'QUEUED' AND " +
                    "`recipients` = 'bob@example.org'",
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM `local_drafts` WHERE `subject` = 'Only here'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e1' AND `until` = 99999"))
        // The two same-id rows of two accounts both survive, and stay apart.
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `accountId` = 'accA' AND `subject` = 'Hello'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `emails` WHERE `accountId` = 'accB' AND `subject` = 'Twin'"))
    }

    // --- (e) the step has to be REGISTERED, and the version has to follow ----------------------------

    /**
     * Chains [SternaDatabase.ALL_MIGRATIONS] — the value Room is really handed — over a published
     */
    @Test fun theRegisteredMigrationsCarryAPublishedInstallToTheCompositeIndex() {
        seedPublishedV15()

        val reached = chainRegisteredFrom15(upTo = Int.MAX_VALUE)

        assertEquals(
            "only the REGISTERED migrations ran: the chain must arrive at v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
        assertEquals(
            "`index_emails_accountId_mailboxId_sortKey` must exist on a published install carried " +
                "forward by the registered chain alone",
            "emails",
            indexNames()["index_emails_accountId_mailboxId_sortKey"],
        )
        assertEquals(
            listOf("accountId", "mailboxId", "sortKey"),
            indexColumns("index_emails_accountId_mailboxId_sortKey"),
        )
        // And the chain that reaches it loses nothing on the way.
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND `state` = 'QUEUED'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `local_drafts` WHERE `subject` = 'Only here'"))
        assertEquals(2, count("SELECT COUNT(*) FROM `mailboxes`"))
        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
    }

    /**
     * Reads [SCHEMA_VERSION], the constant `@Database(version = …)` is itself written from — the
     * annotation is not retained at runtime, so it cannot be read reflectively.
     */
    @Test fun theRegisteredMigrationsReachTheDeclaredSchemaVersion() {
        val reached = SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion }

        assertEquals(
            "the registered chain stops at v$reached while the database declares v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
        // ⛔ A second assertion pinning SCHEMA_VERSION to the literal 27 used to sit here, and it is
        // gone on purpose — see the same note in DraftNumberingMigrationSqlTest. Four testers each
        // kept a private copy of "the version this branch ended on", so one migration falsified all
        // four together. The rule above needs no maintenance: the chain must reach whatever the
        // database declares, at every version there will ever be.
    }
}
