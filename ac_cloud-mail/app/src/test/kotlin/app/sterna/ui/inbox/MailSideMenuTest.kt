package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.sideMenuItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cloud Mail's side menu is built from the account's own mailboxes: never hardcoded, folders then labels, subfolders nested. */
class MailSideMenuTest {
    private fun mb(id: String, role: String? = null, name: String = id, parentId: String? = null) = Mailbox(id = id, name = name, role = role, parentId = parentId)

    private fun section(boxes: List<Mailbox>) = mailFolderSection(boxes, { it.name }, "Folders", "Labels", SecondRowMode.SideMenu)

    @Test fun `standard folders form Folders and the account's own folders form Labels`() {
        val s = section(listOf(mb("work"), mb("i", "inbox", "Inbox"), mb("s", "sent", "Sent"), mb("home"), mb("t", "trash", "Trash")))
        assertEquals(listOf("folders", "labels"), s.groups.map { it.id })
        assertEquals(listOf("i", "s", "t"), s.groups[0].pages.map { it.id })
        assertEquals("custom folders by name", listOf("home", "work"), s.groups[1].pages.map { it.id })
        assertEquals(SecondRowMode.SideMenu, s.rows)
    }

    @Test fun `the items follow the account, not a fixed list`() {
        val a = sideMenuItems(section(listOf(mb("i", "inbox"), mb("x"))))
        val b = sideMenuItems(section(listOf(mb("i", "inbox"), mb("x"), mb("y"), mb("z"))))
        assertEquals(listOf("group:folders", "i", "group:labels", "x"), a.map { it.id })
        assertEquals(listOf("group:folders", "i", "group:labels", "x", "y", "z"), b.map { it.id })
    }

    @Test fun `subfolders nest under their parent and indent`() {
        val s = section(listOf(mb("i", "inbox"), mb("proj"), mb("proj-a", parentId = "proj"), mb("proj-a1", parentId = "proj-a"), mb("other")))
        val rows = sideMenuItems(s).filter { !it.heading }
        assertEquals(listOf("i", "other", "proj", "proj-a", "proj-a1"), rows.map { it.id })
        assertEquals(listOf(1, 1, 1, 2, 3), rows.map { it.depth })
    }

    @Test fun `no labels, no Labels group; an empty account has an empty menu`() {
        assertEquals(listOf("folders"), section(listOf(mb("i", "inbox"))).groups.map { it.id })
        assertTrue(section(emptyList()).groups.isEmpty())
    }

    @Test fun `each role draws its own catalog icon`() {
        assertEquals(listOf("inbox", "send", "drafts", "delete", "report", "archive", "label", "folder"),
            listOf("inbox", "sent", "drafts", "trash", "junk", "archive", null, "all").map(::mailFolderIcon))
    }
}
