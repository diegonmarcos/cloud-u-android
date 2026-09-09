package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The move-to-folder picker's filter field (#182), EXECUTED — never re-derived here: every case
 * below spells out the expected rows literally, so inverting the shipped rule turns one red.
 */
class FolderFilterTest {

    private fun row(id: String, name: String, path: String? = null) =
        FolderPickerRow(Mailbox(id = id, name = name), name, path)

    private fun ids(rows: List<FolderPickerRow>) = rows.map { it.folder.id }

    @Test fun `case is ignored, both ways`() {
        val rows = listOf(
            row("1", "Archive"),
            row("2", "Invoices"),
            row("3", "Archives-2024"),
            row("4", "Archives-2025"),
        )
        assertEquals(
            "a lowercase query must match names spelled with a capital",
            listOf("Archive", "Archives-2024", "Archives-2025"),
            filterFolderRows(rows, "arc").map { it.name },
        )
        assertEquals(
            "an uppercase query must answer exactly the same rows",
            listOf("Archive", "Archives-2024", "Archives-2025"),
            filterFolderRows(rows, "ARC").map { it.name },
        )
    }

    @Test fun `the PAINTED path counts, and a null path does not blow up`() {
        val rows = listOf(
            row("a", "Done", "ProjectA"),
            row("b", "Done", "ProjectB"),
            row("root", "Done"),
        )
        assertEquals(
            "a row must be reachable by the parent path shown under its name (#109)",
            listOf("b"),
            ids(filterFolderRows(rows, "ProjectB")),
        )
        assertEquals(
            "a row with no path line must simply not match on it",
            listOf("a", "b", "root"),
            ids(filterFolderRows(rows, "Done")),
        )
    }

    @Test fun `the input order is kept, matches scattered and out of alphabetical order`() {
        // "Alpha" matches on its PATH only and sits BEFORE "Zeta-work", which matches on its
        // NAME — the weaker match first, on purpose. Any "best match first" scorer pulls "z"
        // to the front; a plain filter cannot. (A stable sort with the name match already
        // leading is a mutation that survives, which is how this list came to be in this order.)
        val rows = listOf(
            row("m", "Middle", "Personal"),
            row("a", "Alpha", "work-2024"),
            row("z", "Zeta-work"),
            row("n", "Nothing", "Personal"),
            row("b", "Beta", "Work-archive"),
        )
        assertEquals(
            "the output must be the input SUBSEQUENCE — no sort, no relevance score (#182)",
            listOf("a", "z", "b"),
            ids(filterFolderRows(rows, "work")),
        )
    }

    @Test fun `an empty query returns the whole list, unchanged`() {
        val rows = listOf(row("1", "Beta"), row("2", "Alpha", "Work"), row("3", "Gamma"))
        assertEquals(
            "an empty field must offer every folder, in the order the picker built them",
            rows,
            filterFolderRows(rows, ""),
        )
    }

    @Test fun `a query nothing matches returns an empty list`() {
        val rows = listOf(row("1", "Archive"), row("2", "Trash", "INBOX"))
        assertTrue(
            "no match must come out empty — that is what draws 'No folder matches'",
            filterFolderRows(rows, "zzz").isEmpty(),
        )
    }

    /**
     * What this pins is that the rows come out CARRYING their folder and their painted path —
     */
    @Test fun `a surviving row still carries its own mailbox and its own path`() {
        val rows = listOf(
            row("id-projectA-done", "Done", "ProjectA"),
            row("id-other", "Invoices", "ProjectA"),
            row("id-projectB-done", "Done", "ProjectB"),
        )
        val shown = filterFolderRows(rows, "Done")
        assertEquals(
            "the rows that survive must be the ones handed in, whole",
            listOf("id-projectA-done", "id-projectB-done"),
            ids(shown),
        )
        assertEquals("a survivor must keep the path line it was painted with", "ProjectB", shown[1].path)
    }

    @Test fun `a typed space filters, it does not reset the list`() {
        // The rule is `isEmpty`, not `isBlank`, and it is a decision: the field shows a space, so
        // a space narrows. Swapped for isBlank this returns all three.
        val rows = listOf(row("1", "Old Mail"), row("2", "Archive"), row("3", "Work", "Old Files"))
        assertEquals(
            "a query of one space must filter on that space, like any other text typed",
            listOf("1", "3"),
            ids(filterFolderRows(rows, " ")),
        )
    }

    @Test fun `the Turkish locale does not change the answer`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr"))
            val rows = listOf(row("1", "Inbox"), row("2", "Sent"))
            assertEquals(
                "a locale-sensitive case fold ('I'.lowercase() is 'ı' in Turkish) must not be " +
                    "able to hide a folder from its own name",
                listOf("1"),
                ids(filterFolderRows(rows, "inbox")),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `an elided ancestor is NOT reachable, and that is the decision`() {
        // mailboxPathLabel drops outer ancestors and marks the cut with "…". What the row does
        // not show, the filter does not match: a row must never surface for a reason nothing on
        // screen explains (WYSIWYG, #182).
        val rows = listOf(row("1", "Factures", "…Nord / Factures"))
        assertTrue(
            "matching a dropped ancestor would surface a row for text no one can see",
            filterFolderRows(rows, "Clients").isEmpty(),
        )
        assertEquals(
            "what the row DOES show still matches",
            listOf("1"),
            ids(filterFolderRows(rows, "nord")),
        )
    }
}
