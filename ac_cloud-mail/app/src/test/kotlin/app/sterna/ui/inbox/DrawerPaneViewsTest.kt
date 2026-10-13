package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The side pane's views are exactly Inbox, Starred and Assigned, in that order (owner, 2026-10-13):
 * Home, All inboxes and Unread left it. Read from the drawer's NavigationDrawerItem labels, in source
 * order, up to the folder list.
 */
class DrawerPaneViewsTest {
    private fun source(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/res/values/strings.xml").isFile }
        return File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt").readText()
    }

    @Test fun `the pane holds exactly Inbox, Starred and Assigned`() {
        val src = source()
        val from = src.indexOf("HorizontalDivider(Modifier.padding(bottom = MailMetrics.s12))")
        val to = src.indexOf("var folderTab by remember", from)
        val block = src.substring(from, to)
        val labels = Regex("""NavigationDrawerItem\(\s*icon = \{[^}]*\},\s*label = \{ DrawerLabel\(([^\n]*)\) \}""")
            .findAll(block).map { it.groupValues[1].trim() }.toList()
        assertEquals(
            listOf(
                "stringResource(R.string.inbox_drawer_inbox)",
                "stringResource(R.string.folder_flagged)",
                "stringResource(R.string.assigned_title)",
            ),
            labels,
        )
    }
}
