package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ListFoldersTest {
    /** Parse each raw "* LIST ..." line and run it through the real folder mapper. */
    private fun folders(vararg lines: String): List<ImapFolder> =
        parseListFolders(lines.map { ImapParser(ByteArrayInputStream(it.toByteArray(Charsets.UTF_8))).readResponse() })

    @Test
    fun dropsGmailNoselectContainerButKeepsSelectableChildren() {
        val result = folders(
            "* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n",
            "* LIST (\\Noselect \\HasChildren) \"/\" \"[Gmail]\"\r\n",
            "* LIST (\\Sent \\HasNoChildren) \"/\" \"[Gmail]/Sent Mail\"\r\n",
            "* LIST (\\Drafts \\HasNoChildren) \"/\" \"[Gmail]/Drafts\"\r\n",
            "* LIST (\\Trash \\HasNoChildren) \"/\" \"[Gmail]/Trash\"\r\n",
        )
        val byPath = result.associateBy { it.path }

        // The \Noselect container is gone.
        assertNull(byPath["[Gmail]"])
        // INBOX survives, and the selectable children remain with their roles.
        assertTrue(byPath.containsKey("INBOX"))
        assertEquals("inbox", byPath["INBOX"]?.role)
        assertEquals("sent", byPath["[Gmail]/Sent Mail"]?.role)
        assertEquals("drafts", byPath["[Gmail]/Drafts"]?.role)
        assertEquals("trash", byPath["[Gmail]/Trash"]?.role)
    }

    @Test
    fun noselectMatchIsCaseInsensitive() {
        val result = folders(
            "* LIST (\\noSelect \\HasChildren) \"/\" \"[Gmail]\"\r\n",
            "* LIST (\\All) \"/\" \"[Gmail]/All Mail\"\r\n",
        )
        assertNull(result.firstOrNull { it.path == "[Gmail]" })
        assertEquals("all", result.single().role)
    }

    @Test
    fun neverDropsInboxEvenIfMislabelledNoselect() {
        val result = folders("* LIST (\\Noselect) \"/\" \"INBOX\"\r\n")
        assertEquals("inbox", result.single().role)
    }
}
