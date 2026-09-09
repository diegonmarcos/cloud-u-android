package app.sterna.core.data.mail

import app.sterna.core.data.db.AccountMailboxId
import app.sterna.core.data.db.AccountMailboxRole
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxIdRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * What a full folder-list sync purges, and in statements SQLite will actually accept.
 */
class MailboxEvictionsTest {

    // -- level 1: the decision, executed ---------------------------------------------------

    @Test fun nothingIsPurgedWhenTheServerStillCarriesEveryCachedFolder() {
        // THE TRAP OF THE OBVIOUS FIX: chunking the `NOT IN (:keepIds)` into several statements
        // deletes everything outside each chunk in turn — the whole drawer, one batch at a time.
        // 1 040 folders, all still on the server, cut into six chunks: nothing may be deleted.
        val cached = (1..1040).map { "Folder$it" }

        val batches = mailboxEvictions(cached, keepIds = cached.toSet())

        assertEquals(emptyList<List<String>>(), batches)
    }

    @Test fun onlyTheFoldersTheServerDroppedArePurged() {
        val cached = listOf("INBOX", "Renamed.old", "Archive", "Trash")

        val batches = mailboxEvictions(cached, keepIds = setOf("INBOX", "Archive", "Trash", "Renamed.new"))

        // Exactly the one folder that left, and nothing else — "Renamed.new" is new on the server,
        // not something to delete, and the three survivors stay.
        assertEquals(listOf(listOf("Renamed.old")), batches)
    }

    @Test fun noPurgeBatchCanOverrunTheSqliteBound() {
        // The reporter's account: 1 040 folders cached, the whole tree gone from the server (an
        // IMAP namespace change). One statement per id list would bind 1 040 variables; SQLite
        // below Android 12 accepts 999.
        val cached = (1..1040).map { "Folder$it" }

        val batches = mailboxEvictions(cached, keepIds = emptySet())

        assertEquals(listOf(200, 200, 200, 200, 200, 40), batches.map { it.size })
        assertTrue(
            "a batch of ${batches.maxOf { it.size }} ids goes into one IN (...)",
            batches.all { it.size <= MAX_CHANGES },
        )
        assertEquals(cached, batches.flatten())
    }

    // -- level 2: the call site, executed --------------------------------------------------

    @Test fun replaceAllPurgesTheAccountsVanishedFoldersInBatches() {
        // 451 cached folders, the server now lists two. The 450 that left must go, in batches, and
        // the other account's rows must never be named.
        val dao = FakeMailboxDao(
            cachedByAccount = mapOf(
                "acct-1" to listOf("INBOX") + (1..450).map { "Gone$it" },
                "acct-2" to listOf("INBOX", "Other"),
            ),
        )

        runBlocking { dao.replaceAll("acct-1", listOf(mailbox("acct-1", "INBOX"), mailbox("acct-1", "New"))) }

        assertEquals(
            listOf(
                "acct-1" to (1..200).map { "Gone$it" },
                "acct-1" to (201..400).map { "Gone$it" },
                "acct-1" to (401..450).map { "Gone$it" },
            ),
            dao.deleteByIdsCalls,
        )
        assertEquals(listOf("acct-1"), dao.idsForAccountCalls)
        assertEquals(listOf(listOf("INBOX", "New")), dao.upsertCalls)
        // The drawer must never blink empty: the new list lands before anything is removed.
        assertEquals("upsertAll", dao.order.first())
    }

    @Test fun replaceAllDeletesNothingWhenTheServerListMatchesTheCache() {
        val dao = FakeMailboxDao(cachedByAccount = mapOf("acct-1" to listOf("INBOX", "Archive")))

        runBlocking { dao.replaceAll("acct-1", listOf(mailbox("acct-1", "INBOX"), mailbox("acct-1", "Archive"))) }

        assertEquals(emptyList<Pair<String, List<String>>>(), dao.deleteByIdsCalls)
    }

    // -- the new statement, run on a real SQLite ------------------------------------------

