package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.jmap.model.Email
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * The agreement between the two ends of a notification pass: what the read REMEMBERS has to cover
 */
class NotifierCandidateWindowTest {
    private lateinit var db: Connection

    private companion object {
        const val MINUTE = 60L * 1000
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        /** A fixed "now" — a clock-derived one would make the ages of the bench rows drift. */
        const val NOW = 1_800_000_000_000L
        /** More rows than the cap, inside the age floor: what makes the cap observable at all. */
        const val BURST = 600
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

    /** One cached row, aged [ageMs] under [NOW], mapped by the shipped mapper. */
    private fun row(id: String, ageMs: Long): EmailEntity =
        Email(id = id, receivedAt = Instant.ofEpochMilli(NOW - ageMs).toString()).toEntity("acc", "inbox")

    /** A row the server dated in a way nothing can parse — the notifier is lenient about these. */
    private fun undatedRow(id: String): EmailEntity =
        Email(id = id, receivedAt = null).toEntity("acc", "inbox")

    private fun insert(e: EmailEntity) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'subj', 'prev', ?, 'N', 'n@e', 0, 0, 0, ?, '[]')",
        ).use { ps ->
            ps.setString(1, e.id); ps.setString(2, e.accountId); ps.setString(3, e.mailboxId)
            ps.setString(4, e.receivedAt); ps.setLong(5, e.sortKey)
            ps.executeUpdate()
        }
    }

    private fun exec(function: String, args: List<Pair<String, Any>>): List<String> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery(function))
        check(order == args.map { it.first }) {
            "EmailDao.$function binds $order — this test offers ${args.map { it.first }}"
        }
        return db.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, (_, v) ->
                when (v) {
                    is Int -> ps.setInt(i + 1, v)
                    is Long -> ps.setLong(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /** The shipped candidate read, executed at the shipped floor and cap. */
    private fun candidates(): List<String> = exec(
        "receivedSince",
        listOf(
            "accountId" to "acc", "mailboxId" to "inbox",
            "since" to notifyCandidateFloor(NOW), "limit" to NOTIFY_CANDIDATE_MAX,
        ),
    )

    /** The shipped baseline read, executed — the same floor, no cap. */
    private fun baseline(): List<String> = exec(
        "idsReceivedSince",
        listOf("accountId" to "acc", "mailboxId" to "inbox", "since" to notifyCandidateFloor(NOW)),
    )

    /** The spread of ages a real folder holds, the undated case, and a burst over the cap. */
    private fun fillFolder(): List<EmailEntity> {
        val spread = listOf(
            row("m-1min", MINUTE),
            row("m-1h", HOUR),
            row("m-23h", 23 * HOUR),
            row("m-25h", 25 * HOUR),
            row("m-3d", 3 * DAY),
            row("m-13d", 13 * DAY),
            row("m-16d", 16 * DAY),
            row("m-29d", 29 * DAY),
            row("m-31d", 31 * DAY),
            row("m-200d", 200 * DAY),
            undatedRow("m-undated"),
        )
        // …plus enough recent mail to push the spread past the cap, so every assertion below is
        // made on a folder where the cap is actually biting.
        val burst = (1..BURST).map { row("burst%04d".format(it), ageMs = 2 * MINUTE + it * MINUTE) }
        (spread + burst).forEach(::insert)
        return spread + burst
    }

    // -- the invariant --------------------------------------------------------------------------

    @Test fun `nothing the notifier could still announce is left out of the baseline`() {
        val rows = fillFolder()
        val remembered = baseline().toSet()
        // Several plausible previous passes: minutes ago (live push), and a device whose push has
        // been dead for a fortnight — the age floor is measured from THAT pass, not from now.
        listOf(0L, HOUR, 3 * DAY, 14 * DAY).forEach { sincePass ->
            val floor = notifyFloor(NOW - sincePass)
            rows.filter { announceableAt(it.receivedAt, floor) }.forEach {
                assertTrue(
                    "with the previous pass ${sincePass / DAY}d/${sincePass / HOUR}h ago, the " +
                        "notifier could still announce '${it.id}' — so the baseline this pass " +
                        "writes has to remember it, or the next pass announces it as new mail",
                    it.id in remembered,
                )
            }
        }
    }

    @Test fun `the cap sheds candidates and never the baseline`() {
        // Literal numbers, not the constant: an expectation computed from NOTIFY_CANDIDATE_MAX
        // holds for every value of it, including a mutated one, and proves nothing about the cap.
        val rows = fillFolder()
        assertEquals("the bench must exceed the cap or it observes nothing", 611, rows.size)
        assertEquals(500, candidates().size)
        // Everything inside the window is remembered: the spread minus the two rows past 30 days,
        // plus the whole burst.
        assertEquals(609, baseline().size)
        assertTrue(candidates().all { it in baseline().toSet() })
    }

    @Test fun `an old message the baseline leaves out cannot come back as new mail`() {
        // The baseline is REPLACED each pass, so a row it drops is forgotten. It may only be
        // dropped once the age floor refuses it as well — otherwise the next pass announces mail
        // from months ago, and unarchives its thread on the server.
        val rows = fillFolder()
        val remembered = baseline().toSet()
        val floor = notifyFloor(NOW)
        rows.filter { it.id !in remembered }.forEach {
            assertFalse(
                "'${it.id}' is not remembered, so it leaves the baseline — and it is still past " +
                    "the notifier's age floor, so the next pass announces it as new mail",
                announceableAt(it.receivedAt, floor),
            )
        }
        assertEquals(
            "the bench holds nothing old enough to be dropped",
            setOf("m-31d", "m-200d"),
            rows.map { it.id }.filterNot { it in remembered }.toSet(),
        )
    }

    @Test fun `a message with no usable date survives both reads and the floor`() {
        // The notifier never suppresses an arrival it cannot date; both reads have to keep those
        // rows (`sortKey = 0`) or that promise is quietly broken one layer down.
        fillFolder()
        assertTrue("an undated row is dropped by the baseline read", "m-undated" in baseline())
        assertTrue(announceableAt(null, notifyFloor(NOW)))
        assertTrue(announceableAt("not a date", notifyFloor(NOW)))
    }

    // -- the two floors -------------------------------------------------------------------------

    @Test fun `a fortnight of dead push still announces`() {
        // An absolute promise, not a relation between two constants that would hold at any size:
        // push dies, the worker is throttled, the user opens the app two weeks later. The floor is
        // measured from the previous pass, so the read has to reach a fortnight plus the horizon.
        val floor = notifyFloor(NOW - 14 * DAY)
        assertTrue(
            "the read reaches back ${(NOW - notifyCandidateFloor(NOW)) / DAY}d, but a pass 14d " +
                "late can still announce mail from ${(NOW - floor) / DAY}d ago",
            notifyCandidateFloor(NOW) <= floor,
        )
    }

    @Test fun `the read always reaches at least the horizon of a pass that just ran`() {
        assertTrue(
            "the read is narrower than the notifier's own age floor: mail that arrived since the " +
                "last pass is not even read, so it is never announced",
            notifyCandidateFloor(NOW) <= notifyFloor(NOW),
        )
    }

    @Test fun `the read's floor only ever moves forward`() {
        // Why the baseline's bound is an instant and not "the newest N rows": a row count slides
        // DOWN as rows above it are deleted, so a message can leave the read set and come back
        // into it — and a message that comes back is no longer in the baseline, which is a false
        // notification and a server-side unarchive. An instant cannot do that.
        assertTrue(notifyCandidateFloor(NOW) < notifyCandidateFloor(NOW + MINUTE))
        assertEquals(NOW - NOTIFY_CANDIDATE_WINDOW_MS, notifyCandidateFloor(NOW))
    }

    @Test fun `a folder that has never had a pass has no floor at all`() {
        // A first diff must not silently drop mail because it cannot date the previous pass.
        assertEquals(Long.MIN_VALUE, notifyFloor(0))
        assertTrue(announceableAt(Instant.ofEpochMilli(0).toString(), notifyFloor(0)))
    }

    @Test fun `the floor is the last pass minus the shared horizon`() {
        assertEquals(NOW - NOTIFY_HORIZON_MS, notifyFloor(NOW))
        assertTrue(announceableAt(Instant.ofEpochMilli(NOW - NOTIFY_HORIZON_MS).toString(), notifyFloor(NOW)))
        assertFalse(announceableAt(Instant.ofEpochMilli(NOW - NOTIFY_HORIZON_MS - 1).toString(), notifyFloor(NOW)))
    }
}
