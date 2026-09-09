package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A destruction may only touch a line that is STILL THE LINE THAT WAS TICKED (Codeberg #99).
 */
class DestroyOnlyWhatWasTickedTest {

    /** A row, reduced to what the decision is allowed to look at: a name, and two stamps. */
    private data class Row(val id: String, val ticked: Long?, val now: Long?)

    private fun split(vararg rows: Row) =
        UidValidity.destroyableUnderTheNumberingItWasTickedUnder(
            rows = rows.toList(),
            tickedUnder = { it.ticked },
            readUnderNow = { it.now },
        )

    /**
     * THE DEFECT. The row was ticked while it belonged to numbering 42; it now belongs to 77, so
     * the envelope on screen is not the one that was ticked and the destroy may not have it.
     */
    @Test fun `a row whose numbering changed since the tick is not destroyed`() {
        val split = split(Row("imap:a:INBOX:7", ticked = 42L, now = 77L))
        assertEquals(
            "a line replaced since the tick must leave the destroy: the UID is the same text, the " +
                "message behind it is not, and expunging it destroys mail nobody selected",
            emptyList<String>(),
            split.kept.map { it.id },
        )
        assertEquals(listOf("imap:a:INBOX:7"), split.drifted.map { it.id })
    }

    /**
     * THE INVERSE WITNESS, and it is not optional: without it "refuse everything" satisfies the
     * test above and no IMAP permanent delete ever goes through again.
     */
    @Test fun `a row still under the numbering it was ticked under is destroyed`() {
        val split = split(Row("imap:a:INBOX:7", ticked = 42L, now = 42L))
        assertEquals(listOf("imap:a:INBOX:7"), split.kept.map { it.id })
        assertEquals(emptyList<String>(), split.drifted.map { it.id })
    }

    /**
     * G4, the expensive half. `uidValidity` is null on EVERY JMAP row, and a row evicted from
     */
    @Test fun `an absent stamp passes, on either side and on both`() {
        val split = split(
            Row("no-tick", ticked = null, now = 77L),
            Row("no-now", ticked = 42L, now = null),
            Row("neither", ticked = null, now = null),
        )
        assertEquals(
            "an absent stamp is not evidence of a swap — refusing on it invents one, and on JMAP " +
                "every stamp is absent",
            listOf("no-tick", "no-now", "neither"),
            split.kept.map { it.id },
        )
        assertEquals(emptyList<String>(), split.drifted.map { it.id })
    }

    /** A mixed wave, cut in the middle, both halves in arrival order — `drifted` goes back into the
     *  selection and back on the screen, so its order is what the user sees. */
    @Test fun `a mixed wave is cut correctly and both halves keep the input order`() {
        val split = split(
            Row("d1", ticked = 42L, now = 77L),
            Row("k1", ticked = 42L, now = 42L),
            Row("k2", ticked = null, now = 77L),
            Row("d2", ticked = 77L, now = 42L),
            Row("k3", ticked = 9L, now = null),
            Row("d3", ticked = 1L, now = 2L),
            Row("k4", ticked = 5L, now = 5L),
        )
        assertEquals(listOf("k1", "k2", "k3", "k4"), split.kept.map { it.id })
        assertEquals(listOf("d1", "d2", "d3"), split.drifted.map { it.id })
    }

    /** The direction of the change says nothing: a numbering that went DOWN is still a numbering
     *  the line did not have when it was ticked. */
    @Test fun `a numbering that went down is a drift too`() {
        val split = split(Row("down", ticked = 77L, now = 42L))
        assertEquals(emptyList<String>(), split.kept.map { it.id })
        assertEquals(listOf("down"), split.drifted.map { it.id })
    }

    /** Nothing in, nothing out — the ordinary shape of a delete with no destroy leg at all. */
    @Test fun `an empty wave splits into two empty halves`() {
        val split = split()
        assertEquals(emptyList<String>(), split.kept.map { it.id })
        assertEquals(emptyList<String>(), split.drifted.map { it.id })
    }
}
