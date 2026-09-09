package app.sterna.core.data.mail

import app.sterna.core.data.db.EMAILS_CREATE_SQL
import app.sterna.core.data.db.EMAIL_BODIES_CREATE_SQL
import app.sterna.core.data.db.EMAIL_FTS_CREATE_SQL
import app.sterna.core.data.db.EmailRetentionRow
import app.sterna.core.data.db.LOCAL_DRAFTS_CREATE_SQL
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * **Why `local_drafts` is a table of its own** (#95), stated as four executions instead of as a
 */
class LocalDraftsSurviveTheCachePrunesSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAIL_BODIES_CREATE_SQL)
            st.executeUpdate(EMAIL_FTS_CREATE_SQL)
            // The shipped CREATE, the one MIGRATION_23_24 runs on a real upgrade.
            st.executeUpdate(LOCAL_DRAFTS_CREATE_SQL)
        }
    }

    @After fun tearDown() = db.close()

    // ---- the fixture: one draft, held twice -----------------------------------------------------

    /** A message the SERVER knows about, cached in the Drafts folder. */
    private fun cacheServerRow(id: String, sortKey: Long) {
        db.prepareStatement(
            "INSERT INTO emails (id, accountId, mailboxId, subject, seen, flagged, hasAttachment, sortKey) " +
                "VALUES (?, ?, ?, 'a saved draft', 0, 0, 0, ?)",
        ).use {
            it.setString(1, id); it.setString(2, ACCOUNT); it.setString(3, DRAFTS); it.setLong(4, sortKey)
            it.executeUpdate()
        }
        db.prepareStatement("INSERT INTO email_bodies VALUES (?, ?, '{}', '{}', 1)").use {
            it.setString(1, id); it.setString(2, ACCOUNT); it.executeUpdate()
        }
    }

    /**
     * The draft written on the phone, in BOTH stores: the row `emails` would hold if the local
     */
    private fun theDraft(sortKey: Long = WRITTEN_LONG_AGO) {
        db.prepareStatement(
            "INSERT INTO emails (id, accountId, mailboxId, subject, seen, flagged, hasAttachment, sortKey) " +
                "VALUES (?, ?, ?, 'Half written', 1, 0, 0, ?)",
        ).use {
            it.setString(1, DRAFT_ID); it.setString(2, ACCOUNT); it.setString(3, DRAFTS)
            it.setLong(4, sortKey); it.executeUpdate()
        }
        db.prepareStatement("INSERT INTO email_bodies VALUES (?, ?, ?, '{}', 1)").use {
            it.setString(1, DRAFT_ID); it.setString(2, ACCOUNT); it.setString(3, TEXT); it.executeUpdate()
        }
        db.prepareStatement(
            "INSERT INTO local_drafts (accountId, id, messageId, toAddresses, subject, textBody, " +
                "attachmentsJson, bodyIsLossy, requestReceipt, createdAtMillis, updatedAtMillis, " +
                "notBeforeMillis, attemptCount, state) " +
                "VALUES (?, ?, 'mid@example.org', 'bob@example.org', 'Half written', ?, '[]', 0, 0, " +
                "?, ?, 0, 0, 'PENDING')",
        ).use {
            it.setString(1, ACCOUNT); it.setString(2, DRAFT_ID); it.setString(3, TEXT)
            it.setLong(4, sortKey); it.setLong(5, sortKey); it.executeUpdate()
        }
    }

    private fun cachedIds(): List<String> = query("SELECT id FROM emails ORDER BY id") { it.getString(1) }

    private fun cachedBodyIds(): List<String> =
        query("SELECT id FROM email_bodies ORDER BY id") { it.getString(1) }

    /** The draft as the phone still holds it: its text, or null once the row is gone. */
    private fun localDraftText(): String? =
        query("SELECT textBody FROM local_drafts WHERE accountId = '$ACCOUNT' AND id = '$DRAFT_ID'") {
            it.getString(1)
        }.firstOrNull()

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }

    // ---- the shipped statements, executed --------------------------------------------------------

    /**
     * Run one shipped `@Query`, binding its named parameters from [args] — a list parameter (Room's
     * `IN (:ids)`) is bound element by element, in order.
     */
    private fun execute(sql: String, args: Map<String, List<String>>): Int {
        val (statement, order) = DaoQuerySource.bindOrder(sql, args.mapValues { it.value.size })
        val cursors = mutableMapOf<String, Int>()
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { position, name ->
                val values = args[name] ?: error("nothing to bind for :$name in $statement")
                val at = cursors.getOrDefault(name, 0)
                ps.setString(position + 1, values[at])
                cursors[name] = at + 1
            }
            ps.executeUpdate()
        }
    }

    /** `EmailDao.idsForMailbox`'s shipped SQL, run for real — what every path below reads first. */
    private fun idsForMailbox(): List<String> {
        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("idsForMailbox"))
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name -> ps.setString(i + 1, if (name == "accountId") ACCOUNT else DRAFTS) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** `EmailDao.retentionRows`'s shipped SQL, run for real — the rows the retention prune judges. */
    private fun retentionRows(): List<EmailRetentionRow> {
        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("retentionRows"))
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name -> ps.setString(i + 1, if (name == "accountId") ACCOUNT else DRAFTS) }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(EmailRetentionRow(rs.getString(1), rs.getLong(2))) }
            }
        }
    }

    /** The one DELETE that [functionName]'s shipped path issues on the cache. */
    private fun deleteStatementOf(functionName: String): String =
        DaoQuerySource.emailDaoStatements(functionName)
            .filter { it.sql.trimStart().startsWith("DELETE", ignoreCase = true) }
            .map { it.sql }
            .distinct()
            .single()

    /**
     * A staleness guard, not a proof: it fails loudly when the shipped repository path stops
     */
    private fun stillCalls(repositoryFunction: String, vararg calls: String) {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", repositoryFunction)
        calls.forEach {
            check(it in body) {
                "MailRepository.$repositoryFunction no longer calls '$it'. This test replays that " +
                    "path to show it cannot reach local_drafts; if the path moved, follow it rather " +
                    "than keep passing."
            }
        }
    }

    // ---- 1. the full-query reconcile -------------------------------------------------------------

    @Test fun `the reconcile deletes the cached draft row and cannot reach the local one`() {
        // The walk names what the SERVER holds in Drafts. A draft that never left the phone is in
        // no walk's answer — by construction, not by accident — so it is in the complement.
        theDraft()
        cacheServerRow("imap:acc:Drafts:12", sortKey = NOW)

        val batches = reconcileEvictions(
            cachedIds = idsForMailbox(),
            keepIds = setOf("imap:acc:Drafts:12"),
            spareIds = emptySet(),
        )
        val delete = deleteStatementOf("reconcileMailboxRows")
        batches.forEach { execute(delete, mapOf("accountId" to listOf(ACCOUNT), "ids" to it)) }

        assertEquals(
            "the witness: filed in `emails`, the draft is exactly what a reconcile evicts",
            listOf("imap:acc:Drafts:12"), cachedIds(),
        )
        assertEquals("and the same draft, held in its own table, is untouched", TEXT, localDraftText())
    }

    // ---- 2. the ghost sweep, which takes the body too ---------------------------------------------

    @Test fun `the ghost sweep destroys the cached draft and its body, and not the local draft`() {
        stillCalls("pruneGhostRows", "idsForMailbox(", "ghostEvictions(", "pruneServerGone(")
        stillCalls("pruneServerGone", "deleteByIds(", "emailFtsDao.deleteByIds(", "emailBodyDao.deleteById(")
        theDraft()
        cacheServerRow("imap:acc:Drafts:12", sortKey = NOW)

        // `Email/get` answers for the ids the cache holds. It has never heard of a draft that is
        // still on the phone, so that id comes back in `notFound` — the sweep's word for "the
        // server destroyed this", which here is simply false.
        val cached = idsForMailbox()
        val ghosts = ghostEvictions(cached, notFound = setOf(DRAFT_ID))
        assertEquals(listOf(DRAFT_ID), ghosts)

        // `pruneServerGone`, replayed. Its `emailsByIds` read is skipped on purpose: it only
        // narrows the list to rows that exist, and EmailDao carries two overloads of that name, so
        // reading "the" one from the source would execute a statement this path does not run.
        DaoQuerySource.emailDaoPath("deleteByIds").main.forEach {
            execute(it.sql, mapOf("accountId" to listOf(ACCOUNT), "ids" to ghosts))
        }
        execute(
            DaoQuerySource.daoQuery("EmailFtsDao", "deleteByIds"),
            mapOf("accountId" to listOf(ACCOUNT), "ids" to ghosts),
        )
        ghosts.forEach {
            execute(
                DaoQuerySource.daoQuery("EmailBodyDao", "deleteById"),
                mapOf("accountId" to listOf(ACCOUNT), "id" to listOf(it)),
            )
        }

        assertEquals(listOf("imap:acc:Drafts:12"), cachedIds())
        assertEquals(
            "⛔ the witness, in its worst form: this path takes the cached BODY with the row, so the " +
                "text itself is gone, not just its header",
            listOf("imap:acc:Drafts:12"), cachedBodyIds(),
        )
        assertEquals(TEXT, localDraftText())
    }

    // ---- 3. the retention prune -------------------------------------------------------------------

    @Test fun `the retention window evicts the cached draft that waited and not the local one`() {
        stillCalls("pruneRetention", "retentionRows(", "retentionEvictions(", "evictFromCacheKeepingIndex(")
        // A phone that has been away from its server for months: the draft was written before the
        // window and three server messages have landed since. This is the shape in which the
        // retention prune is the path that destroys it — the age is the whole point, so the fixture
        // states it rather than pretending every prune reaches every row.
        theDraft(sortKey = WRITTEN_LONG_AGO)
        cacheServerRow("imap:acc:Drafts:12", sortKey = NOW)
        cacheServerRow("imap:acc:Drafts:13", sortKey = NOW - 1_000)
        cacheServerRow("imap:acc:Drafts:14", sortKey = NOW - 2_000)

        val gone = retentionEvictions(
            cached = retentionRows(),
            cutoffMillis = NOW - 30L * 24 * 3_600_000,
            // What the refresh just fetched. A draft the server has never seen cannot be in it.
            freshIds = setOf("imap:acc:Drafts:12", "imap:acc:Drafts:13", "imap:acc:Drafts:14"),
            spareIds = emptySet(),
            keepNewest = 2,
        )
        assertEquals(listOf(DRAFT_ID), gone)
        execute(
            deleteStatementOf("evictFromCacheKeepingIndex"),
            mapOf("accountId" to listOf(ACCOUNT), "ids" to gone),
        )

        assertEquals(
            listOf("imap:acc:Drafts:12", "imap:acc:Drafts:13", "imap:acc:Drafts:14"),
            cachedIds(),
        )
        assertEquals(TEXT, localDraftText())
    }

    // ---- 4. the same delete, reached from a folder the account no longer syncs ---------------------

    @Test fun `a delete scoped to the account empties the cached folder and leaves the drafts table`() {
        // The last shape of the same statement: `reconcileMailbox` called with an EMPTY keep set —
        // what a folder that came back empty from the server produces. Nothing in `emails` is
        // spared; `local_drafts` is not in the statement's reach at all.
        theDraft()
        cacheServerRow("imap:acc:Drafts:12", sortKey = NOW)

        val batches = reconcileEvictions(cachedIds = idsForMailbox(), keepIds = emptySet(), spareIds = emptySet())
        val delete = deleteStatementOf("reconcileMailboxRows")
        batches.forEach { execute(delete, mapOf("accountId" to listOf(ACCOUNT), "ids" to it)) }

        assertEquals("the witness: the cache is emptied, drafts and all", emptyList<String>(), cachedIds())
        assertEquals(
            "the phone still holds the only copy of what the user typed",
            TEXT, localDraftText(),
        )
        assertTrue(
            "the drafts table itself is still there — no path above may drop it",
            query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }
                .contains("local_drafts"),
        )
    }

    private companion object {
        const val ACCOUNT = "acc"
        const val DRAFTS = "mb-drafts"

        /** A local draft id: [LOCAL_DRAFT_ID_PREFIX] plus a fixed suffix, so the fixture is
         *  reproducible while the shape stays the shipped one. */
        val DRAFT_ID = LOCAL_DRAFT_ID_PREFIX + "d1"

        const val TEXT = "I will be there at six, do not wait for me at the station"
        const val NOW = 1_760_000_000_000L

        /** Written four months before [NOW]: a phone that has not reached its server since. */
        const val WRITTEN_LONG_AGO = NOW - 120L * 24 * 3_600_000
    }
}
