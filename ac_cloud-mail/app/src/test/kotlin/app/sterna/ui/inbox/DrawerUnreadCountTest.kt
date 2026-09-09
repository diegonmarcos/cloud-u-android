package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a drawer row COUNTS, EXECUTED ([drawerUnreadCount]) — a folded folder answers the unread it
 */
class DrawerUnreadCountTest {

    private fun mailbox(id: String, unread: Int = 0, role: String? = null, parentId: String? = null) =
        Mailbox(id = id, name = id.substringAfterLast('/'), role = role, parentId = parentId, unreadForList = unread)

    /** The test plan's tree: Travail(1) → Client A(3) → Devis(4), and Client B(2). */
    private val tree = listOf(
        mailbox("Travail", unread = 1),
        mailbox("Travail/Client A", unread = 3),
        mailbox("Travail/Client A/Devis", unread = 4),
        mailbox("Travail/Client B", unread = 2),
    )

    private fun folder(id: String) = tree.first { it.id == id }

    /**
     * The defect this volet closes: since the fold default, `Travail` is folded on the very
     * first launch, so its row is the only place the mail under it can still be seen.
     */
    @Test fun `a folded folder carries its own unread plus every descendant's, grandchild included`() {
        assertEquals(
            "a folded Travail must badge 1+3+2+4 = 10. A 1 is the shipped defect — the mail under " +
                "a folder that folded itself on the first run is on no row at all. A 6 (1+3+2) is " +
                "a walk that stops at the children: mailboxTree hides grandchildren too, so Devis " +
                "is off screen and its 4 unread are nowhere.",
            10,
            drawerUnreadCount(folder("Travail"), tree, setOf("Travail")),
        )
    }

    /**
     * THE state the app STARTS in, which no other case here plays: a parent and one of its own
     */
    @Test fun `the first launch folds a parent AND its child, and the top row still counts through`() {
        val started = setOf("Travail", "Travail/Client A")
        assertEquals(
            "with Travail and Client A both folded — the state the drawer opens in on this tree — " +
                "Travail's row must still badge 1+3+2+4 = 10. It is the ONLY row on screen for " +
                "everything under it, so a walk that stops at a folded descendant leaves Devis' 4 " +
                "and Client A's 3 nowhere: a 3 (1+2) is that walk, a 1 is no walk at all.",
            10,
            drawerUnreadCount(folder("Travail"), tree, started),
        )
        assertEquals(
            "Client A is folded too, and its row is not drawn (its parent hides it) — but the " +
                "count it answers must not depend on who is folded ABOVE it: 3+4 = 7, the same as " +
                "when Travail is open. It is what the row shows the moment Travail is unfolded.",
            7,
            drawerUnreadCount(folder("Travail/Client A"), tree, started),
        )
        assertEquals(
            "Client B is open and stays on its own 2 while its parent is folded.",
            2,
            drawerUnreadCount(folder("Travail/Client B"), tree, started),
        )
    }

    @Test fun `an open folder carries its own unread, and each child carries its own`() {
        assertEquals(
            "with nothing folded, Travail's own row must show its own 1: its children have rows of " +
                "their own right under it, and summing here would count the same mail twice on " +
                "one screen.",
            1,
            drawerUnreadCount(folder("Travail"), tree, emptySet()),
        )
        assertEquals(
            "an open Client A shows its own 3 — Devis has a row of its own below it.",
            3,
            drawerUnreadCount(folder("Travail/Client A"), tree, emptySet()),
        )
        assertEquals("a leaf shows its own 4.", 4, drawerUnreadCount(folder("Travail/Client A/Devis"), tree, emptySet()))
    }

