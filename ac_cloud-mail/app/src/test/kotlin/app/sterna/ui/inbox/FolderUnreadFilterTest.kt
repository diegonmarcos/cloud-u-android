package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the drawer's `Unread` tab lists (#247), RUN — including the parent rule, which is a choice. */
class FolderUnreadFilterTest {

    private fun folder(name: String, unread: Int = 0, parentId: String? = null) =
        Mailbox(id = name, name = name, parentId = parentId, unreadForList = unread)

    private val account = listOf(
        folder("01 Inbox - noAlerts", unread = 249),
        folder("AO SIZE"),
        folder("Aa Large (≥10MB)", unread = 3),
        folder("Ab Medium (1-10MB)"),
        folder("Cloud - VPS Providers"),
        folder("VPS Git", parentId = "Cloud - VPS Providers"),
        folder("VPS Oracle", unread = 7, parentId = "Cloud - VPS Providers"),
    )

    @Test fun `All is the account untouched — the tab that filters is the other one`() {
        assertEquals(account, foldersForTab(account, FolderTab.ALL))
    }

    @Test fun `Unread lists exactly the folders carrying unread mail, plus the parents holding them`() {
        assertEquals(
            listOf(
                "01 Inbox - noAlerts", // 249 of its own
                "Aa Large (≥10MB)", //    3 of its own
                // Zero unread of its own, and kept anyway: it is the only route to VPS Oracle, and
                // `mailboxTree` builds the tree by parent lookup — drop it and the folder with 7
                // unread disappears from the tab whose entire job is to show it.
                "Cloud - VPS Providers",
                "VPS Oracle", //          7 of its own
            ),
            foldersForTab(account, FolderTab.UNREAD).map { it.name },
        )
    }

    @Test fun `a folder read to zero leaves the tab, and takes a parent it was the only reason for`() {
        val allRead = account.map { it.copy(unreadForList = 0) }
        assertEquals(emptyList<String>(), foldersForTab(allRead, FolderTab.UNREAD).map { it.name })
    }

    @Test fun `the kept parents are ancestors, not just the one directly above`() {
        val deep = listOf(
            folder("Cloud - VPS Providers"),
            folder("VPS Git", parentId = "Cloud - VPS Providers"),
            folder("VPS Git Issues", unread = 2, parentId = "VPS Git"),
        )
        // The grandparent is kept too; keeping only "VPS Git" would orphan the branch just the same.
        assertEquals(
            listOf("Cloud - VPS Providers", "VPS Git", "VPS Git Issues"),
            foldersForTab(deep, FolderTab.UNREAD).map { it.name },
        )
    }

    @Test fun `a parent that is its own ancestor does not hang the walk`() {
        val cycle = listOf(
            folder("a", parentId = "b"),
            folder("b", unread = 1, parentId = "a"),
        )
        assertEquals(listOf("a", "b"), foldersForTab(cycle, FolderTab.UNREAD).map { it.name })
    }
}
