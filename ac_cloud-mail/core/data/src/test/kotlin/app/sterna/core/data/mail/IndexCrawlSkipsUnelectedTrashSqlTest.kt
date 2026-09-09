package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The OTHER half of "a second trash never surfaces in search": the index build itself
 */
class IndexCrawlSkipsUnelectedTrashSqlTest {
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

    private fun folder(id: String, role: String?, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO mailboxes VALUES(?, ?, 'Folder', ?, NULL, 0, 0, 0)").use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, role); it.executeUpdate()
        }
    }

    private fun cached(emailId: String, mailboxId: String, accountId: String = "acc") {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'quarterly token report', 'preview', " +
                "'', 'Alex Rivera', 'alex@example.org', 0, 0, 0, 100, NULL)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.executeUpdate()
        }
    }

    /** Run the shipped crawl statement with the single role source the app passes it. */
    private fun crawl() {
        val roles = NOT_SEARCHED_ROLES.toList()
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("EmailFtsDao", "insertFromEmails"),
            listParams = mapOf("excludedRoles" to roles.size),
        )
        var role = 0
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "excludedRoles" -> ps.setString(i + 1, roles[role++])
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.insertFromEmails")
                }
            }
            ps.executeUpdate()
        }
    }

    private fun indexed(): List<String> =
        db.prepareStatement("SELECT emailId FROM email_fts ORDER BY emailId").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    @Test fun theCrawlIndexesAnOrdinaryFoldersMail() {
        folder("mb-inbox", role = "inbox")
        cached("kept", "mb-inbox")

        crawl()

        assertEquals(listOf("kept"), indexed())
    }

    @Test fun theCrawlSkipsTheElectedTrashAndJunk() {
        folder("mb-inbox", role = "inbox")
        folder("mb-trash", role = "trash")
        folder("mb-junk", role = "junk")
        cached("kept", "mb-inbox"); cached("thrown", "mb-trash"); cached("junked", "mb-junk")

        crawl()

        assertEquals(listOf("kept"), indexed())
    }

    /**
     * The case this branch exists for: the SECOND trash, the one that lost the role and is stored
     */
    @Test fun theCrawlSkipsAnUnelectedTrashAndAnUnelectedJunk() {
        folder("mb-inbox", role = "inbox")
        folder("mb-trash", role = "trash")
        folder("mb-shared-trash", role = "~trash")
        folder("mb-shared-junk", role = "~junk")
        cached("kept", "mb-inbox")
        cached("shared-thrown", "mb-shared-trash")
        cached("shared-junked", "mb-shared-junk")

        crawl()

        assertEquals(listOf("kept"), indexed())
    }

    /**
     * The witness: the mark is not a wildcard that swallows every folder it touches. A lost claim
     */
    @Test fun theCrawlStillIndexesAFolderMarkedForAnythingElse() {
        folder("mb-sent", role = "~sent")
        folder("mb-odd", role = "~")
        folder("mb-none", role = null)
        cached("sent", "mb-sent"); cached("odd", "mb-odd"); cached("none", "mb-none")

        crawl()

        assertEquals(listOf("none", "odd", "sent"), indexed())
    }
}
