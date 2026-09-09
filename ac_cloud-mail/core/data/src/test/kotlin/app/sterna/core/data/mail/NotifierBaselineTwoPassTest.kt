package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.jmap.model.Email
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * TWO PASSES, because one pass cannot see this failure.
 */
class NotifierBaselineTwoPassTest {
    private lateinit var db: Connection

    private companion object {
        const val MINUTE = 60L * 1000
        const val HOUR = 60 * MINUTE
        const val NOW = 1_800_000_000_000L

        /**
         * Rows in the burst, comfortably over the shipped cap of 500 — and inside the age floor,
         */
        const val BURST = 600

        /** Rows that leave the folder between the two passes (read, archived, deleted, swept). */
        const val LEAVING = 120
    }

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
                    recipientsJson TEXT,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private val receivedAtOf = mutableMapOf<String, String?>()

    private fun insert(e: EmailEntity) {
        receivedAtOf[e.id] = e.receivedAt
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'subj', 'prev', ?, 'N', 'n@e', 0, 0, 0, ?, '[]')",
        ).use { ps ->
            ps.setString(1, e.id); ps.setString(2, e.accountId); ps.setString(3, e.mailboxId)
            ps.setString(4, e.receivedAt); ps.setLong(5, e.sortKey)
            ps.executeUpdate()
        }
    }

    /** One cached row aged [ageMs] under [NOW], mapped by the shipped mapper (sortKey included). */
    private fun row(id: String, ageMs: Long): EmailEntity =
        Email(id = id, receivedAt = Instant.ofEpochMilli(NOW - ageMs).toString()).toEntity("acc", "inbox")

    private fun delete(id: String) =
        db.prepareStatement("DELETE FROM emails WHERE accountId = 'acc' AND id = ?").use {
            it.setString(1, id); it.executeUpdate()
        }

    /**
     * The DAO statement `MailRepository.notifyRead` assigns to [field], read out of the shipped
     */
    private fun statementFor(field: String): String {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "notifyRead")
        val line = body.lines().map { it.trim() }.firstOrNull { it.startsWith("$field = emailDao.") }
            ?: error("MailRepository.notifyRead no longer assigns '$field' from an emailDao call:\n$body")
        return Regex("""emailDao\.(\w+)\(""").find(line)?.groupValues?.get(1)
            ?: error("cannot read the statement behind '$field' in: $line")
    }

    /** Run a shipped DAO statement at [atMs], binding whatever named parameters it declares. */
    private fun exec(function: String, atMs: Long): List<String> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery(function))
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, "acc")
                    "mailboxId" -> ps.setString(i + 1, "inbox")
                    "since" -> ps.setLong(i + 1, notifyCandidateFloor(atMs))
                    "limit" -> ps.setInt(i + 1, NOTIFY_CANDIDATE_MAX)
                    else -> error("EmailDao.$function binds ':$name', which this bench cannot fill")
                }
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /** The hydrated rows one pass hands the diff, at [atMs] — capped. */
    private fun candidates(atMs: Long): List<String> = exec(statementFor("emails"), atMs)

    /** The ids one pass writes into the folder's baseline, at [atMs] — time floor only, no cap. */
    private fun baselineIds(atMs: Long): List<String> = exec(statementFor("baselineIds"), atMs)

    /** The shipped diff, minus the parts this file does not own (selfMoved, thread collapse). */
    private fun announced(candidates: List<String>, known: Set<String>, lastPassMs: Long): List<String> =
        candidates.filter { it !in known && announceableAt(receivedAtOf[it], notifyFloor(lastPassMs)) }

    // -- the two passes ---------------------------------------------------------------------------

    @Test fun `nothing that was already in the folder announces on a later pass`() {
        // A burst larger than the cap, all of it inside the age floor.
        val burst = (1..BURST).map { row("m%04d".format(it), ageMs = HOUR + it * MINUTE) }
        burst.forEach(::insert)

        // PASS 1 — the notifier is handed the candidates and writes the baseline. Every id here
        // existed before this pass; none of it may EVER announce afterwards.
        val seenAtPassOne = burst.map { it.id }.toSet()
        val known = baselineIds(NOW).toSet()

        // Between the passes the top of the folder empties: mail read on the laptop and archived,
        // a delete, the ghost sweep dropping what the server no longer has.
        candidates(NOW).take(LEAVING).forEach(::delete)

        // PASS 2 — a few minutes later. The previous pass is what the age floor is measured from.
        val later = NOW + 5 * MINUTE
        val announced = announced(candidates(later), known, lastPassMs = NOW)

        val resurrected = announced.filter { it in seenAtPassOne }
        assertTrue(
            "${resurrected.size} message(s) that were already in the folder at the previous pass " +
                "are announced as new mail — and the same diff drives unarchiveThreadsOnReply, so " +
                "each one also pulls a thread back out of the Archive, server-side. " +
                "First few: ${resurrected.take(5)}",
            resurrected.isEmpty(),
        )
    }

    @Test fun `the baseline covers every candidate the same pass could announce`() {
        // The property the two-pass test rests on, stated directly: whatever the cap sheds from the
        // hydrated candidates is still remembered. Break it and the test above starts failing on
        // whichever rows the folder happens to lose.
        (1..BURST).forEach { insert(row("m%04d".format(it), ageMs = HOUR + it * MINUTE)) }
        val candidates = candidates(NOW)
        val baseline = baselineIds(NOW).toSet()
        assertEquals("the bench must exceed the cap or it proves nothing about it", 500, candidates.size)
        assertEquals("the baseline must not be capped", BURST, baseline.size)
        assertTrue(
            "the baseline does not cover the candidates: ${candidates.filterNot { it in baseline }.take(5)}",
            candidates.all { it in baseline },
        )
    }

    @Test fun `an undated row is remembered as well as offered`() {
        // The read admits sortKey = 0 because the age floor is lenient about undated mail. The
        // baseline has to admit it too, or the leniency turns into a repeating notification.
        insert(row("dated", ageMs = HOUR))
        insert(Email(id = "undated", receivedAt = null).toEntity("acc", "inbox"))
        assertTrue("undated" in candidates(NOW))
        assertTrue("undated" in baselineIds(NOW))
    }

    @Test fun `a row past the time floor is in neither`() {
        insert(row("ancient", ageMs = 400L * 24 * HOUR))
        insert(row("recent", ageMs = HOUR))
        assertEquals(listOf("recent"), candidates(NOW))
        assertEquals(listOf("recent"), baselineIds(NOW))
    }
}
