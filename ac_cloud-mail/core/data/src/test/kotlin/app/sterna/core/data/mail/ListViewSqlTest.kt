package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** The list view's grouping, ranking and filters, run through the shipped SQL on in-memory SQLite. */
class ListViewSqlTest {
    private lateinit var db: Connection
    private val scope = listOf("acc" to "inbox")

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER, authClass INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT, accountId TEXT, until INTEGER, PRIMARY KEY(accountId, emailId))")
        }
        // authClass: 1 Ga Code, 2 Gb Link to auth, 0 Gc No Auth, null not classified
        //        id   thread subject  from    seen flag att auth key
        insert("m1", "T1", "Alpha", "zed@x.io", 1, 0, 0, 0, 100)
        insert("m2", "T1", "Alpha", "amy@x.io", 0, 1, 0, 2, 200)
        insert("m3", null, "Bravo", "amy@x.io", 1, 1, 1, 1, 300)
        insert("m4", null, "Charlie", "bob@x.io", 0, 0, 1, 1, 400)
        insert("m5", null, "Delta", "Amy@X.io", 1, 0, 0, null, 50)
    }

    @After fun tearDown() = db.close()

    private fun insert(id: String, thread: String?, subject: String, from: String, seen: Int, flagged: Int, att: Int, auth: Int?, key: Long) {
        db.prepareStatement("INSERT INTO emails VALUES(?, 'acc', 'inbox', ?, ?, 'p', '', 'N', ?, ?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, id); ps.setString(2, thread); ps.setString(3, subject); ps.setString(4, from)
            ps.setInt(5, seen); ps.setInt(6, flagged); ps.setInt(7, att); ps.setLong(8, key); if (auth == null) ps.setNull(9, java.sql.Types.INTEGER) else ps.setInt(9, auth)
            ps.executeUpdate()
        }
    }

    private fun flat(sort: SortOrder = SortOrder.DATE_DESC, unread: Boolean = false, shape: ListShape = ListShape.NONE): List<String> =
        db.prepareStatement(pagingSql(scope.size, sort, unread, shape)).use { ps ->
            scope.flatMap { listOf(it.first, it.second) }.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    private fun grouped(sort: SortOrder = SortOrder.DATE_DESC, unread: Boolean = false, shape: ListShape = ListShape.NONE): List<Pair<String, Int>> =
        db.prepareStatement(conversationSql(scope.size, sort, unread, 0, shape)).use { ps ->
            (1..3).flatMap { scope.flatMap { listOf(it.first, it.second) } }.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id") to rs.getInt("threadCount")) } }
        }

    // -- grouping ----------------------------------------------------------------------------

    @Test fun `no grouping lists every message`() {
        assertEquals(listOf("m4", "m3", "m2", "m1", "m5"), flat())
    }

    @Test fun `group by subject is the conversation view`() {
        assertEquals(listOf("m4" to 1, "m3" to 1, "m2" to 2, "m5" to 1), grouped())
    }

    @Test fun `group by sender collapses one address to its newest message, case-insensitively`() {
        val rows = grouped(shape = ListShape(bySender = true))
        assertEquals(listOf("m4" to 1, "m3" to 3, "m1" to 1), rows)
    }

    // -- ranking -----------------------------------------------------------------------------

    @Test fun `rank newest first, by sender, by subject`() {
        assertEquals(listOf("m4", "m3", "m2", "m1", "m5"), flat(SortOrder.DATE_DESC))
        assertEquals("m4", flat(SortOrder.SENDER).last())
        assertEquals(listOf("m1", "m2"), flat(SortOrder.SUBJECT).take(2))
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), flat(SortOrder.SUBJECT))
    }

    // -- filters, alone and combined ---------------------------------------------------------

    @Test fun `only starred`() = assertEquals(listOf("m3", "m2"), flat(shape = ListShape(starred = true)))
    @Test fun `only unread`() = assertEquals(listOf("m4", "m2"), flat(unread = true))
    @Test fun `only with attachments`() = assertEquals(listOf("m4", "m3"), flat(shape = ListShape(attachments = true)))
    @Test fun `auth with codes, auth links and any auth use the index`() {
        assertEquals(listOf("m4", "m3"), flat(shape = ListShape(auth = AuthFilter.CODES)))
        assertEquals(listOf("m2"), flat(shape = ListShape(auth = AuthFilter.LINKS)))
        assertEquals(listOf("m4", "m3", "m2"), flat(shape = ListShape(auth = AuthFilter.ANY)))
        assertEquals("not-yet-classified rows are in no auth class", false, "m5" in flat(shape = ListShape(auth = AuthFilter.ANY)))
    }

    @Test fun `filters combine`() {
        assertEquals(listOf("m3"), flat(shape = ListShape(starred = true, attachments = true)))
        assertEquals(listOf("m4"), flat(unread = true, shape = ListShape(attachments = true, auth = AuthFilter.CODES)))
        assertEquals(listOf("m3"), flat(shape = ListShape(starred = true, attachments = true, auth = AuthFilter.CODES)))
        assertEquals(emptyList<String>(), flat(unread = true, shape = ListShape(starred = true, auth = AuthFilter.CODES)))
    }

    @Test fun `the filters apply to the grouped list too`() {
        assertEquals(listOf("m3" to 1), grouped(shape = ListShape(starred = true, auth = AuthFilter.CODES)))
        assertEquals(listOf("m4" to 1), grouped(unread = true, shape = ListShape(auth = AuthFilter.CODES)))
    }

    @Test fun `select all takes exactly the filtered rows`() {
        val shape = ListShape(starred = true)
        db.prepareStatement(selectionIdsSql(scope.size, false, shape)).use { ps ->
            ps.setString(1, "acc"); ps.setString(2, "inbox")
            val ids = ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
            assertEquals(flat(shape = shape).sorted(), ids.sorted())
        }
    }

    // -- the index ---------------------------------------------------------------------------

    @Test fun `a row with no preview is not classified yet`() {
        assertNull(AuthIndex.indexed("Subject", null))
        assertEquals(0, AuthIndex.indexed("Lunch?", "see you at noon"))
        assertEquals(1, AuthIndex.indexed("Your code", "Your code is 482913."))
    }

    @Test fun `the classifier uses the reading pane's extractor and no second one`() {
        val src = java.io.File("src/main/kotlin/app/sterna/core/data/text/AuthClassifier.kt").readText()
        assertTrue(src.contains("extractVerificationCode(subject = subject, bodyText = bodyText, html = html)"))
        val app = java.io.File("../../app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        if (app.isFile) assertTrue(app.readText().contains("return extractVerificationCode("))
    }
}
