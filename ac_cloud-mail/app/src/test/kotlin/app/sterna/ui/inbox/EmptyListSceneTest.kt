package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.ui.components.EmptyArt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an empty list SAYS, run rather than read: the picture, the two strings, and whether the way
 */
class EmptyListSceneTest {

    private fun scene(
        unreadView: Boolean = false,
        toggle: Boolean = false,
        unified: Boolean = false,
        selectedMailboxId: String? = "f1",
        role: String? = null,
    ) = emptyListScene(unreadView, toggle, unified, selectedMailboxId, role)

    // -- the four combinations of (scope, funnel) -------------------------------------------------

    @Test
    fun `neither the scope nor the funnel - the folder speaks for itself`() {
        assertEquals(
            EmptyScene(EmptyArt.INBOX_ZERO, R.string.empty_inbox_title, R.string.empty_inbox_body, false),
            scene(unreadView = false, toggle = false, selectedMailboxId = "in", role = "inbox"),
        )
    }

    @Test
    fun `the funnel alone - unread words, the folder's own picture, and a way out`() {
        assertEquals(
            EmptyScene(EmptyArt.INBOX_ZERO, R.string.empty_unread_title, R.string.empty_unread_body, true),
            scene(unreadView = false, toggle = true, selectedMailboxId = "in", role = "inbox"),
        )
    }

    @Test
    fun `the scope alone - its own words, and no button, because there is no filter to lift`() {
        assertEquals(
            "In the unread view the filter is the view. The button offers to remove it, would " +
                "remove nothing, and the same empty list would stay on screen — so the body must " +
                "not send the reader looking for it either.",
            EmptyScene(EmptyArt.FOLDER, R.string.empty_unread_title, R.string.empty_unread_view_body, false),
            scene(unreadView = true, toggle = false, selectedMailboxId = null),
        )
    }

    @Test
    fun `the scope with the funnel also on - still its own words, still no button`() {
        assertEquals(
            "The funnel may well be on underneath: turning it off leaves the scope filtered, so " +
                "the button still changes nothing on screen and the sentence that names it is still " +
                "untrue.",
            EmptyScene(EmptyArt.FOLDER, R.string.empty_unread_title, R.string.empty_unread_view_body, false),
            scene(unreadView = true, toggle = true, selectedMailboxId = null),
        )
    }

    // -- the scenes that shipped, unchanged -------------------------------------------------------

    @Test
    fun `the unified inbox keeps the hero, the unread view does not`() {
        assertEquals(EmptyArt.INBOX_ZERO, scene(unified = true, selectedMailboxId = null).art)
        assertEquals(
            "INBOX_ZERO draws the unified inbox's scene and its words promise an empty INBOX. The " +
                "unread view spans every folder of one account, so it takes the neutral folder art.",
            EmptyArt.FOLDER,
            scene(unreadView = true, selectedMailboxId = null).art,
        )
    }

    @Test
    fun `the trash and a user folder keep their own voice`() {
        assertEquals(
            EmptyScene(EmptyArt.TRASH, R.string.empty_trash_title, R.string.empty_trash_body, false),
            scene(selectedMailboxId = "t", role = "trash"),
        )
        assertEquals(
            EmptyScene(EmptyArt.FOLDER, R.string.empty_folder_title, R.string.empty_folder_body, false),
            scene(selectedMailboxId = "p", role = null),
        )
    }

    /**
     * The unread view outranks the folder role left over from the last folder visited: nothing in
     */
    @Test
    fun `the scope outranks a stale folder role`() {
        assertEquals(
            EmptyScene(EmptyArt.FOLDER, R.string.empty_unread_title, R.string.empty_unread_view_body, false),
            scene(unreadView = true, selectedMailboxId = "t", role = "trash"),
        )
    }

    /**
     * The four titles are pinned as LITERALS above, one by one, and never as "whatever
     */
    @Test
    fun `the ids these scenes are told apart by are all different`() {
        val ids = listOf(
            R.string.empty_inbox_title, R.string.empty_inbox_body,
            R.string.empty_folder_title, R.string.empty_folder_body,
            R.string.empty_trash_title, R.string.empty_trash_body,
            R.string.empty_unread_title, R.string.empty_unread_body,
            R.string.empty_unread_view_body,
        )
        assertEquals(
            "Two of the empty-state resources resolve to the SAME id, so at least one assertion in " +
                "this file compares a value with itself. Ids were: $ids",
            ids.size,
            ids.toSet().size,
        )
    }
}
