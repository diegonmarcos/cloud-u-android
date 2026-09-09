package app.sterna.core.data.mail

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.db.EMAILS_CREATE_SQL
import app.sterna.core.data.db.EMAILS_MAILBOX_INDEX_SQL
import app.sterna.core.data.db.SNOOZED_CREATE_SQL
import app.sterna.core.data.db.SternaDatabase
import app.sterna.core.data.settings.SortOrder
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
 * What the SQLite planner actually DOES with [conversationSql] once `emails` carries the composite
 */
class ConversationIndexPlanSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAILS_MAILBOX_INDEX_SQL)
            st.executeUpdate(SNOOZED_CREATE_SQL)
        }
        fill()
    }

    @After fun tearDown() = db.close()

    /**
     * Enough rows, spread over two accounts and two folders, that the planner has a real choice:
     */
    private fun fill() {
        db.autoCommit = false
        db.prepareStatement(
            "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `threadId`, `subject`, `preview`, " +
                "`receivedAt`, `fromName`, `fromEmail`, `seen`, `flagged`, `hasAttachment`, `sortKey`) " +
                "VALUES (?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e@example.org', ?, 0, 0, ?)",
        ).use { ps ->
            for (account in listOf("accA", "accB")) {
                for (mailbox in listOf("inbox", "archive")) {
                    for (i in 0 until 400) {
                        ps.setString(1, "$mailbox-$i")
                        ps.setString(2, account)
                        ps.setString(3, mailbox)
                        ps.setString(4, "T${i / 3}")
                        ps.setInt(5, i % 2)
                        ps.setLong(6, i * 10L)
                        ps.addBatch()
                    }
                }
            }
            ps.executeBatch()
        }
        db.commit()
        db.autoCommit = true
    }

    /** Adds the composite index by running the step Room has REGISTERED for v26 → v27. */
    private fun applyTheRegisteredIndexStep() {
        val step = SternaDatabase.ALL_MIGRATIONS.firstOrNull { it.startVersion == 26 }
        assertNotNull(
            "no migration starting at v26 is registered in SternaDatabase.ALL_MIGRATIONS " +
                "(registered: " +
                SternaDatabase.ALL_MIGRATIONS.joinToString { "${it.startVersion}->${it.endVersion}" } +
                "), so `emails` never gains the (accountId, mailboxId, sortKey) index on an " +
                "updated install and the conversation list keeps re-scanning the folder",
            step,
        )
        step!!.migrate(
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
            } as SupportSQLiteDatabase,
        )
    }

    private val sql: String
        get() = conversationSql(scopeCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false)

    /**
     * The form the reported gesture REALLY emits. A folder view on an account whose Sent folder is
     */
    private val sqlWithSentFolder: String
        get() = conversationSql(
            scopeCount = 1,
            sort = SortOrder.DATE_DESC,
            unreadOnly = false,
            sentMailboxCount = 1,
        )

    /**
     * The plan rows of [sql], one string each, with the leading `SEARCH`/`SEARCH TABLE` removed so
     * the assertion pins the ALIAS, the INDEX and the CONSTRAINED COLUMNS — not SQLite's wording.
     */
    private fun plan(): List<String> = planOf(sql, listOf("accA", "inbox", "accA", "inbox", "accA", "inbox"))

    /**
     * The plan of [query], bound with [args]. Bind sites, in order: the in-view sub-query `g`, the
     * chip-count sub-query `c` (plus one extra pair per Sent alternative), then the outer WHERE.
     */
    private fun planOf(query: String, args: List<String>): List<String> {
        val rows = mutableListOf<String>()
        db.prepareStatement("EXPLAIN QUERY PLAN $query").use { ps ->
            args.forEachIndexed { i, value -> ps.setString(i + 1, value) }
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += rs.getString(4).removePrefix("SEARCH TABLE ").removePrefix("SEARCH ")
                }
            }
        }
        return rows
    }

    private fun readable(plan: List<String>) = plan.joinToString("\n  ", prefix = "\n  ")

    // --- the proof ---------------------------------------------------------------------------------

    /**
     * The two grouped sub-queries. `g` (the representative + unread state) and `c` (the chip
     */
    @Test fun theGroupingSubQueriesReachTheFolderThroughTheCompositeIndex() {
        applyTheRegisteredIndexStep()
        val plan = plan()

        assertEquals(
            "both GROUP BY sub-queries of conversationSql (g, the representative and unread state, " +
                "and c, the chip count) must reach the folder through " +
                "index_emails_accountId_mailboxId_sortKey on the (accountId, mailboxId) pair. " +
                "Plan was:${readable(plan)}",
            2,
            plan.count {
                it == "emails USING INDEX index_emails_accountId_mailboxId_sortKey " +
                    "(accountId=? AND mailboxId=?)"
            },
        )
    }

    /**
     * The representative join — `e.accountId = g.gacc AND e.sortKey = g.maxKey` under the outer
     */
    @Test fun theRepresentativeJoinUsesAllThreeColumns() {
        applyTheRegisteredIndexStep()
        val plan = plan()

        assertTrue(
            "the representative row of each thread must be fetched through " +
                "index_emails_accountId_mailboxId_sortKey constrained on accountId, mailboxId AND " +
                "sortKey — that last column is the whole reason it is the third one. " +
                "Plan was:${readable(plan)}",
            plan.any {
                it == "e USING INDEX index_emails_accountId_mailboxId_sortKey " +
                    "(accountId=? AND mailboxId=? AND sortKey=?)"
            },
        )
    }

    /**
     * The same proof on the form the gesture actually emits. A folder view whose account has a
     */
    @Test fun theFormWithAResolvedSentFolderStillReachesTheRepresentativeThroughAllThreeColumns() {
        applyTheRegisteredIndexStep()
        val plan = planOf(
            sqlWithSentFolder,
            // g, then c with its Sent alternative, then the outer WHERE.
            listOf("accA", "inbox", "accA", "inbox", "accA", "sent", "accA", "inbox"),
        )

        assertTrue(
            "with a resolved Sent folder — the normal case for the reported gesture — the " +
                "representative row of each thread must STILL be fetched through " +
                "index_emails_accountId_mailboxId_sortKey on all three columns. Plan was:${readable(plan)}",
            plan.any {
                it == "e USING INDEX index_emails_accountId_mailboxId_sortKey " +
                    "(accountId=? AND mailboxId=? AND sortKey=?)"
            },
        )
        assertEquals(
            "and no part of the folder path may read `emails` without an index. Plan was:${readable(plan)}",
            emptyList<String>(),
            plan.filter { it.startsWith("emails ") && !it.contains("USING INDEX") },
        )
    }

    // --- the witness: what the plan looked like before ----------------------------------------------

    /**
     * The state this branch leaves behind, kept as a witness so the two tests above are known to be
     */
    @Test fun withoutTheStepThePlanFallsBackToTheMailboxIdAlone() {
        val plan = plan()

        assertTrue(
            "this witness must run BEFORE the index exists. Plan was:${readable(plan)}",
            plan.none { it.contains("index_emails_accountId_mailboxId_sortKey") },
        )
        assertEquals(
            "the grouped sub-queries fall back to the single-column index, which ignores the " +
                "account. Plan was:${readable(plan)}",
            2,
            plan.count { it == "emails USING INDEX index_emails_mailboxId (mailboxId=?)" },
        )
        assertTrue(
            "and the representative join walks the account's whole primary key. Plan was:${readable(plan)}",
            plan.any { it == "e USING INDEX sqlite_autoindex_emails_1 (accountId=?)" },
        )
    }
}
