package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, the OTHER half of "a deleted message must not come back in
 */
class DeletedMailLeavesTheIndexSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE VIRTUAL TABLE email_fts USING fts4(" +
                    "emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                    "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey, " +
                    "notindexed=emailId, notindexed=accountId, notindexed=mailboxId, " +
                    "notindexed=threadId, notindexed=preview, notindexed=receivedAt, " +
                    "notindexed=fromName, notindexed=fromEmail, notindexed=seen, " +
                    "notindexed=flagged, notindexed=hasAttachment, notindexed=sortKey, " +
                    "tokenize=unicode61 `remove_diacritics=1`)",
            )
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT, subject TEXT,
                    preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT, seen INTEGER,
                    flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER, recipientsJson TEXT,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE mailboxes(
                    accountId TEXT, id TEXT, name TEXT, role TEXT, parentId TEXT,
                    sortOrder INTEGER, totalEmails INTEGER, unreadEmails INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    // ---- the state the app would be in -------------------------------------------------------

    private fun folder(id: String, role: String?, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO mailboxes VALUES(?, ?, 'Folder', ?, NULL, 0, 0, 0)").use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, role); it.executeUpdate()
        }
    }

    /** A message that is both cached for the list and present in the search index. */
    private fun message(emailId: String, mailboxId: String, accountId: String = "acc") {
        cache(emailId, mailboxId, accountId)
        index(emailId, mailboxId, accountId)
    }

    private fun cache(emailId: String, mailboxId: String, accountId: String = "acc") {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'quarterly token report', 'preview', " +
                "'', 'Alex Rivera', 'alex@example.org', 0, 0, 0, 100, NULL)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.executeUpdate()
        }
    }

    private fun index(emailId: String, mailboxId: String, accountId: String = "acc") {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, " +
                "body, preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, " +
                "sortKey) VALUES(?, ?, ?, NULL, 'quarterly token report', " +
                "'Alex Rivera alex@example.org', '', 'preview', '', 'Alex Rivera', " +
                "'alex@example.org', 0, 0, 0, 100)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.executeUpdate()
        }
    }

    // ---- the shipped statements ---------------------------------------------------------------

    /**
     * Replay one of `EmailDao`'s composed delete functions: the statements ITS OWN BODY calls, in
     */
    private fun deletePath(function: String, values: Map<String, Any>, busy: Busy? = null) {
        val path = DaoQuerySource.emailDaoPath(function)
        check(path.main.isNotEmpty()) { "EmailDao.$function composes no statement" }
        val outcome = runCatching { replayPart(path.main, path.atomic, values, busy) }
        if (outcome.isSuccess) return
        // The `catch` the shipped body carries, if it carries one; without it the failure escapes
        // to the caller exactly as it would in the app.
        if (path.fallback.isEmpty()) throw outcome.exceptionOrNull()!!
        replayPart(path.fallback, atomic = false, values, busy)
    }

    private fun replayPart(
        statements: List<DaoQuerySource.DaoStatement>,
        atomic: Boolean,
        values: Map<String, Any>,
        busy: Busy?,
    ) {
        if (!atomic) {
            statements.forEach { replay(it, values, busy) }
            return
        }
        db.autoCommit = false
        try {
            statements.forEach { replay(it, values, busy) }
            db.commit()
        } catch (e: Exception) {
            db.rollback() // what @Transaction does with an exception it did not catch
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    /** One replayed statement, failing the way the shipped body lets it fail. */
    private fun replay(statement: DaoQuerySource.DaoStatement, values: Map<String, Any>, busy: Busy?) {
        val run = {
            if (busy?.refuses(statement.sql) == true) throw java.sql.SQLException("database is locked (SQLITE_BUSY)")
            exec(statement.sql, values)
        }
        if (statement.guarded) runCatching { run() } else run()
    }

    /**
     * A lock SQLite refuses ONE statement over, leaving the transaction around it standing —
     */
    private class Busy(private val onSql: String, private var times: Int) {
        var fired = 0
            private set

        fun refuses(sql: String): Boolean {
            if (onSql !in sql || times == 0) return false
            times--
            fired++
            return true
        }
    }

    /** Run a shipped `@Query` statement, binding [values] by Room's parameter names. */
    private fun exec(sql: String, values: Map<String, Any>) {
        val (statement, order) = DaoQuerySource.bindOrder(sql, listSizes(values))
        db.prepareStatement(statement).use { ps -> bind(ps, order, values); ps.executeUpdate() }
    }

    private fun listSizes(values: Map<String, Any>): Map<String, Int> =
        values.mapNotNull { (name, v) -> (v as? List<*>)?.let { name to it.size } }.toMap()

    private fun bind(ps: java.sql.PreparedStatement, order: List<String>, values: Map<String, Any>) {
        val consumed = mutableMapOf<String, Int>()
        order.forEachIndexed { i, name ->
            when (val v = values[name] ?: error("no value bound for ':$name'")) {
                is List<*> -> {
                    val n = consumed.getOrDefault(name, 0)
                    ps.setString(i + 1, v[n] as String)
                    consumed[name] = n + 1
                }
                is Int -> ps.setInt(i + 1, v)
                else -> ps.setString(i + 1, v.toString())
            }
        }
    }

    /**
     * The ids the shipped `EmailFtsDao.search` returns — the local half of a user's search, scoped
     */
    private fun search(
        match: String = "token*",
        limit: Int = 50,
        accountScope: List<String> = emptyList(),
    ): List<String> {
        val roles = NOT_SEARCHED_ROLES.toList()
        val sql = DaoQuerySource.daoQuery("EmailFtsDao", "search")
        val (statement, order) = DaoQuerySource.bindOrder(
            sql,
            mapOf("excludedRoles" to roles.size, "accountIds" to accountScope.size),
        )
        var role = 0
        var account = 0
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "match" -> ps.setString(i + 1, match)
                    "excludedRoles" -> ps.setString(i + 1, roles[role++])
                    "accountIds" -> ps.setString(i + 1, accountScope[account++])
                    "accountScopeCount" -> ps.setInt(i + 1, accountScope.size)
                    "limit" -> ps.setInt(i + 1, limit)
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.search")
                }
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }
    }

    /** What the INDEX itself holds, whatever the search query chooses to show. */
    private fun indexed(): List<String> =
        db.prepareStatement("SELECT emailId FROM email_fts ORDER BY emailId").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }

    /** What the display CACHE itself holds. */
    private fun cached(): List<String> =
        db.prepareStatement("SELECT id FROM emails ORDER BY id").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    /** Whose rows are left in [table] — what says an account-scoped statement stayed in its account. */
    private fun accountsIn(table: String): List<String> =
        db.prepareStatement("SELECT accountId FROM $table ORDER BY accountId").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    /** A cached row as `MailRepository` reads it before a bulk move (for [idsAlreadyIn]). */
    private fun row(id: String, mailboxId: String, accountId: String = "acc") = EmailEntity(
        id = id, accountId = accountId, mailboxId = mailboxId, threadId = null, subject = null,
        preview = null, receivedAt = null, fromName = null, fromEmail = null, seen = true,
        flagged = false, hasAttachment = false, sortKey = 0,
    )

    // ---- the cases ----------------------------------------------------------------------------

    @Test fun aRowLabelledTrashIsHiddenByTheQueryAndStaysInTheIndex() {
        // The witness of the query-side filter, restated here so removing it is visibly a
        // regression of a DIFFERENT defence: this row was written already carrying the Trash
        // mailbox (unsynced folder roles, a late role, MIGRATION_14_15), nothing deleted it.
        folder("mb-trash", role = "trash")
        message("labelled", "mb-trash")

        assertEquals(emptyList<String>(), search())
        assertEquals(listOf("labelled"), indexed())
    }

    @Test fun aMessageDeletedFromTheInboxLosesItsIndexRowToo() {
        // The reported case, exactly: indexed while it sat in the Inbox, then thrown away. Its
        // index row still says "inbox", so the query-side filter is blind to it — what keeps it
        // out of the results is that the delete path took the index row with it.
        folder("mb-inbox", role = "inbox")
        message("thrown", "mb-inbox")
        assertEquals(listOf("thrown"), search())

        deletePath("deleteById", mapOf("accountId" to "acc", "id" to "thrown"))

        assertEquals(emptyList<String>(), cached())
        assertEquals(emptyList<String>(), indexed())
        assertEquals(emptyList<String>(), search())
    }

    @Test fun aBulkDeleteTakesEveryIndexRowWithIt() {
        folder("mb-inbox", role = "inbox")
        message("one", "mb-inbox")
        message("two", "mb-inbox")
        message("kept", "mb-inbox")

        deletePath("deleteByIds", mapOf("accountId" to "acc", "ids" to listOf("one", "two")))

        assertEquals(listOf("kept"), cached())
        assertEquals(listOf("kept"), indexed())
        assertEquals(listOf("kept"), search())
    }

    @Test fun deletingInOneAccountLeavesTheSiblingAccountsIndexAlone() {
        // Two accounts of one login (issue #31) share mailbox AND message ids: the delete must be
        // account-scoped on the index for the same reason it is on the cache.
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "inbox", accountId = "accB")
        message("e-9", "mb-7", accountId = "accA")
        message("e-9", "mb-7", accountId = "accB")

        deletePath("deleteById", mapOf("accountId" to "accA", "id" to "e-9"))

        assertEquals(listOf("e-9"), cached())
        assertEquals(listOf("e-9"), indexed())
        assertEquals(listOf("e-9"), search())
        assertEquals(
            listOf("accB"),
            db.prepareStatement("SELECT accountId FROM email_fts").use { ps ->
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            },
        )
    }

    @Test fun anActionThatMovedNothingKeepsTheIndexRowOfWhatItDidNotMove() {
        // Archiving a message that is already in the Archive, moving one to the folder it is in:
        // nothing leaves anything, the message stays in a SEARCHABLE folder, and the row only goes
        //
        // Deleting a message that is already in the Trash is NOT one of these — see
        // [aTrashDeleteTakesTheIndexRowOneByOneAsInBulk].
        folder("mb-archive", role = "archive")
        message("already", "mb-archive")
        message("other", "mb-archive")

        deletePath(noOpEviction("archive"), mapOf("accountId" to "acc", "ids" to listOf("already")))

        assertEquals(listOf("other"), cached())
        assertEquals(listOf("already", "other"), indexed())
        assertEquals(listOf("already", "other"), search())
    }

    @Test fun aNoOpInAFolderSearchNeverLooksAtTakesTheIndexRowWithIt() {
        // The other side of the line the case above draws, and the reason it cannot be drawn on
        // "did anything move?" alone: the same two no-op branches are ALSO how a message is filed
        folder("mb-junk", role = "junk")
        message("reported", "mb-junk")
        message("other", "mb-junk")

        deletePath(noOpEviction("junk"), mapOf("accountId" to "acc", "ids" to listOf("reported")))

        assertEquals(listOf("other"), cached())
        assertEquals("an index row was left for a message in a folder search never looks at", listOf("other"), indexed())
    }

    @Test fun everyNoOpBranchGoesThroughTheEvictionThatWeighsTheFolder() {
        // The wiring the two cases above cannot see: which repository paths hand their empty actions
        // to the one eviction that asks what folder the message stayed in. Each of these has a branch
        //
        // jmapMoveAll is the one that had no such branch at all: `archiveAll`/`moveAllToMailbox`
        // short-circuit the no-op themselves on IMAP, but on JMAP the whole selection goes to the
        val missing = listOf("archive", "moveToMailbox", "archiveAll", "moveAllToMailbox", "jmapMoveAll")
            .filterNot { "$NO_OP_EVICTION(" in DaoQuerySource.mailFunctionBody("MailRepository", it) }
        assertEquals(
            "these MailRepository paths no longer call $NO_OP_EVICTION(): an action that moves " +
                "nothing must evict the cached row WITHOUT un-indexing it",
            emptyList<String>(), missing,
        )
    }

    @Test fun aBulkJmapMoveTellsTheNoOpPartFromThePartThatMoved() {
        // What the wiring rule above cannot state: WHICH ids of a confirmed bulk move never moved.
        // The server's answer does not say — an `Email/set` filing a message into the folder it is
        // already in succeeds like any other — so the split is made against the rows read before the
        // move, and only the part that really left goes to the un-indexing eviction.
        val rows = listOf(row("already", "mb-archive"), row("moved", "mb-inbox"))

        assertEquals(
            "an id whose cached row already sat in the destination did not move, and un-indexing it " +
                "drops a message the server never touched out of offline search",
            listOf("already"), idsAlreadyIn(rows, setOf("already", "moved", "uncached"), "mb-archive"),
        )
        // No cached row means we cannot say where it sat: it belongs to the moved part, whose
        // eviction takes the index row — the conservative direction (the index must not outlive a
        // message that did leave).
        assertEquals(emptyList<String>(), idsAlreadyIn(emptyList(), setOf("uncached"), "mb-archive"))
        // An id the server did not confirm is in neither part: its cached row survives untouched.
        assertEquals(emptyList<String>(), idsAlreadyIn(rows, emptySet(), "mb-archive"))
    }

    @Test fun aBulkDeleteInOneAccountLeavesTheSiblingAccountsRowsAlone() {
        // The bulk halves' account scope, which nothing exercised: the only cross-account case in
        // the repository ran the SINGLE-id path, so dropping `accountId = :accountId` from
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "inbox", accountId = "accB")
        listOf("e-9", "e-10").forEach {
            message(it, "mb-7", accountId = "accA")
            message(it, "mb-7", accountId = "accB")
        }

        deletePath("deleteByIds", mapOf("accountId" to "accA", "ids" to listOf("e-9", "e-10")))

        assertEquals("a bulk delete emptied the sibling account's cache", listOf("e-10", "e-9"), cached())
        assertEquals("a bulk delete emptied the sibling account's index", listOf("e-10", "e-9"), indexed())
        assertEquals(listOf("accB", "accB"), accountsIn("emails"))
        assertEquals(listOf("accB", "accB"), accountsIn("email_fts"))
    }

    @Test fun theDeletePathsLetACancellationThrough() {
        // The signal, not the rows. `runCatching` swallows a CancellationException like any other
        // failure, so a screen closed mid-delete came out of the path looking like a clean delete —
        listOf("deleteById", "deleteByIds").forEach { function ->
            val body = DaoQuerySource.daoFunctionBody("EmailDao", function)
            val cancellationArm = body.substringAfter("CancellationException)", "")
                .substringBefore("catch (")
            assertEquals(
                "EmailDao.$function no longer rethrows a CancellationException: a caller that went " +
                    "away must commit neither half, not be told the delete succeeded",
                true, "throw " in cancellationArm,
            )
            assertEquals(
                "EmailDao.$function guards something with a bare runCatching: it swallows a " +
                    "cancellation as readily as a lock, and here the cache row is already committed " +
                    "— use getOrElseUnlessCancelled so the cancellation still propagates",
                Regex("runCatching").findAll(body).count(),
                Regex("getOrElseUnlessCancelled").findAll(body).count(),
            )
        }
    }

    @Test fun anIndexLockThatDoesNotClearLeavesTheOrphanTheDeleteCannotWaitFor() {
        // The residue, stated as a case instead of as a paragraph. The retry outside the transaction
        // is IMMEDIATE — no delay, no backoff — so a lock still held microseconds later refuses it
        folder("mb-inbox", role = "inbox")
        message("thrown", "mb-inbox")
        message("kept", "mb-inbox")
        val busy = Busy(onSql = "DELETE FROM email_fts", times = 2)

        deletePath("deleteById", mapOf("accountId" to "acc", "id" to "thrown"), busy)

        assertEquals("the un-index was not attempted twice: the retry outside the transaction is gone", 2, busy.fired)
        assertEquals("an unwritable index stopped a message from being deleted", listOf("kept"), cached())
        assertEquals(
            "a lock that never cleared left no orphan — if the delete path can now repair this, say " +
                "so here and in EmailDao's comment, which promises only a single immediate retry",
            listOf("kept", "thrown"), indexed(),
        )
    }

    @Test fun aTrashDeleteTakesTheIndexRowOneByOneAsInBulk() {
        // The two delete paths must state it the same way: deleting a message that is ALREADY in the
        // Trash moves nothing on the server either, but the Trash is excluded from search at both
        val paths = listOf("delete", "deleteAll")
        val sparing = paths.filter { "$NO_OP_EVICTION(" in DaoQuerySource.mailFunctionBody("MailRepository", it) }
        assertEquals(
            "the delete paths must NOT evict through $NO_OP_EVICTION: they know their destination " +
                "is the Trash, which search covers at neither end, so the index row they would " +
                "spare is an orphan no re-seed can clear",
            emptyList<String>(), sparing,
        )
        val removals = paths.associateWith { cacheRemovalsIn(it) }
        assertEquals(
            "these delete paths no longer remove any cached row through EmailDao — has the delete " +
                "stopped emptying the local list, or does it go through a wrapper this rule does " +
                "not know about ($REMOVAL_WRAPPERS)?",
            emptyList<String>(), removals.filterValues { it.isEmpty() }.keys.toList(),
        )
        assertEquals(
            "these delete paths empty the cache with an EmailDao function that leaves the search " +
                "index alone — a message deleted from the Trash would keep an index row the folder " +
                "cache alone hides, and a renamed or forgotten folder would give it back",
            emptyList<String>(),
            removals.flatMap { (path, functions) ->
                functions.filterNot(::unindexes).map { "MailRepository.$path → EmailDao.$it" }
            },
        )

        // And the effect itself, replayed on the statements each path actually issues.
        folder("mb-trash", role = "trash")
        message("one-by-one", "mb-trash")
        message("in-bulk", "mb-trash")
        removals.forEach { (path, functions) ->
            val id = if (path == "delete") "one-by-one" else "in-bulk"
            functions.forEach { function ->
                deletePath(function, mapOf("accountId" to "acc", "id" to id, "ids" to listOf(id)))
            }
        }
        assertEquals(emptyList<String>(), cached())
        assertEquals(emptyList<String>(), indexed())
    }

    /**
     * The `EmailDao` functions `MailRepository.[path]` empties the display cache through: its own
     */
    private fun cacheRemovalsIn(path: String): Set<String> {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", path)
        val direct = Regex("""emailDao\.(\w+)\(""").findAll(body).map { it.groupValues[1] }.toList()
        val delegated = REMOVAL_WRAPPERS.filter { "$it(" in body }.map { daoRemovalIn(it) }
        return (direct + delegated).filter(::deletesCache).toSet()
    }

    private fun deletesCache(function: String): Boolean =
        DaoQuerySource.emailDaoStatements(function).any { it.sql.contains("DELETE FROM emails", ignoreCase = true) }

    private fun unindexes(function: String): Boolean =
        DaoQuerySource.emailDaoStatements(function).any { it.sql.contains("DELETE FROM email_fts", ignoreCase = true) }

    /**
     * The `EmailDao` function the `MailRepository` wrapper [repositoryFunction] evicts with, read
     */
    private fun daoRemovalIn(repositoryFunction: String): String =
        Regex("""emailDao\.(\w+)\(""")
            .findAll(DaoQuerySource.mailFunctionBody("MailRepository", repositoryFunction))
            .map { it.groupValues[1] }
            .distinct()
            .firstOrNull { name ->
                DaoQuerySource.emailDaoStatements(name).any { it.sql.trimStart().startsWith("DELETE", ignoreCase = true) }
            }
            ?: error("MailRepository.$repositoryFunction no longer evicts anything through EmailDao")

    /**
     * The `EmailDao` function an action that moved nothing in a folder of this [role] evicts with —
     */
    private fun noOpEviction(role: String?): String = noOpEvictionDao(noOpEvictionFor("mb-any", role))

    /**
     * The `EmailDao` function the `when` arm for [decision] in `MailRepository.evictAlreadyThere`
     */
    private fun noOpEvictionDao(decision: NoOpEviction): String {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", NO_OP_EVICTION)
        val marker = "${NoOpEviction::class.simpleName}.${decision.name}"
        val start = body.indexOf("$marker ->")
        check(start >= 0) {
            "MailRepository.$NO_OP_EVICTION has no branch for $marker: the shipped rule can answer " +
                "it, so something has to carry it out"
        }
        val rest = body.substring(start + marker.length)
        val arm = rest.indexOf("${NoOpEviction::class.simpleName}.").let { if (it < 0) rest else rest.take(it) }
        Regex("""emailDao\.(\w+)\(""").find(arm)?.let { return it.groupValues[1] }
        val wrapper = REMOVAL_WRAPPERS.filterNot { it == NO_OP_EVICTION }.firstOrNull { "$it(" in arm }
        return daoRemovalIn(
            wrapper ?: error("the $marker branch of MailRepository.$NO_OP_EVICTION evicts nothing through EmailDao"),
        )
    }

    @Test fun theRuleSparesTheIndexRowOnlyWhereASearchLooks() {
        // The decision itself, executed. It is the one thing the cases below must not restate, and
        // the one thing that says WHICH folders are which.
        assertEquals(NoOpEviction.SPARE_INDEX_ROW, noOpEvictionFor("mb-1", "archive"))
        assertEquals(NoOpEviction.SPARE_INDEX_ROW, noOpEvictionFor("mb-1", "inbox"))
        // A folder the cache knows no role for: spared, the harmless direction (an index row in a
        // folder that IS searched is true; one in the Trash is an orphan nothing can clear).
        assertEquals(NoOpEviction.SPARE_INDEX_ROW, noOpEvictionFor("mb-1", null))
        NOT_SEARCHED_ROLES.forEach {
            assertEquals(
                "role '$it' is not searched, so a no-op there must take the index row with the cached one",
                NoOpEviction.TAKE_INDEX_ROW, noOpEvictionFor("mb-1", it),
            )
        }
        // Same trim/lowercase as the search filter, through the one role source.
        assertEquals(NoOpEviction.TAKE_INDEX_ROW, noOpEvictionFor("mb-1", " Trash "))
    }

    @Test fun eachAnswerOfTheRuleIsCarriedOutByTheEvictionThatMatchesIt() {
        // The other half: the arms of the shipped `when`. Swap them and the rule stays right while
        // the app does the opposite of what it decided.
        assertEquals(
            "the ${NoOpEviction.SPARE_INDEX_ROW} branch of MailRepository.$NO_OP_EVICTION un-indexes: " +
                "a message that never left a searched folder would leave offline search for good, " +
                "nothing re-indexing a row the cache no longer holds",
            false, unindexes(noOpEvictionDao(NoOpEviction.SPARE_INDEX_ROW)),
        )
        assertEquals(
            "the ${NoOpEviction.TAKE_INDEX_ROW} branch of MailRepository.$NO_OP_EVICTION spares the " +
                "index row: in a folder search never looks at that row is an orphan whose message is " +
                "no longer cached, and no re-seed can clear it",
            true, unindexes(noOpEvictionDao(NoOpEviction.TAKE_INDEX_ROW)),
        )
    }

    @Test fun theNoOpEvictionAsksWhetherTheFolderIsSearchedAtAll() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", NO_OP_EVICTION)
        assertEquals(
            "MailRepository.$NO_OP_EVICTION must decide through the shipped rule (noOpEvictionFor): " +
                "whether the index row may stay is not 'did anything move?' but 'is this folder " +
                "searched at all?'",
            true, "noOpEvictionFor(" in body,
        )
        assertEquals(
            "MailRepository.$NO_OP_EVICTION restates the roles a search skips instead of asking the " +
                "single source (NOT_SEARCHED_ROLES, through excludedSearchFolderIds): a second copy " +
                "is what lets the index and the search disagree about what a folder is",
            emptyList<String>(), NOT_SEARCHED_ROLES.filter { "\"$it\"" in body },
        )
    }

    @Test fun theDeletePathIsOneTransaction() {
        // Restored after being dropped on the theory that only a transaction could let a broken
        // index block a delete. It cannot — the fallback below is what keeps the delete landing.
        listOf("deleteById", "deleteByIds").forEach { function ->
            assertEquals(
                "the statements EmailDao.$function issues must run as ONE transaction — its own " +
                    "@Transaction or that of the pair it delegates to — so a refused un-index cannot " +
                    "leave the cache delete committed on its own",
                true, DaoQuerySource.emailDaoPath(function).atomic,
            )
        }
    }

    @Test fun theTransactionDoesNotSwallowTheIndexFailureInsideItself() {
        // The form this replaced: both statements in the transaction, the un-index wrapped in a
        // runCatching INSIDE it. It reads as "the delete lands whatever the index does", and it does
        listOf("deleteById", "deleteByIds").forEach { function ->
            assertEquals(
                "EmailDao.$function guards a statement INSIDE its transaction: a failure swallowed " +
                    "there commits the half that succeeded. The recovery belongs outside — let the " +
                    "transaction fail whole, then replay the cache delete on its own",
                emptyList<String>(),
                DaoQuerySource.emailDaoPath(function).main.filter { it.guarded }.map { it.function },
            )
        }
    }

    @Test fun aRefusedIndexStatementTakesNeitherHalfWithIt() {
        // The case the shape rules above are about, played out. The index delete is refused once —
        // a lock, not a corruption — and the two halves must end up in step: either both rows go or
        // neither does, and the message must not be left cached-out but still searchable.
        folder("mb-inbox", role = "inbox")
        message("thrown", "mb-inbox")
        message("kept", "mb-inbox")
        val busy = Busy(onSql = "DELETE FROM email_fts", times = 1)

        deletePath("deleteById", mapOf("accountId" to "acc", "id" to "thrown"), busy)

        assertEquals("the index refusal was never exercised", 1, busy.fired)
        assertEquals(listOf("kept"), cached())
        assertEquals(
            "a refused un-index left an index row whose cached message is gone — the orphan no " +
                "re-seed clears, and the deleted message comes back in search for good",
            listOf("kept"), indexed(),
        )
    }

    @Test fun anIndexTooBrokenToWriteStillLetsTheMailBeDeleted() {
        // Issue #71's ground: a search table that cannot be written (damaged, or locked). Deleting
        // is a folder action, not a search feature — it must land whatever the index says. It could
        folder("mb-inbox", role = "inbox")
        message("thrown", "mb-inbox")
        message("kept", "mb-inbox")
        db.createStatement().use { it.executeUpdate("DROP TABLE email_fts") }
        // Stated before the delete, so "the cache row is gone" below is the delete's doing and not
        // a row that was never written: without it the case passes on an empty database.
        assertEquals(listOf("kept", "thrown"), cached())

        val outcome = runCatching { deletePath("deleteById", mapOf("accountId" to "acc", "id" to "thrown")) }

        assertEquals("the cache delete did not stand", listOf("kept"), cached())
        assertEquals("the delete path let an index failure escape", null, outcome.exceptionOrNull()?.message)
    }

    @Test fun aRetentionEvictionKeepsTheIndexRowsOfTheMailItDropped() {
        // The sync window is not a removal: these messages are still in their folder on the server,
        // they merely fell outside what the account keeps offline (`retentionEvictions`). Routing
        folder("mb-inbox", role = "inbox")
        message("old", "mb-inbox")
        message("recent", "mb-inbox")

        deletePath(retentionEviction(), mapOf("accountId" to "acc", "ids" to listOf("old")))

        assertEquals(listOf("recent"), cached())
        assertEquals(listOf("old", "recent"), indexed())
        assertEquals(listOf("old", "recent"), search())
    }

    /**
     * The `EmailDao` function `MailRepository.pruneRetention` evicts with — read out of that
     */
    private fun retentionEviction(): String =
        Regex("""emailDao\.(\w+)\(""")
            .findAll(DaoQuerySource.mailFunctionBody("MailRepository", "pruneRetention"))
            .map { it.groupValues[1] }
            .distinct()
            .firstOrNull { name ->
                DaoQuerySource.emailDaoStatements(name).any { it.sql.trimStart().startsWith("DELETE", ignoreCase = true) }
            }
            ?: error("MailRepository.pruneRetention no longer evicts anything through EmailDao")

    @Test fun pruningTheCachedPageDoesNotUnindexAnything() {
        // deleteNotIn is window eviction: the message is still in its folder, it just fell out of
        // the page the list caches. Wiring it to the index would empty search as the user scrolls.
        folder("mb-inbox", role = "inbox")
        message("page1", "mb-inbox")
        message("page2", "mb-inbox")

        exec(
            DaoQuerySource.emailDaoQuery("deleteNotIn"),
            mapOf("accountId" to "acc", "mailboxId" to "mb-inbox", "keepIds" to listOf("page1")),
        )

        assertEquals(listOf("page1"), cached())
        assertEquals(listOf("page1", "page2"), indexed())
        assertEquals(listOf("page1", "page2"), search())
    }

    private companion object {
        /** The `MailRepository` function every "the destination is where it already is" branch
         *  hands its ids to — named once, so the two rules above cannot drift apart. The delete
         *  paths are the exception, and [REMOVAL_WRAPPERS] is how the rule sees what they use. */
        const val NO_OP_EVICTION = "evictAlreadyThere"

        /** The wrapper that takes the index row with the cached one — where the no-op eviction
         *  sends a folder no search looks at. */
        const val INDEXING_REMOVAL = "deleteFromCacheAndIndex"

        /** `MailRepository`'s own removal wrappers: a path's cache removal is either a direct
         *  `emailDao.` call or one of these, and each resolves to the DAO function it evicts with. */
        val REMOVAL_WRAPPERS = listOf(INDEXING_REMOVAL, NO_OP_EVICTION)
    }
}
