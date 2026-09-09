package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT a tap on a list row does.
 */
class RowTapActionTest {

    // -- the defect: a draft whose conversation can unfold ---------------------------------------

    @Test
    fun `a draft in Drafts edits even when its conversation can unfold`() {
        assertEquals(
            RowTap.EDIT_DRAFT,
            rowTapAction(localDraft = false, selectionActive = false, expandable = true, fromSearch = false, rowRole = "drafts"),
        )
    }

    @Test
    fun `a flat draft in Drafts edits`() {
        assertEquals(
            RowTap.EDIT_DRAFT,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = false, rowRole = "drafts"),
        )
    }

    // -- everywhere else the tap opens the reader -------------------------------------------------

    @Test
    fun `a foldable conversation in the Inbox opens`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = true, fromSearch = false, rowRole = "inbox"),
        )
    }

    @Test
    fun `a flat row in the Inbox opens`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = false, rowRole = "inbox"),
        )
    }

    @Test
    fun `a search hit filed in Drafts opens, its folder is frozen at crawl time`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = true, rowRole = "drafts"),
        )
    }

    @Test
    fun `a row of unknown role opens`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = false, rowRole = null),
        )
    }

    // -- a live selection toggles, the whole conversation when it can unfold ----------------------

    @Test
    fun `during a selection a foldable draft toggles its whole conversation`() {
        assertEquals(
            RowTap.SELECT_THREAD,
            rowTapAction(localDraft = false, selectionActive = true, expandable = true, fromSearch = false, rowRole = "drafts"),
        )
    }

    @Test
    fun `during a selection a flat draft toggles itself`() {
        assertEquals(
            RowTap.SELECT_ROW,
            rowTapAction(localDraft = false, selectionActive = true, expandable = false, fromSearch = false, rowRole = "drafts"),
        )
    }

    // -- a draft only this phone holds wins before the selection (#95) ----------------------------

    @Test
    fun `a local draft edits even during a selection, and outside Drafts`() {
        assertEquals(
            RowTap.EDIT_DRAFT,
            rowTapAction(localDraft = true, selectionActive = true, expandable = false, fromSearch = false, rowRole = "inbox"),
        )
    }

    // -- a child of an unfolded conversation, judged by ITS OWN folder ----------------------------

    @Test
    fun `a child filed in Drafts edits`() {
        assertEquals(RowTap.EDIT_DRAFT, childTapAction("drafts"))
    }

    @Test
    fun `a child filed in Sent opens`() {
        assertEquals(RowTap.OPEN, childTapAction("sent"))
    }

    @Test
    fun `a child of unknown role opens`() {
        assertEquals(RowTap.OPEN, childTapAction(null))
    }
}