    /**
     * The sum follows what is HIDDEN, not the hierarchy. With Travail open and Client A folded,
     * Devis is the only row off screen, and it is Client A's row that has to account for it.
     */
    @Test fun `an open parent of a folded child stays on its own count, the folded child sums`() {
        val folded = setOf("Travail/Client A")
        assertEquals(
            "Client A is folded, so its row must carry 3+4 = 7 — Devis is the row that is gone.",
            7,
            drawerUnreadCount(folder("Travail/Client A"), tree, folded),
        )
        assertEquals(
            "Travail is OPEN: its row must stay at 1. Summing a whole subtree whenever anything " +
                "under it is folded double-counts Devis on a single screen — once on Client A's " +
                "row and once on Travail's.",
            1,
            drawerUnreadCount(folder("Travail"), tree, folded),
        )
        assertEquals("Client B is untouched by its sibling's fold.", 2, drawerUnreadCount(folder("Travail/Client B"), tree, folded))
    }

    /**
     * The scope. The drawer hands its DRAWN list (`MailUi.visibleMailboxes`); a descendant the
     */
    @Test fun `a descendant absent from the list handed in is not counted`() {
        val drawn = listOf(folder("Travail"), folder("Travail/Client A"))
        assertEquals(
            "Devis and Client B are not in the list the drawer draws, so a folded Travail must " +
                "badge 1+3 = 4. A 10 means the sum walked a list the screen does not show.",
            4,
            drawerUnreadCount(folder("Travail"), drawn, setOf("Travail")),
        )
        assertEquals(
            "the same folder, the same fold, against the WHOLE list: 1+3+2+4 = 10. The two numbers " +
                "differ, which is the whole point — the list handed in decides, so passing the " +
                "account's whole list at the call site is a real and silent defect.",
            10,
            drawerUnreadCount(folder("Travail"), tree, setOf("Travail")),
        )
    }

    @Test fun `nesting declared by the JMAP parentId is walked too, grandchild included`() {
        val jmap = listOf(
            mailbox("m1", unread = 1),
            mailbox("m2", unread = 3, parentId = "m1"),
            mailbox("m3", unread = 4, parentId = "m2"),
        )
        assertEquals(
            "JMAP ids carry no path: filiation comes from parentId, and a walk that only splits " +
                "ids on a delimiter sums nothing at all on a JMAP account.",
            8,
            drawerUnreadCount(jmap[0], jmap, setOf("m1")),
        )
    }

    @Test fun `a folded folder with nothing under it answers its own count, and zero stays zero`() {
        val leaf = mailbox("Perso", unread = 5)
        assertEquals(
            "a folder with no descendant in the list has nothing to add to its own count.",
            5,
            drawerUnreadCount(leaf, listOf(leaf), setOf("Perso")),
        )
        val quiet = listOf(mailbox("Travail"), mailbox("Travail/Client A"), mailbox("Travail/Client A/Devis"))
        assertEquals(
            "nothing unread anywhere under a folded folder must answer 0 — the row then shows the " +
                "bare folder name, with no (0) after it.",
            0,
            drawerUnreadCount(quiet[0], quiet, setOf("Travail")),
        )
    }

    /**
     * A cycle is a server answer, not an impossibility — the guard [mailboxTree] and
     * [visibleFolders] both carry. Without it this walk either never ends or counts a folder twice.
     */
    @Test(timeout = 5_000) fun `a parentId pointing back down its own chain neither spins nor double-counts`() {
        val folders = listOf(
            mailbox("a", unread = 5, parentId = "b"),
            mailbox("b", unread = 3, parentId = "a"),
        )
        assertEquals(
            "each of the two names the other as its parent. The folded one must count itself once " +
                "and the other once: 5+3 = 8. A 13 is `a` counted twice; not finishing at all is " +
                "the unguarded walk.",
            8,
            drawerUnreadCount(folders[0], folders, setOf("a")),
        )
    }

    @Test fun `a folder the list does not hold answers its own count`() {
        val gone = mailbox("Vieux", unread = 2)
        assertEquals(
            "a row can be drawn from a list this folder has just left; there is nothing to sum, " +
                "and its own count is the honest answer.",
            2,
            drawerUnreadCount(gone, tree, setOf("Vieux")),
        )
    }
}
