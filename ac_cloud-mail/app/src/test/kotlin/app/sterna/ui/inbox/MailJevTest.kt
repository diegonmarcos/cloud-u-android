package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #883 mail_move_target: THE FALLBACK TEST. No suggestion (no engine, no consent - mail needs the person's grant -, a
 * closed evidence gate, unsure, a folder the picker does not offer) leaves the move picker in its own order, the very
 * same list. A suggestion only moves ONE row to the front. The model sees headers, never the body.
 */
class MailJevTest {

    private val rows = listOf("inbox", "archive", "work", "trash")

    @Test fun noSuggestionIsTheSameListInstance() {
        assertSame(rows, MailJev.promote(rows, null) { it })
        assertSame(rows, MailJev.promote(rows, "unknown") { it })
        assertSame("already first", rows, MailJev.promote(rows, "inbox") { it })
    }

    @Test fun aSuggestionMovesOneRowToTheFrontAndKeepsTheRest() {
        assertEquals(listOf("work", "inbox", "archive", "trash"), MailJev.promote(rows, "work") { it })
        assertEquals(rows.sorted(), MailJev.promote(rows, "trash") { it }.sorted())
    }

    @Test fun promoteInPlaceTouchesTheListOnlyForARealSuggestion() {
        val a = rows.toMutableList()
        MailJev.promoteInPlace(a, null) { it }
        MailJev.promoteInPlace(a, "unknown") { it }
        assertEquals(rows, a)
        MailJev.promoteInPlace(a, "trash") { it }
        assertEquals(listOf("trash", "inbox", "archive", "work"), a)
    }

    @Test fun onlyAnOfferedFolderIsAccepted() {
        for (pick in listOf(null, "", "spam")) assertNull("pick=$pick", MailJev.valid(pick, rows))
        assertEquals("work", MailJev.valid("work", rows))
    }

    @Test fun theStateIsHeadersOnly() {
        val s = MailJev.state("a@b.example", "Hello").toString()
        assertEquals(setOf("from", "subject"), org.json.JSONObject(s).keys().asSequence().toSet())
        assertFalse(s.contains("body") || s.contains("preview"))
        assertEquals(200, MailJev.state("x".repeat(500), "").getString("from").length)
    }

    @Test fun theQuestionOffersExactlyThePickersFolders() {
        val crit = MailJev.questions(listOf("a" to "Inbox", "b" to "Work")).getJSONObject("folder").getJSONObject("criteria")
        assertEquals(setOf("a", "b"), crit.keys().asSequence().toSet())
        assertEquals("Work", crit.getString("b"))
    }
}
