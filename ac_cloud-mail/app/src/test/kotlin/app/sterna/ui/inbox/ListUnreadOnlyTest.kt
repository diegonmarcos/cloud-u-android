package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHETHER THE LIST IS FILTERED TO UNREAD — the one answer the pager and "Select all" both ask.
 */
class ListUnreadOnlyTest {

    @Test
    fun `the unread scope filters whether or not the funnel is on`() {
        assertTrue(
            "a view called \"Unread\" showing read mail is a lie on the screen (WYSIWYG), and — worse " +
                "— a \"Select all\" that reaches it",
            listUnreadOnly(Sel.Unread, toggle = false),
        )
        assertTrue(listUnreadOnly(Sel.Unread, toggle = true))
    }

    @Test
    fun `a folder follows the funnel and nothing else`() {
        assertFalse(
            "the funnel off in a plain folder must show the whole folder — forcing it on anywhere " +
                "else is the mirror mistake, and it hides mail",
            listUnreadOnly(Sel.Folder("inbox-a"), toggle = false),
        )
        assertTrue(listUnreadOnly(Sel.Folder("inbox-a"), toggle = true))
    }

    @Test
    fun `the unified inbox follows the funnel and nothing else`() {
        assertFalse(listUnreadOnly(Sel.Unified, toggle = false))
        assertTrue(listUnreadOnly(Sel.Unified, toggle = true))
    }

    @Test
    fun `a null folder id does not make a folder into the unread scope`() {
        // Sel.Folder(null) is the state before the inbox id is known. It is a folder, not the
        // unread scope, and a comparison written on the wrong thing would force the filter there.
        assertFalse(listUnreadOnly(Sel.Folder(null), toggle = false))
    }

    @Test
    fun `all four combinations, written out`() {
        assertEquals(
            listOf(
                Sel.Folder("i") to false, Sel.Folder("i") to true,
                Sel.Unified to false, Sel.Unified to true,
                Sel.Unread to false, Sel.Unread to true,
            ).map { (sel, toggle) -> listUnreadOnly(sel, toggle) },
            listOf(false, true, false, true, true, true),
        )
    }
}
