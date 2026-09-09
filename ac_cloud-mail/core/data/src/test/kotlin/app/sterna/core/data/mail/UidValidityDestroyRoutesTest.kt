package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a held-back destroy splits into requests — the decision `InboxViewModel.foldersToDestroy`
 */
class UidValidityDestroyRoutesTest {

    /**
     * THE CASE THE OLD GROUPING GOT WRONG. Two rows of ONE folder, read either side of a
     */
    @Test fun `one folder read under two numberings becomes two requests, each with its own number`() {
        val numbering = mapOf("old-a" to 42L, "fresh" to 77L, "old-b" to 42L)

        val routes = UidValidity.imapDestroyRoutes(
            listOf("old-a" to TRASH, "fresh" to TRASH, "old-b" to TRASH),
        ) { numbering[it] }

        assertEquals(
            "the ids read under 42 must travel together, in the order they came, opposing 42 — and " +
                "the id read under 77 must travel alone, opposing 77",
            listOf(
                UidValidity.ImapDestroyRoute(TRASH, listOf("old-a", "old-b"), 42L),
                UidValidity.ImapDestroyRoute(TRASH, listOf("fresh"), 77L),
            ),
            routes,
        )
    }

    /**
     * The witness for JMAP, where every numbering is null: the split must collapse back to exactly
     */
    @Test fun `a selection with no numbering at all is one request per folder, ids in order`() {
        val routes = UidValidity.imapDestroyRoutes(
            listOf("m1" to TRASH, "m2" to ARCHIVE, "m3" to TRASH, "m4" to ARCHIVE),
        ) { null }

        assertEquals(
            listOf(
                UidValidity.ImapDestroyRoute(TRASH, listOf("m1", "m3"), null),
                UidValidity.ImapDestroyRoute(ARCHIVE, listOf("m2", "m4"), null),
            ),
            routes,
        )
    }

    /**
     * A row the cache no longer holds answers nothing, and "nothing" is NOT the same key as a
     */
    @Test fun `an id whose row says nothing is routed apart, opposing nothing`() {
        val numbering = mapOf("known" to 42L)

        val routes = UidValidity.imapDestroyRoutes(
            listOf("known" to TRASH, "evicted" to TRASH),
        ) { numbering[it] }

        assertEquals(
            listOf(
                UidValidity.ImapDestroyRoute(TRASH, listOf("known"), 42L),
                UidValidity.ImapDestroyRoute(TRASH, listOf("evicted"), null),
            ),
            routes,
        )
    }

    /** Two folders under the same number stay two requests: the folder is half of the key, and one
     *  SELECT addresses one folder. */
    @Test fun `two folders under one numbering are still two requests`() {
        val routes = UidValidity.imapDestroyRoutes(
            listOf("t1" to TRASH, "a1" to ARCHIVE),
        ) { 42L }

        assertEquals(
            listOf(
                UidValidity.ImapDestroyRoute(TRASH, listOf("t1"), 42L),
                UidValidity.ImapDestroyRoute(ARCHIVE, listOf("a1"), 42L),
            ),
            routes,
        )
    }

    /** The folder comes from the TARGET — the row frozen at the confirmation — and never from the
     *  id: `ImapMailService.mailboxOf` would answer nothing for a JMAP id, and this path serves
     *  both protocols. An id that looks like nothing at all still routes under the folder it was
     *  confirmed in. */
    @Test fun `the folder is the one frozen with the target, whatever the id looks like`() {
        val routes = UidValidity.imapDestroyRoutes(listOf("Mabc-123" to TRASH)) { 42L }

        assertEquals(listOf(UidValidity.ImapDestroyRoute(TRASH, listOf("Mabc-123"), 42L)), routes)
    }

    @Test fun `nothing selected routes nowhere`() {
        assertEquals(emptyList<UidValidity.ImapDestroyRoute>(), UidValidity.imapDestroyRoutes(emptyList()) { 42L })
    }

    private companion object {
        const val TRASH = "Trash"
        const val ARCHIVE = "Archive"
    }
}
