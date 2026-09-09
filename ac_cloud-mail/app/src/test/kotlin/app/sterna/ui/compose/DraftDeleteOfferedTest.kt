package app.sterna.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the composer offers to delete the draft it is editing (#127, #95).
 */
class DraftDeleteOfferedTest {

    @Test fun `a saved draft, read and in hand, may be deleted`() {
        assertTrue(
            draftDeleteOffered(
                restore = false,
                draftId = "draft-1",
                draftInHand = true,
                localDraftHeld = false,
            ),
        )
    }

    @Test fun `a draft the phone is keeping may be deleted too, with no cached row behind it`() {
        // The row exists only in `local_drafts`; no server ever saw it, so nothing is in the cache
        // and `draftInHand` can never be true. The lease this composer holds is what makes the
        // button honest — it is exactly what the deletion acts on.
        assertTrue(
            draftDeleteOffered(
                restore = false,
                draftId = "local-draft:d1",
                draftInHand = false,
                localDraftHeld = true,
            ),
        )
    }

    @Test fun `a local draft whose lease this composer lost offers nothing`() {
        // `LocalDraftEdit.Gone`: the upload worker consumed the row between the list being drawn
        // and the tap landing. The navigation argument still names a local draft and the editor is
        // still on screen — and a button here would do nothing at all.
        assertFalse(
            draftDeleteOffered(
                restore = false,
                draftId = "local-draft:d1",
                draftInHand = false,
                localDraftHeld = false,
            ),
        )
    }

    @Test fun `a new mail has nothing to delete`() {
        assertFalse(
            draftDeleteOffered(restore = false, draftId = null, draftInHand = false, localDraftHeld = false),
        )
    }

    @Test fun `a reply or forward has nothing to delete either`() {
        // No draft id: whatever is on screen exists only on screen. Closing already discards it,
        // and the leave dialog says so.
        assertFalse(
            draftDeleteOffered(restore = false, draftId = null, draftInHand = true, localDraftHeld = false),
        )
    }

    @Test fun `a message taken back out of the send queue is never offered this button`() {
        // It carries a draft id of its own, and its row waits in the outbox marked EDITING: that
        // row has to be handed back or consumed, and deleting it is the outbox screen's gesture,
        assertFalse(
            draftDeleteOffered(restore = true, draftId = "draft-1", draftInHand = true, localDraftHeld = false),
        )
        assertFalse(
            draftDeleteOffered(restore = true, draftId = "draft-1", draftInHand = false, localDraftHeld = true),
        )
    }

    @Test fun `a draft that could not be read offers nothing`() {
        // Offline: the navigation argument is there, the fetch failed, the composer is blank and
        // says so. A Delete here would either do nothing or destroy a draft whose contents this
        // screen never saw.
        assertFalse(
            draftDeleteOffered(restore = false, draftId = "draft-1", draftInHand = false, localDraftHeld = false),
        )
    }

    @Test fun `the whole truth table, written out`() {
        // Sixteen inputs, sixteen answers spelled out rather than recomputed — a table that derives
        // the expectation from the same expression as the code under it would agree with any
        // mistake that expression makes. Exactly three rows are true, and all three are a live
        // draft this composer really holds one end of.
        val expected = mapOf(
            Row(false, "draft-1", true, true) to true,
            Row(false, "draft-1", true, false) to true,
            Row(false, "draft-1", false, true) to true,
            Row(false, "draft-1", false, false) to false,
            Row(false, null, true, true) to false,
            Row(false, null, true, false) to false,
            Row(false, null, false, true) to false,
            Row(false, null, false, false) to false,
            Row(true, "draft-1", true, true) to false,
            Row(true, "draft-1", true, false) to false,
            Row(true, "draft-1", false, true) to false,
            Row(true, "draft-1", false, false) to false,
            Row(true, null, true, true) to false,
            Row(true, null, true, false) to false,
            Row(true, null, false, true) to false,
            Row(true, null, false, false) to false,
        )
        expected.forEach { (input, answer) ->
            assertTrue(
                "draftDeleteOffered(restore = ${input.restore}, draftId = ${input.draftId}, " +
                    "draftInHand = ${input.draftInHand}, localDraftHeld = ${input.localDraftHeld}) " +
                    "must be $answer",
                draftDeleteOffered(
                    input.restore,
                    input.draftId,
                    input.draftInHand,
                    input.localDraftHeld,
                ) == answer,
            )
        }
    }

    private data class Row(
        val restore: Boolean,
        val draftId: String?,
        val draftInHand: Boolean,
        val localDraftHeld: Boolean,
    )
}
