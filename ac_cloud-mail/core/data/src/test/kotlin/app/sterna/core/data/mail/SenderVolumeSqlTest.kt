package app.sterna.core.data.mail

import app.sterna.core.data.db.NOT_SNOOZED_EMAILS_SQL
import app.sterna.core.data.db.SENDER_MESSAGE_IDS_SQL
import app.sterna.core.data.db.SENDER_VOLUMES_SQL
import app.sterna.core.data.db.SENDER_VOLUME_SCOPE_SQL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Verifies, against in-memory SQLite, the two statements behind "Mail by sender":
 */
class SenderVolumeSqlTest {
    private lateinit var db: Connection

    private data class Vol(val email: String, val name: String?, val total: Int, val unread: Int, val latest: Long)

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
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
            // The snooze table is part of the bench because the scope clause consults it. It was
            // absent from the first version of this file, and that absence is structurally why a
            // missing snooze filter could not be seen here: a predicate over a table the bench
            // does not have cannot be exercised, and a table nobody names cannot be missed.
            st.executeUpdate(
                "CREATE TABLE snoozed(emailId TEXT, accountId TEXT, until INTEGER, " +
                    "PRIMARY KEY(accountId, emailId))",
            )
        }
        // Every account in this file has the same folder layout, one folder per role plus two
        // untyped ones — so a test only has to say which folder a message sits in.
        listOf("acc", "other").forEach { account ->
            folder(account, "inbox", "Inbox", "inbox")
            folder(account, "sent", "Sent", "sent")
            folder(account, "drafts", "Drafts", "drafts")
            folder(account, "trash", "Trash", "trash")
            folder(account, "junk", "Junk", "junk")
            folder(account, "spam", "Spam", "spam")
            folder(account, "archive", "Archive", "archive")
            folder(account, "untyped", "Projects", null)
        }
        // …plus ONE folder id that means different things in the two accounts: this account's
        // Sent folder, the sibling's untyped one. Without it, dropping the account scope from the
        // sub-query is invisible — both accounts' "sent" would be a Sent folder either way, and a
        // mutation nothing can distinguish reads as coverage.
        folder("acc", "shared", "Outgoing", "sent")
        folder("other", "shared", "Projects", null)
    }

    @After fun tearDown() = db.close()

    private fun folder(accountId: String, id: String, name: String, role: String?) {
        db.prepareStatement("INSERT INTO mailboxes VALUES(?, ?, ?, ?, NULL, 0, 0, 0)").use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, name)
            it.setString(4, role); it.executeUpdate()
        }
    }

    private fun insert(
        id: String,
        from: String?,
        name: String? = "N",
        seen: Int = 1,
        sortKey: Long = 100,
        mailbox: String = "inbox",
        accountId: String = "acc",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'subj', 'prev', '', ?, ?, ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setString(4, name); ps.setString(5, from); ps.setInt(6, seen); ps.setLong(7, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, untilMillis: Long, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO snoozed VALUES(?, ?, ?)").use {
            it.setString(1, id); it.setString(2, accountId); it.setLong(3, untilMillis); it.executeUpdate()
        }
    }

    /** Run one shipped DAO statement, binding its named parameters from [args]. */
    private fun <T> query(function: String, args: Map<String, String>, read: (ResultSet) -> T): List<T> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery(function))
        check(order.toSet() == args.keys) {
            "EmailDao.$function binds $order — this test offers ${args.keys}"
        }
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name -> ps.setString(i + 1, args.getValue(name)) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    }

    private fun volumes(accountId: String = "acc"): List<Vol> =
        query("senderVolumes", mapOf("accountId" to accountId)) {
            Vol(it.getString("email"), it.getString("name"), it.getInt("total"), it.getInt("unread"), it.getLong("latest"))
        }

    private fun ids(email: String, accountId: String = "acc"): List<String> =
        query("senderMessageIds", mapOf("accountId" to accountId, "email" to email)) { it.getString("id") }

    // -- what makes a line, and what does not -------------------------------------------------

    @Test fun `one address in several spellings is one line`() {
        insert("m1", from = "A@Example.com")
        insert("m2", from = "a@example.COM")
        val rows = volumes()
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().total)
    }

    @Test fun `a plus tag makes its own line`() {
        // a+news@ and a+facture@ are two subscriptions. Folding them together would also mean a
        // Sieve `address :is "from"` written on the untagged address, which filters both.
        insert("m1", from = "a+news@example.com")
        insert("m2", from = "a+facture@example.com")
        assertEquals(
            setOf("a+news@example.com", "a+facture@example.com"),
            volumes().mapTo(mutableSetOf()) { it.email },
        )
    }

    @Test fun `two spellings differing by a non-ASCII capital are TWO lines`() {
        // Pinned rather than endured: SQLite's lower() is ASCII-only, so "É" is not folded onto
        // "é" and these are two groups. Kotlin's lowercase() IS Unicode-aware, so anything that
        insert("m1", from = "Éric@example.com")
        insert("m2", from = "éric@example.com")
        val rows = volumes()
        assertEquals(2, rows.size)
        assertEquals(
            "the two SQL groups collapse to one Kotlin key — never key a list on lowercase()",
            1,
            rows.mapTo(mutableSetOf()) { it.email.lowercase() }.size,
        )
    }

    @Test fun `a message with no usable sender makes no line at all`() {
        insert("m1", from = null)
        insert("m2", from = "")
        insert("m3", from = "real@example.com")
        assertEquals(listOf("real@example.com"), volumes().map { it.email })
        // …and so the header total, which is the sum of the lines, is 1 and not 3. That is the
        // reason it is computed from the lines instead of a COUNT(*) of the cache.
        assertEquals(1, volumes().sumOf { it.total })
    }

    // -- which folders are counted --------------------------------------------------------------

    @Test fun `each outgoing or discarded folder is excluded, one by one`() {
        // Folder by folder, so a shortened exclusion list names WHICH folder came back.
        listOf("sent", "drafts", "trash", "junk", "spam").forEach { role ->
            db.createStatement().use { it.executeUpdate("DELETE FROM emails") }
            insert("in", from = "a@example.com", mailbox = "inbox")
            insert("out", from = "a@example.com", mailbox = role)
            assertEquals(
                "a message in the '$role' folder must not be counted",
                1,
                volumes().single().total,
            )
            assertEquals("…and its id must not be handed to the delete either", listOf("in"), ids("a@example.com"))
        }
    }

    @Test fun `a folder the server did not type is counted`() {
        // `role NOT IN (…)` is NULL — hence false — for a NULL role, so without the explicit
        // `role IS NULL OR` an untyped folder vanishes. On a server that types nothing, that
        // folder is the inbox and the screen counts zero messages.
        insert("m1", from = "a@example.com", mailbox = "untyped")
        assertEquals(1, volumes().single().total)
        assertEquals(listOf("m1"), ids("a@example.com"))
    }

    // -- which account is counted ---------------------------------------------------------------

    @Test fun `a sibling account whose folders share ids is not counted`() {
        // Stalwart numbers mailboxes per account: "inbox" means a different folder in each
        // account (#121). On a counting screen a phantom duplicate is invisible.
        insert("a1", from = "a@example.com", accountId = "acc")
        insert("b1", from = "a@example.com", accountId = "other")
        insert("b2", from = "a@example.com", accountId = "other")
        assertEquals(1, volumes("acc").single().total)
        assertEquals(2, volumes("other").single().total)
        assertEquals(listOf("a1"), ids("a@example.com", "acc"))
    }

    @Test fun `a folder id another account types differently stays excluded here`() {
        // "shared" is THIS account's Sent folder and the sibling's untyped one. The folder
        // exclusion has to be resolved inside the acting account: read across accounts, the
        // sibling's row makes the id look countable and the user's own Sent folder — her own
        // address — walks back into the screen.
        insert("mine", from = "me@example.com", mailbox = "shared", accountId = "acc")
        insert("theirs", from = "me@example.com", mailbox = "shared", accountId = "other")
        assertEquals(emptyList<Vol>(), volumes("acc"))
        assertEquals(emptyList<String>(), ids("me@example.com", "acc"))
        // …and the sibling, whose "shared" is untyped, does count it.
        assertEquals(1, volumes("other").single().total)
    }

    @Test fun `ghost rows left by a pre-1_4_6 install are not counted`() {
        insert("ghost", from = "a@example.com", accountId = "")
        insert("m1", from = "a@example.com", accountId = "acc")
        assertEquals(1, volumes("acc").single().total)
        assertEquals(listOf("m1"), ids("a@example.com", "acc"))
    }

    // -- what the owner already put aside ---------------------------------------------------------

    @Test fun `a message snoozed into the future is neither counted nor deleted`() {
        // She put it aside on purpose: it is in no list and in no unread badge. Counting it here
        // would be the single place it comes back — and since the clause is shared, the delete
        // would carry off mail she cannot see anywhere.
        insert("here", from = "a@example.com", seen = 0)
        insert("later", from = "a@example.com", seen = 0)
        snooze("later", untilMillis = Long.MAX_VALUE)

        val row = volumes().single()
        assertEquals(1, row.total)
        assertEquals(1, row.unread)
        assertEquals(listOf("here"), ids("a@example.com"))
    }

    @Test fun `a snooze that has run out counts again`() {
        insert("back", from = "a@example.com", seen = 0)
        snooze("back", untilMillis = 1)
        assertEquals(1, volumes().single().total)
        assertEquals(listOf("back"), ids("a@example.com"))
    }

    @Test fun `one account's snooze does not hide a sibling account's message`() {
        // Sub-accounts of one login can mint the same email id (#31).
        insert("e1", from = "a@example.com", accountId = "acc")
        insert("e1", from = "a@example.com", accountId = "other")
        snooze("e1", untilMillis = Long.MAX_VALUE, accountId = "acc")
        assertEquals(emptyList<Vol>(), volumes("acc"))
        assertEquals(1, volumes("other").single().total)
    }

    @Test fun `the snooze filter is the list's own, not a second rendering of it`() {
        assertEquals(
            "the per-sender clause must hide exactly the rows the list hides — one predicate, " +
                "written once. A second rendering drifts, and the drift is invisible: both " +
                "queries keep returning rows.",
            notSnoozedSql("emails"),
            NOT_SNOOZED_EMAILS_SQL,
        )
    }

    // -- what the numbers say -------------------------------------------------------------------

    @Test fun `unread counts the unread messages and nothing else`() {
        insert("m1", from = "a@example.com", seen = 0)
        insert("m2", from = "a@example.com", seen = 0)
        insert("m3", from = "a@example.com", seen = 1)
        val row = volumes().single()
        assertEquals(3, row.total)
        assertEquals(2, row.unread)
    }

    @Test fun `the name shown is the one on the most recent message`() {
        // A bare column beside MAX(sortKey) comes from the row that won the MAX. Deliberate, and
        // pinned here: the address is what groups, the name is only what they last called
        // themselves.
        insert("old", from = "a@example.com", name = "Old Name", sortKey = 100)
        insert("new", from = "a@example.com", name = "New Name", sortKey = 900)
        insert("mid", from = "a@example.com", name = "Mid Name", sortKey = 500)
        val row = volumes().single()
        assertEquals("New Name", row.name)
        assertEquals(900L, row.latest)
    }

    @Test fun `lines come biggest first`() {
        insert("s1", from = "small@example.com")
        repeat(3) { insert("b$it", from = "big@example.com") }
        repeat(2) { insert("m$it", from = "mid@example.com") }
        assertEquals(
            listOf("big@example.com", "mid@example.com", "small@example.com"),
            volumes().map { it.email },
        )
    }

    // -- the honesty invariant --------------------------------------------------------------------

    @Test fun `the ids handed to the delete are exactly the ones counted`() {
        // Several senders, several folders, one excluded folder and a sibling account in the
        // way: for every line on screen, the delete acts on exactly as many messages as the line
        // announced — no more (a folder it should not touch) and no fewer (a case it missed).
        insert("a1", from = "A@example.com", mailbox = "inbox")
        insert("a2", from = "a@EXAMPLE.com", mailbox = "archive")
        insert("a3", from = "a@example.com", mailbox = "sent") // not counted, not deleted
        insert("b1", from = "b@example.com", mailbox = "untyped")
        insert("b2", from = "b@example.com", mailbox = "inbox")
        insert("b3", from = "b@example.com", mailbox = "inbox")
        insert("c1", from = "c@example.com", mailbox = "inbox")
        insert("x1", from = "a@example.com", accountId = "other")

        val rows = volumes()
        assertEquals(3, rows.size)
        rows.forEach { row ->
            assertEquals(
                "the line for ${row.email} announces ${row.total}",
                row.total,
                ids(row.email).size,
            )
        }
        assertEquals(setOf("a1", "a2"), ids("a@example.com").toSet())
        assertEquals(setOf("b1", "b2", "b3"), ids("b@example.com").toSet())
        // Reached with any spelling that fed the line, since the line is grouped case-blind.
        assertEquals(ids("a@example.com").toSet(), ids("A@EXAMPLE.COM").toSet())
    }

    @Test fun `both shipped statements carry the same scope clause`() {
        // Read from the annotations, constant resolved: the two statements must contain the
        // shared clause TEXT, not two clauses that happen to agree today.
        val counting = DaoQuerySource.emailDaoQuery("senderVolumes")
        val deleting = DaoQuerySource.emailDaoQuery("senderMessageIds")
        assertTrue(
            "the counting statement no longer contains the shared scope clause:\n$counting",
            SENDER_VOLUME_SCOPE_SQL in counting,
        )
        assertTrue(
            "the id statement no longer contains the shared scope clause:\n$deleting",
            SENDER_VOLUME_SCOPE_SQL in deleting,
        )
        // And the annotations are still those constants, not inlined copies of them — otherwise
        // the constants could be edited (and this file's SQLite runs with them) while the app
        // executes something else.
        assertEquals(SENDER_VOLUMES_SQL, counting)
        assertEquals(SENDER_MESSAGE_IDS_SQL, deleting)
    }
}
