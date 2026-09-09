package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A batch IMAP move must credit — and un-index — only the messages the session can PROVE left the
 */
class MoveCreditsOnlyWhatLeftTheSourceTest {

    // ---- the decision, executed ---------------------------------------------------------------

    private val batch = linkedMapOf(11L to "acc|INBOX|11", 12L to "acc|INBOX|12", 13L to "acc|INBOX|13")

    /**
     * The case the defect is made of. An empty `confirmedGone` is "the server proved nothing",
     */
    @Test fun `a move that proved nothing credits nothing`() {
        val outcome = imapMoveOutcome(batch, emptySet())

        assertEquals(
            "an OK with no proof credited the whole batch: those ids get un-indexed, and nothing " +
                "on IMAP ever re-indexes a message the cache no longer holds",
            emptyList<String>(), outcome.gone,
        )
        assertEquals(
            "the unproven ids must FAIL — that is what makes the caller re-query, so a message " +
                "that never moved comes back instead of vanishing from a list it never left",
            listOf("acc|INBOX|11", "acc|INBOX|12", "acc|INBOX|13"), outcome.unproven,
        )
    }

    /** The ordinary case: the server named every source uid it emptied. */
    @Test fun `a move that proved every uid credits every id and fails none`() {
        val outcome = imapMoveOutcome(batch, setOf(11L, 12L, 13L))

        assertEquals(listOf("acc|INBOX|11", "acc|INBOX|12", "acc|INBOX|13"), outcome.gone)
        assertEquals(emptyList<String>(), outcome.unproven)
    }

    /**
     * The mixed batch — one stale uid among live ones, which is how the defect actually reaches a
     */
    @Test fun `a partly proved move splits exactly, keeping arrival order`() {
        val wide = linkedMapOf(
            9L to "id-a", 4L to "id-b", 7L to "id-c", 2L to "id-d", 5L to "id-e",
        )

        val outcome = imapMoveOutcome(wide, setOf(2L, 9L, 7L))

        assertEquals(listOf("id-a", "id-c", "id-d"), outcome.gone)
        assertEquals(listOf("id-b", "id-e"), outcome.unproven)
    }

    /**
     * A proved uid this batch never handed in invents no id. One gesture can be cut into several
     */
    @Test fun `a proof about a uid this batch never sent invents no id`() {
        val outcome = imapMoveOutcome(mapOf(12L to "acc|INBOX|12"), setOf(11L, 12L, 99L))

        assertEquals(listOf("acc|INBOX|12"), outcome.gone)
        assertEquals(emptyList<String>(), outcome.unproven)
    }

    /** Nothing handed in, nothing answered — no uid of the proof leaks into either list. */
    @Test fun `an empty batch answers empty on both sides`() {
        val outcome = imapMoveOutcome(emptyMap(), setOf(11L, 12L))

        assertEquals(emptyList<String>(), outcome.gone)
        assertEquals(emptyList<String>(), outcome.unproven)
    }

    // ---- the wiring: which ids each half of the success branch is HANDED ----------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

    /**
     * THE LINE THE WHOLE FIX IS: the un-indexing takes the PROVED ids and nothing else.
     */
    @Test fun `the batch move un-indexes only the ids the server proved gone`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must un-index the PROVED ids only. Handing it the whole batch " +
                "un-indexes messages a no-op `UID MOVE` never touched, and nothing on IMAP ever " +
                "re-indexes a message the cache no longer holds. Body was:\n$body",
            listOf("deleteFromCacheAndIndex(credentials.id, outcome.gone)"),
            codeLinesNaming(body, "deleteFromCacheAndIndex("),
        )
        assertEquals(
            "and the drawer-count nudge must count the same ids — the filter is what keeps the " +
                "two in step (INV-COUNT). Body was:\n$body",
            listOf("adjustCountsForRemoval(rows.filter { it.id in outcome.gone }, dest)"),
            codeLinesNaming(body, "adjustCountsForRemoval("),
        )
        assertEquals(
            "no id of a batch move may be evicted as a no-op: an unproven id is UNKNOWN, not " +
                "\"it stayed\", and its rows must be left exactly as they are. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "evictAlreadyThere("),
        )
    }

    /**
     * The split itself, and where it is READ FROM: `ImapMoved.confirmedGone`, the source-side
     */
    @Test fun `the batch move asks the pure split, and asks it what the move reported`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must take its split from imapMoveOutcome, over the uids the move " +
                "itself reported gone from the source. Body was:\n$body",
            listOf("val outcome = imapMoveOutcome(uidToId, landed.confirmedGone)"),
            codeLinesNaming(body, "imapMoveOutcome("),
        )
    }

    /**
     * And the ORDER: the verdict is computed BEFORE anything is dropped. Kotlin enforces it as
     */
    @Test fun `the verdict is reached before any row is dropped`() {
        val lines = codeLinesOf(bodyOf("imapMoveGroup"))
        val split = lines.indexOfFirst { "imapMoveOutcome(" in it }
        val dropped = lines.indexOfFirst { "deleteFromCacheAndIndex(" in it }
        assertTrue("imapMoveGroup no longer computes a split at all", split >= 0)
        assertTrue("imapMoveGroup no longer drops anything at all", dropped >= 0)
        assertTrue(
            "the rows are dropped before the move's verdict is known — that IS the defect. " +
                "Body was:\n${lines.joinToString("\n")}",
            split < dropped,
        )
    }

    /**
     * Every `failed +=` of the batch mover, as a closed list. Three legitimate ones, and each says
     */
    @Test fun `everything the batch move does not credit, it fails`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must fail exactly what it does not credit: unparseable ids, ids the " +
                "move proved nothing about, and the whole group when the move did not go out. " +
                "Body was:\n$body",
            listOf(
                "failed += ids.filter { ImapMailService.uidOf(it) == null }",
                "failed += outcome.unproven",
                ".onFailure { failed += uidToId.values }",
            ),
            codeLinesNaming(body, "failed +="),
        )
    }
}
