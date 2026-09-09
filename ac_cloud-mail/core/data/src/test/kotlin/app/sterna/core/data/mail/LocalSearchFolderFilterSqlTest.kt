package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, that the local index half of a search
 */
class LocalSearchFolderFilterSqlTest {
    private lateinit var db: Connection

    /**
     * The DAO's own statement, with `:match` / `:excludedRoles` / `:accountIds` /
     */
    private fun searchSql(accountScope: List<String>) = DaoQuerySource.bindOrder(
        DaoQuerySource.daoQuery("EmailFtsDao", "search"),
        listParams = mapOf(
            "excludedRoles" to NOT_SEARCHED_ROLES.size,
            "accountIds" to accountScope.size,
        ),
    )

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

    private fun index(
        emailId: String, mailboxId: String, subject: String = "quarterly token report",
        accountId: String = "acc", sortKey: Long = 100,
    ) {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, " +
                "body, preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, " +
                "sortKey) VALUES(?, ?, ?, NULL, ?, 'Alex Rivera alex@example.org', '', " +
                "'preview', '', 'Alex Rivera', 'alex@example.org', 0, 0, 0, ?)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.setString(4, subject); it.setLong(5, sortKey)
            it.executeUpdate()
        }
    }

    /**
     * Put an excluded folder's id on an index row — the state this filter exists for.
     */
    private fun labelIndexRow(emailId: String, mailboxId: String) {
        db.prepareStatement("UPDATE email_fts SET mailboxId = ? WHERE emailId = ?").use {
            it.setString(1, mailboxId); it.setString(2, emailId); it.executeUpdate()
        }
    }

    /**
     * The ids the shipped search query returns for [match], scoped to [accountScope].
     */
    private fun search(match: String, limit: Int = 50, accountScope: List<String> = emptyList()): List<String> {
        val (sql, order) = searchSql(accountScope)
        val roles = NOT_SEARCHED_ROLES.toList()
        var role = 0
        var account = 0
        return db.prepareStatement(sql).use { ps ->
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
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("emailId")) }
            }
        }
    }

    /** The witness: what the INDEX holds, filter or no filter. */
    private fun indexedIds(match: String): List<String> =
        db.prepareStatement("SELECT emailId FROM email_fts WHERE email_fts MATCH ? ORDER BY emailId").use { ps ->
            ps.setString(1, match)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }

    @Test fun aMessageInASearchableFolderIsReturned() {
        folder("mb-inbox", role = "inbox")
        index("kept", "mb-inbox")

        assertEquals(listOf("kept"), search("token*"))
    }

    @Test fun aRowLabelledTrashIsHiddenFromResultsWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox")
        folder("mb-trash", role = "trash")
        index("thrown", "mb-inbox")
        assertEquals(listOf("thrown"), search("token*"))

        labelIndexRow("thrown", "mb-trash")

        assertEquals(emptyList<String>(), search("token*"))
        // The witness: nothing cleaned the index — the QUERY is what hides the message.
        assertEquals(listOf("thrown"), indexedIds("token*"))
    }

    @Test fun aRowLabelledJunkIsHiddenFromResultsWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox")
        folder("mb-junk", role = "junk")
        index("junked", "mb-inbox")
        assertEquals(listOf("junked"), search("token*"))

        labelIndexRow("junked", "mb-junk")

        assertEquals(emptyList<String>(), search("token*"))
        assertEquals(listOf("junked"), indexedIds("token*"))
    }

    @Test fun aSpamFolderIsHiddenUnderWhateverSpellingTheServerSentItsRole() {
        // Servers spell roles inconsistently (`Trash`, ` Spam `): the same folding the index build
        // applies (LOWER(TRIM(...))) must apply here, or the filter misses the folder it targets.
        folder("mb-inbox", role = "inbox")
        folder("mb-spam", role = " Spam ")
        folder("mb-trash", role = "TRASH")
        index("kept", "mb-inbox")
        index("spam", "mb-spam")
        index("thrown", "mb-trash")

        assertEquals(listOf("kept"), search("token*"))
        assertEquals(listOf("kept", "spam", "thrown"), indexedIds("token*"))
    }

    /**
     * The SECOND trash. An account with a personal `Trash` and a shared `Shared/…/Trash` gives the
     */
    @Test fun aRowLabelledWithAnUnelectedTrashIsHiddenToo() {
        folder("mb-inbox", role = "inbox")
        folder("mb-trash", role = "trash")
        folder("mb-shared-trash", role = "~trash")
        index("thrown", "mb-inbox")
        assertEquals(listOf("thrown"), search("token*"))

        labelIndexRow("thrown", "mb-shared-trash")

        assertEquals(emptyList<String>(), search("token*"))
        assertEquals(listOf("thrown"), indexedIds("token*"))
    }

    @Test fun aRowLabelledWithAnUnelectedJunkIsHiddenToo() {
        folder("mb-inbox", role = "inbox")
        folder("mb-shared-junk", role = "~junk")
        index("junked", "mb-inbox")
        assertEquals(listOf("junked"), search("token*"))

        labelIndexRow("junked", "mb-shared-junk")

        assertEquals(emptyList<String>(), search("token*"))
        assertEquals(listOf("junked"), indexedIds("token*"))
    }

    /**
     * The witness for the two above: the mark is not a wildcard. A folder whose role merely STARTS
     */
    @Test fun theMarkHidesTrashAndJunkAndNothingElse() {
        folder("mb-sent", role = "~sent")
        folder("mb-odd", role = "~")
        index("sent", "mb-sent")
        index("odd", "mb-odd")

        assertEquals(listOf("odd", "sent"), search("token*").sorted())
    }

    @Test fun aHitWhoseFolderIsNotCachedYetIsStillReturned() {
        // Index and folder list fill in independently. An unknown folder must not turn "not synced
        // yet" into "no results" while the user is typing: local search stays monotonic.
        index("early", "mb-not-synced-yet")

        assertEquals(listOf("early"), search("token*"))
    }

    @Test fun aSiblingAccountsTrashDoesNotHideThisAccountsMessage() {
        // Two accounts of one login (issue #31) can mint the SAME mailbox id: account A's inbox
        // and account B's trash both being "mb-7" must not make A's message vanish.
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "trash", accountId = "accB")
        index("a1", "mb-7", accountId = "accA")
        index("b1", "mb-7", accountId = "accB")

        assertEquals(listOf("a1"), search("token*"))
        assertEquals(listOf("a1", "b1"), indexedIds("token*"))
    }

    /**
     * A row indexed under an account that is GONE stays cherchable otherwise: the index carries no
     */
    @Test fun aRowOfAnAccountThatIsGoneIsHiddenWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox", accountId = "accA")
        index("live", "mb-inbox", accountId = "accA")
        index("ghost", "mb-inbox", accountId = "accGhost")

        assertEquals(listOf("live"), search("token*", accountScope = listOf("accA")))
        assertEquals(listOf("ghost", "live"), indexedIds("token*"))
    }

    /**
     * The inverse witness for the case above: name BOTH accounts and both hits come back. Without
     */
    @Test fun everyAccountNamedInTheScopeIsSearched() {
        folder("mb-inbox", role = "inbox", accountId = "accA")
        index("live", "mb-inbox", accountId = "accA")
        index("ghost", "mb-inbox", accountId = "accGhost")

        assertEquals(listOf("ghost", "live"), search("token*", accountScope = listOf("accA", "accGhost")).sorted())
    }

    /**
     * An EMPTY scope filters nothing, deliberately. `AccountStore.accounts()` answers with an empty
     */
    @Test fun anEmptyScopeFiltersNoAccountAtAll() {
        folder("mb-inbox", role = "inbox", accountId = "accA")
        index("live", "mb-inbox", accountId = "accA")
        index("ghost", "mb-inbox", accountId = "accGhost")

        assertEquals(listOf("ghost", "live"), search("token*", accountScope = emptyList()).sorted())
    }

    /**
     * The scope does not make the role join's own account match redundant. Two accounts of one
     */
    @Test fun accountScopeDoesNotReplaceTheRoleJoinsAccountMatch() {
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "trash", accountId = "accB")
        index("a1", "mb-7", accountId = "accA")
        index("b1", "mb-7", accountId = "accB")

        assertEquals(listOf("a1"), search("token*", accountScope = listOf("accA", "accB")))
        assertEquals(listOf("a1", "b1"), indexedIds("token*"))
    }

    @Test fun anEmptyIndexSimplyAnswersNothing() {
        folder("mb-inbox", role = "inbox")

        assertEquals(emptyList<String>(), search("token*"))
    }
}
