package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the folder ON SCREEN stops being reachable and the list must fall back to the Inbox — the
 */
class SelectionIsUnreachableTest {

    private fun folder(
        id: String,
        role: String? = null,
        parentId: String? = null,
        subscribed: Boolean = true,
    ) = Mailbox(id = id, name = id, role = role, parentId = parentId, isSubscribed = subscribed)

    /**
     * One account's folder list, as the cache delivers it — whole, unfiltered:
     */
    private val folders = listOf(
        folder("inbox", role = "inbox"),
        folder("vieux", subscribed = false),
        folder("archive", role = "archive", subscribed = false),
        folder("projet", subscribed = false),
        folder("projet/done", parentId = "projet"),
        folder("travail"),
    )

    @Test fun `the fixture is what the rule is supposed to see`() {
        // Pinned as literals, so a change in [visibleFolders] shows up HERE as a fixture that no
        // longer says what these cases claim, instead of quietly redefining every expectation below.
        assertEquals(
            listOf("inbox", "vieux", "archive", "projet", "projet/done", "travail"),
            visibleFolders(folders, false).map { it.id },
        )
        assertEquals(
            listOf("inbox", "archive", "projet", "projet/done", "travail"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    // ── the reported symptom ─────────────────────────────────────────────────────────────────────

    @Test fun `the folder on screen was just hidden by the setting, so the list falls back`() {
        // Bench, 2026-08-25: `Vieux` open, the box ticked in the account's settings, back to the
        // list — header and messages still `Vieux`, offered by no drawer and no move-to picker,
        // and no way back into it without unticking.
        assertTrue(selectionIsUnreachable("vieux", folders, true))
    }

    @Test fun `with the setting off nobody is moved out of an unsubscribed folder`() {
        // The default every account is on. Answering true here would expel every user from every
        // unsubscribed folder, on every account, all the time.
        assertFalse(selectionIsUnreachable("vieux", folders, false))
    }

    // ── #89, both ways round ─────────────────────────────────────────────────────────────────────

    @Test fun `a deleted folder still falls back, setting off`() {
        assertTrue(selectionIsUnreachable("travail", folders.filterNot { it.id == "travail" }, false))
    }

    @Test fun `a deleted folder still falls back, setting on`() {
        assertTrue(selectionIsUnreachable("travail", folders.filterNot { it.id == "travail" }, true))
    }

    // ── what stays put with the setting ON ───────────────────────────────────────────────────────

    @Test fun `an unsubscribed folder that carries a role is visible, so it stays selected`() {
        assertFalse(selectionIsUnreachable("archive", folders, true))
    }

    @Test fun `an unsubscribed parent of a visible folder stays selected`() {
        assertFalse(selectionIsUnreachable("projet", folders, true))
    }

    @Test fun `a subscribed folder stays selected`() {
        assertFalse(selectionIsUnreachable("travail", folders, true))
        assertFalse(selectionIsUnreachable("projet/done", folders, true))
        assertFalse(selectionIsUnreachable("inbox", folders, true))
    }

    // ── the conservative guards, which this must never spend ─────────────────────────────────────

    @Test fun `an empty folder list means not loaded yet, never gone`() {
        // First launch, an account switch before the new account's folders are cached, a cleared
        // cache. [InboxViewModel] resets its snapshot to exactly this on an account change.
        assertFalse(selectionIsUnreachable("vieux", emptyList(), false))
        assertFalse(selectionIsUnreachable("vieux", emptyList(), true))
    }

    @Test fun `the unified inbox has no folder to lose`() {
        assertFalse(selectionIsUnreachable(null, folders, true))
        assertFalse(selectionIsUnreachable(null, folders, false))
        assertFalse(selectionIsUnreachable(null, emptyList(), true))
    }

    @Test fun `a visible list that came out empty is not known yet either`() {
        // Nothing subscribed and no role anywhere: the filter answers an empty list, which says
        // "this account's subscriptions are not usable", not "your folder was taken away". Being
        // bounced to an Inbox that is itself hidden would be the worse of the two states.
        val nothingVisible = listOf(folder("vieux", subscribed = false), folder("autre", subscribed = false))
        assertEquals(emptyList<String>(), visibleFolders(nothingVisible, true).map { it.id })
        assertFalse(selectionIsUnreachable("vieux", nothingVisible, true))
    }

    @Test fun `a deleted folder falls back even when nothing at all is visible`() {
        // The whole list is judged as well as the filtered one, and this is why. Here the filter
        // answers an empty list, which reads as "not known yet" — but the WHOLE list is known, and
        // the folder on screen is not in it. #89 must not be spent by a server that reports
        // nothing subscribed.
        val nothingVisible = listOf(folder("vieux", subscribed = false), folder("autre", subscribed = false))
        assertTrue(selectionIsUnreachable("supprime", nothingVisible, true))
    }

    @Test fun `ids are matched exactly, not by prefix`() {
        // IMAP ids are paths: a sibling whose id merely starts the same is not the folder.
        assertTrue(selectionIsUnreachable("projet", listOf(folder("projet-2"), folder("projet/done")), true))
    }
}
