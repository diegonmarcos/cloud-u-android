package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision behind a notification banner's Delete button, EXECUTED — not re-derived. Every
 */
class BannerDeleteActTest {

    // --- The two ends where the button must do nothing at all, banner included ---

    @Test fun `a message already in Trash is not deleted again`() {
        assertEquals(BannerDeleteAct.DoNothing, bannerDeleteAct(cachedRole = "trash", hasCachedTrash = true))
    }

    @Test fun `an account whose synced folders name no Trash is left alone`() {
        // The role is known, so the folder list HAS been synced: no Trash here means no Trash.
        assertEquals(BannerDeleteAct.DoNothing, bannerDeleteAct(cachedRole = "inbox", hasCachedTrash = false))
    }

    // --- Everything else moves, which is the harmless direction ---

    @Test fun `an ordinary inbox message on an account with a Trash is moved`() {
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = "inbox", hasCachedTrash = true))
    }

    @Test fun `an unknown folder is not a Trash, and does not refuse a delete either`() {
        // No cached row, or a folder the cache knows no role for. Refusing here would break an
        // ordinary Delete on an account whose folder list has not been cached yet.
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = null, hasCachedTrash = true))
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = null, hasCachedTrash = false))
    }

    @Test fun `a folder that lost its claim to the Trash role is not the Trash`() {
        // `~trash` is how the cache stores a folder whose claim another folder won
        // (UNELECTED_ROLE_MARK): a delete really does move OUT of it, so it takes the normal path.
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = "~trash", hasCachedTrash = true))
    }

    @Test fun `a Trash row on an account whose Trash is not cached still moves nothing`() {
        // Both reasons at once — already at destination AND no elected Trash in the cache. Either
        // arm answers DoNothing, so this pins the ANSWER, not an order between them.
        assertEquals(BannerDeleteAct.DoNothing, bannerDeleteAct(cachedRole = "trash", hasCachedTrash = false))
    }
}
