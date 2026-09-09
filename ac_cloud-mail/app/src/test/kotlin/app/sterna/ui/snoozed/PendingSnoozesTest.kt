package app.sterna.ui.snoozed

import app.sterna.core.data.db.SnoozedListRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the "Snoozed" screen lists (Codeberg #82): pending deadlines only, soonest first. */
class PendingSnoozesTest {

    private fun row(id: String, until: Long) = SnoozedListRow(
        emailId = id,
        accountId = "acc",
        until = until,
        subject = "s",
        fromName = null,
        fromEmail = null,
    )

    @Test fun `keeps only deadlines still in the future`() {
        val now = 1_000L
        val kept = pendingSnoozes(listOf(row("past", 500), row("future", 1_500)), now)
        assertEquals(listOf("future"), kept.map { it.emailId })
    }

    @Test fun `a deadline exactly at now has lapsed`() {
        assertEquals(emptyList<String>(), pendingSnoozes(listOf(row("a", 1_000)), 1_000).map { it.emailId })
    }

    @Test fun `sorts soonest first`() {
        val rows = listOf(row("c", 3_000), row("a", 1_100), row("b", 2_000))
        assertEquals(listOf("a", "b", "c"), pendingSnoozes(rows, 1_000).map { it.emailId })
    }

    @Test fun `no snoozes gives an empty list`() {
        assertEquals(emptyList<SnoozedListRow>(), pendingSnoozes(emptyList(), 1_000))
    }
}