    @Test fun idsForAccountReadsEVERYFolderOfOneAccountAndNoOtherAccounts() {
        // The purge deletes whatever this returns, so anything this statement leaves out is a
        // folder the purge stops touching — silently. Two ways to lose rows, and the fixture must
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE mailboxes(accountId TEXT, id TEXT, name TEXT, role TEXT, " +
                        "parentId TEXT, sortOrder INTEGER, totalEmails INTEGER, unreadEmails INTEGER, " +
                        "PRIMARY KEY(accountId, id))",
                )
                it.executeUpdate(
                    "INSERT INTO mailboxes(accountId, id, name, role, parentId, sortOrder, " +
                        "totalEmails, unreadEmails) VALUES" +
                        "('acct-1','INBOX','INBOX','inbox',NULL,0,0,0)," +
                        "('acct-1','INBOX/2024','2024',NULL,'INBOX',0,0,0)," +
                        "('acct-1','Work','Work',NULL,NULL,0,0,0)," +
                        "('acct-1','Work/Sent','Sent','sent','Work',0,0,0)," +
                        "('acct-2','Secret','Secret',NULL,NULL,0,0,0)",
                )
            }
            val sql = DaoQuerySource.daoQuery("MailboxDao", "idsForAccount")
            val (positional, order) = DaoQuerySource.bindOrder(sql)
            assertEquals(listOf("accountId"), order)
            val ids = db.prepareStatement(positional).use { ps ->
                ps.setString(1, "acct-1")
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            // The whole set, nested folders included; acct-2's folder is not in it.
            assertEquals(listOf("INBOX", "INBOX/2024", "Work", "Work/Sent"), ids.sorted())
        }
    }

    @Test fun replaceAllStaysOneTransaction() {
        assertTrue(
            "MailboxDao.replaceAll must keep its @Transaction. The purge used to be ONE statement, " +
                "atomic for free; it is now a SELECT plus N DELETEs, and a batch refused half way " +
                "(a transient SQLITE_BUSY while the push service writes) would commit the upsert " +
                "and part of the purge — the old and the new folder lists mixed in the drawer, a " +
                "rename showing both names, until the next successful refresh.",
            DaoQuerySource.isTransactional("MailboxDao", "replaceAll"),
        )
    }

    // -- level 3: the statement that broke it is gone from the DAO -------------------------

    @Test fun noNotInStatementSurvivesInMailboxDao() {
        val code = withoutComments(locate(DAO_PATH).readText())

        assertTrue(
            "no `NOT IN` may appear in MailboxDao: one bound variable per server folder is what " +
                "made every refresh of a 1 040-folder account fail to compile (#142). Comments " +
                "are stripped before this looks, so prose may say \"not in\" freely — only code " +
                "counts. Code was:\n$code",
            !code.contains("NOT IN", ignoreCase = true),
        )
    }

    // -- fixtures --------------------------------------------------------------------------

    private fun mailbox(accountId: String, id: String) = MailboxEntity(
        accountId = accountId,
        id = id,
        name = id,
        role = null,
        sortOrder = 0,
        totalEmails = 0,
        unreadEmails = 0,
    )

    /**
     * A [MailboxDao] whose `replaceAll` is the SHIPPED default method: the test executes the real
     */
    private class FakeMailboxDao(private val cachedByAccount: Map<String, List<String>>) : MailboxDao {

        val upsertCalls = mutableListOf<List<String>>()
        val idsForAccountCalls = mutableListOf<String>()
        val deleteByIdsCalls = mutableListOf<Pair<String, List<String>>>()
        val order = mutableListOf<String>()

        override suspend fun upsertAll(mailboxes: List<MailboxEntity>) {
            order += "upsertAll"
            upsertCalls += mailboxes.map { it.id }
        }

        override suspend fun idsForAccount(accountId: String): List<String> {
            order += "idsForAccount"
            idsForAccountCalls += accountId
            return cachedByAccount[accountId].orEmpty()
        }

        override suspend fun deleteByIds(accountId: String, ids: List<String>) {
            order += "deleteByIds"
            deleteByIdsCalls += accountId to ids
        }

        override fun observeAll(accountId: String): Flow<List<MailboxEntity>> = error("unused")
        override suspend fun deleteAll(): Unit = error("unused")
        override suspend fun deleteForAccount(accountId: String): Unit = error("unused")
        override suspend fun accountIds(): List<String> = error("unused")
        override suspend fun idForRole(accountId: String, role: String): String? = error("unused")
        override fun observeSentMailboxes(accountIds: List<String>): Flow<List<AccountMailboxId>> = error("unused")
        override fun observeRoles(accountIds: List<String>): Flow<List<AccountMailboxRole>> = error("unused")
        override suspend fun idForAnyName(accountId: String, names: List<String>): String? = error("unused")
        override suspend fun searchOrder(accountId: String): List<MailboxIdRole> = error("unused")
        override suspend fun roleForId(accountId: String, id: String): String? = error("unused")
        override suspend fun adjustCounts(accountId: String, id: String, totalDelta: Int, unreadDelta: Int): Unit =
            error("unused")
    }

    private companion object {
        const val DAO_PATH = "core/data/src/main/kotlin/app/sterna/core/data/db/MailboxDao.kt"

        /** [relative] resolved from the module's working directory, walking up to the repo root. */
        fun locate(relative: String): File {
            val fromModule = relative.substringAfter("core/data/")
            val cwd = System.getProperty("user.dir").orEmpty()
            var dir: File? = File(cwd).absoluteFile
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { return it }
                File(dir, fromModule).takeIf { it.isFile }?.let { return it }
                dir = dir.parentFile
            }
            error("Cannot find $relative from $cwd")
        }

        /**
         * [source] with its Kotlin comments removed, string literals left alone — a source lint
         * that reads prose as code fails on files that are correct.
         */
        fun withoutComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                    source.startsWith("/*", i) -> {
                        val end = source.indexOf("*/", i + 2)
                        i = if (end < 0) source.length else end + 2
                        out.append(' ')
                    }
                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3)
                        val stop = if (end < 0) source.length else end + 3
                        out.append(source, i, stop)
                        i = stop
                    }
                    source[i] == '"' -> {
                        out.append(source[i]); i++
                        while (i < source.length && source[i] != '"') {
                            if (source[i] == '\\' && i + 1 < source.length) { out.append(source[i]); i++ }
                            out.append(source[i]); i++
                        }
                        if (i < source.length) { out.append(source[i]); i++ }
                    }
                    else -> { out.append(source[i]); i++ }
                }
            }
            return out.toString()
        }
    }
}
