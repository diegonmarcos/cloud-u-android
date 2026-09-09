package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the subscription filter (#174) does to the three surfaces that are not the drawer: the two
 */
class SubscribedFolderSurfacesTest {

    private fun folder(
        id: String,
        role: String? = null,
        name: String = id,
        parentId: String? = null,
        subscribed: Boolean = true,
        unread: Int = 0,
    ) = Mailbox(
        id = id,
        name = name,
        role = role,
        parentId = parentId,
        isSubscribed = subscribed,
        unreadForList = unread,
    )

    private val english = Collator.getInstance(Locale.ENGLISH)

    private val folders = listOf(
        folder("inbox", role = "inbox"),
        folder("trash", role = "trash", subscribed = false),
        folder("Old", name = "Old", subscribed = false),
        folder("ProjectA", name = "ProjectA", subscribed = false),
        folder("ProjectA/Done", name = "Done"),
    )

    // ── T6: the pickers ──────────────────────────────────────────────────────────────────────────

    @Test fun `a hidden folder is not offered as a move destination`() {
        assertEquals(
            "with the setting on, 'Old' must not be offered — and 'trash' must be, unsubscribed " +
                "or not, or there is nowhere to delete into",
            listOf("trash", "ProjectA", "ProjectA/Done"),
            moveTargets(folders, "inbox", showOnlySubscribed = true, collator = english).map { it.id },
        )
        assertEquals(
            "with the setting off the picker offers everything it always did",
            listOf("trash", "Old", "ProjectA", "ProjectA/Done"),
            moveTargets(folders, "inbox", showOnlySubscribed = false, collator = english).map { it.id },
        )
    }

    @Test fun `the ancestors are resolved against the WHOLE list, not the offered targets`() {
        // The trap this test exists for: filtering the list ONCE, at the call site, and handing the
        // result to both of moveTargets' uses of it. The picker would still look right here — and a
        // child whose parent is hidden would lose the parent segment of the path shown under its
        // name (#109). So the ARGUMENT is pinned, not just the answer.
        val contexts = mutableListOf<List<String>>()
        val resolved = mutableMapOf<String, List<String>>()
        moveTargets(folders, "inbox", showOnlySubscribed = true, collator = english) { m, list ->
            contexts += list.map { it.id }
            mailboxAncestors(m, list).also { resolved[m.id] = it }
        }
        assertEquals(
            "the ancestors were resolved against a FILTERED list: 'Old' is missing from the " +
                "context, so the caller (or this function) filtered before resolving",
            listOf(listOf("inbox", "trash", "Old", "ProjectA", "ProjectA/Done")),
            contexts.distinct(),
        )
        assertEquals(
            "the child lost the parent segment of its displayed path",
            listOf("ProjectA"),
            resolved["ProjectA/Done"],
        )
    }

    // ── T7: hidden is not deleted ────────────────────────────────────────────────────────────────

    @Test fun `a folder the setting hides must not bounce the list back to the Inbox`() {
        assertFalse(
            "#89's fallback is judged on the WHOLE folder list. A folder hidden by a tick is not a " +
                "folder that was deleted, and sending the user back to the Inbox for having " +
                "ticked a box is a state nobody asked for.",
            selectionIsGone("Old", folders),
        )
        // Witness that the two answers really differ, so the assertion above is not vacuous: this
        // is what the wrong wiring would answer.
        assertTrue(selectionIsGone("Old", visibleFolders(folders, true)))
        assertTrue("a folder that really left the list still bounces", selectionIsGone("gone", folders))
    }

    // ── T8: the unread view's scope, and the badge that follows it ───────────────────────────────

    @Test fun `the unread view pages the visible folders, and its badge follows that scope`() {
        val counted = listOf(
            folder("inbox", role = "inbox", unread = 3),
            folder("projets", unread = 4),
            folder("Old", name = "Old", subscribed = false, unread = 5),
            folder("trash", role = "trash", subscribed = false, unread = 9),
            folder("ProjectA", name = "ProjectA", subscribed = false, unread = 2),
            folder("ProjectA/Done", name = "Done", unread = 6),
        )
        val hidden = unreadViewScopes("a", visibleFolders(counted, true))
        assertEquals(
            "the scope must drop the hidden folder and keep the parent its subscribed child needs",
            listOf("a" to "inbox", "a" to "projets", "a" to "ProjectA", "a" to "ProjectA/Done"),
            hidden,
        )
        assertEquals(
            listOf("a" to "inbox", "a" to "projets", "a" to "Old", "a" to "ProjectA", "a" to "ProjectA/Done"),
            unreadViewScopes("a", visibleFolders(counted, false)),
        )
        // The badge is NOT filtered a second time: it sums the folders the scope names. The scope
        // is the only place the rule is applied, and this executes the shipped counter over it.
        assertEquals(15, unreadViewCount("a", hidden, counted))
        assertEquals(20, unreadViewCount("a", unreadViewScopes("a", visibleFolders(counted, false)), counted))
        assertEquals(
            "the counter must answer the same whether it is handed the whole folder list or the " +
                "filtered one — the scope decides, and a second filter would be a second rule",
            unreadViewCount("a", hidden, counted),
            unreadViewCount("a", hidden, visibleFolders(counted, true)),
        )
    }
}
