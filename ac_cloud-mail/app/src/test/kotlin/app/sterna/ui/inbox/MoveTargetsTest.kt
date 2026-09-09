package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders a move-to-folder picker offers, and in which order — the rule behind both the
 * list's selection-bar picker and the reader's overflow entry (#73).
 */
class MoveTargetsTest {

    private fun mailbox(id: String, role: String? = null, name: String = id, parentId: String? = null) =
        Mailbox(id = id, name = name, role = role, parentId = parentId)

    private val folders = listOf(
        mailbox("custom-b", name = "Bills"),
        mailbox("trash-1", role = "trash"),
        mailbox("custom-a", name = "Archive 2024"),
        mailbox("inbox-1", role = "inbox"),
        mailbox("junk-1", role = "junk"),
        mailbox("archive-1", role = "archive"),
        mailbox("drafts-1", role = "drafts"),
        mailbox("sent-1", role = "sent"),
    )

    @Test fun `the folder the message is already in is not offered`() {
        val targets = moveTargets(folders, currentMailboxId = "junk-1")
        assertTrue(targets.none { it.id == "junk-1" })
        assertEquals(folders.size - 1, targets.size)
    }

    @Test fun `standard folders lead in a fixed order, custom folders follow`() {
        // The role prefix is unchanged. The TAIL changed: the two custom folders used to come out
        // in arrival order (custom-b "Bills" then custom-a "Archive 2024"); they are now sorted by
        // name, so "Archive 2024" leads.
        val ids = moveTargets(folders, currentMailboxId = null).map { it.id }
        assertEquals(
            listOf("inbox-1", "drafts-1", "sent-1", "junk-1", "archive-1", "trash-1", "custom-a", "custom-b"),
            ids,
        )
    }

    @Test fun `custom folders are sorted by name, not by the order the server sent them`() {
        // The witness for this change: an IMAP LIST (or a JMAP sortOrder) that hands Zulu first
        // must not put Zulu first in the picker.
        val custom = listOf(mailbox("Zulu"), mailbox("Alpha"), mailbox("Work"))
        assertEquals(listOf("Alpha", "Work", "Zulu"), moveTargets(custom, null).map { it.id })
    }

    @Test fun `a child folder follows its parent, never a root folder that sorts between them`() {
        // Compared segment by segment, so "Work_Notes" (a ROOT folder, and "_" sorts before "/")
        // cannot slip between "Work" and its children the way a whole-path string compare would.
        val nested = listOf(
            mailbox("Xylo"),
            mailbox("Work/Alpha", name = "Alpha"),
            mailbox("Work_Notes"),
            mailbox("Work"),
            mailbox("Work/2026", name = "2026"),
        )
        assertEquals(
            listOf("Work", "Work/2026", "Work/Alpha", "Work_Notes", "Xylo"),
            moveTargets(nested, null).map { it.id },
        )
    }

    @Test fun `a three-level Dovecot account keeps a grandchild under its parent`() {
        // Three levels, because a two-level tree cannot tell a WHOLE ancestor chain from its last
        // segment: with one ancestor, "keep only the nearest one" is the identity. On a Dovecot
        //
        // "INBOX.Zeta" is named "Alpha" on purpose: an id whose last segment is not the folder's
        // name claims no path at all (imapParentPath refuses it), so it sorts on "Alpha" and
        // leads. Sorting on the id would put it LAST. Every other folder here is name == last
        // segment, which is exactly what cannot tell the two apart.
        val dovecot = listOf(
            mailbox("INBOX.Clients.Acme", name = "Acme"),
            mailbox("INBOX.Zeta", name = "Alpha"),
            mailbox("INBOX.Boulot", name = "Boulot"),
            mailbox("INBOX.Clients", name = "Clients"),
        )
        assertEquals(
            listOf("INBOX.Zeta", "INBOX.Boulot", "INBOX.Clients", "INBOX.Clients.Acme"),
            moveTargets(dovecot, null, collator = Collator.getInstance(Locale.ENGLISH)).map { it.id },
        )
    }

    @Test fun `a JMAP child keeps its parent's path even when the parent is the folder being left`() {
        // Paths are resolved against the WHOLE account list, not the offered targets: dropping
        // "Work" from the list would leave its child sorting as a bare "Zebra", i.e. after "Xylo".
        val jmap = listOf(
            mailbox("p1", name = "Work"),
            mailbox("c1", name = "Zebra", parentId = "p1"),
            mailbox("r1", name = "Xylo"),
        )
        val ids = moveTargets(jmap, "p1", collator = Collator.getInstance(Locale.ENGLISH)).map { it.id }
        assertEquals(listOf("c1", "r1"), ids)
    }

    @Test fun `names are compared with a collator, not with UTF-16 code units`() {
        // German bench: "Über" belongs next to "U", not after "Z" as String.compareTo would have it.
        val german = listOf(mailbox("Über"), mailbox("Zoo"), mailbox("Alpha"))
        val ids = moveTargets(german, null, collator = Collator.getInstance(Locale.GERMAN)).map { it.id }
        assertEquals(listOf("Alpha", "Über", "Zoo"), ids)
    }

    @Test fun `a Russian folder list sorts as Russian, not as code points`() {
        // "Ёлка" is U+0401, BELOW every other Cyrillic letter in UTF-16, so a naive sort files it
        // ahead of "Альфа"; Russian collation puts Ё right after Е. Latin still leads Cyrillic.
        val russian = listOf(mailbox("Ёлка"), mailbox("Zoo"), mailbox("Ежи"), mailbox("Alpha"))
        val ids = moveTargets(russian, null, collator = Collator.getInstance(Locale("ru"))).map { it.id }
        assertEquals(listOf("Alpha", "Zoo", "Ежи", "Ёлка"), ids)
    }

    @Test fun `the picker follows the device language, not one hard-coded locale`() {
        // Called WITHOUT a collator: the DEFAULT is what this pins, i.e. the promise that the
        // picker sorts the way the phone's language does.
        //
        // Swedish on purpose, and it must stay Swedish: "Ö" is a letter of its own, filed AFTER
        // "Z", while US (and German) collation files it with the "O"s. German would prove
        // nothing here — "Ü" sorts with the "U"s in US collation too, so the test would stay
        // green even with the device locale ignored.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("sv"))
            val swedish = listOf(mailbox("Zoo"), mailbox("Öl"), mailbox("Alpha"))
            assertEquals(listOf("Alpha", "Zoo", "Öl"), moveTargets(swedish, null).map { it.id })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `case does not split a folder family across the list`() {
        // "archive-2025" and "Archive-2024" differ only in case; a code-unit sort sends every
        // lowercase name to the far end, past "Zoo".
        val mixedCase = listOf(mailbox("archive-2025"), mailbox("Zoo"), mailbox("Archive-2024"))
        assertEquals(
            listOf("Archive-2024", "archive-2025", "Zoo"),
            moveTargets(mixedCase, null, collator = Collator.getInstance(Locale.ENGLISH)).map { it.id },
        )
    }

    @Test fun `an id with an exotic delimiter sorts on its own name, and nothing blows up`() {
        // Accepted degradation: only "/" and "." are read as IMAP delimiters, so a backslash id
        // yields no path at all and the folder sorts on its leaf name — here ahead of "Alpha",
        // NOT under "Work".
        val exotic = listOf(mailbox("Work"), mailbox("Work\\2026", name = "2026"), mailbox("Alpha"))
        assertEquals(
            listOf("Work\\2026", "Alpha", "Work"),
            moveTargets(exotic, null, collator = Collator.getInstance(Locale.ENGLISH)).map { it.id },
        )
    }

    @Test fun `each folder's path is resolved once, not once per comparison`() {
        // The cost guard: moveTargets runs in composition without remember, and every ancestor
        // walk rebuilds a map over the whole folder list. Resolving inside the comparator would
        // be O(n log n) walks on a 120-folder account instead of 120.
        val many = (1..120).map { mailbox("folder-%03d".format(it)) }.reversed()
        var resolutions = 0
        val ids = moveTargets(
            many,
            currentMailboxId = null,
            collator = Collator.getInstance(Locale.ENGLISH),
            ancestorsOf = { mailbox, all -> resolutions++; mailboxAncestors(mailbox, all) },
        ).map { it.id }
        assertEquals(120, resolutions)
        assertEquals("folder-001", ids.first())
        assertEquals("folder-120", ids.last())
    }

    @Test fun `a message read from Trash can be moved back out`() {
        // The reported case (#73): a draft that only Archive could rescue from the Trash.
        val ids = moveTargets(folders, currentMailboxId = "trash-1").map { it.id }
        assertTrue("inbox-1" in ids)
        assertTrue("drafts-1" in ids)
        assertTrue("trash-1" !in ids)
    }

    @Test fun `an unknown current folder excludes nothing`() {
        // Unified inbox, or a message whose body fetch dropped its mailbox: offering one folder
        // that happens to be its own beats hiding a guessed one.
        assertEquals(folders.size, moveTargets(folders, currentMailboxId = null).size)
    }

    @Test fun `an all-mail folder ranks with Archive`() {
        // Gmail-style servers expose "all" instead of an Archive role.
        val gmail = listOf(mailbox("all-1", role = "all"), mailbox("trash-1", role = "trash"), mailbox("inbox-1", role = "inbox"))
        assertEquals(listOf("inbox-1", "all-1", "trash-1"), moveTargets(gmail, null).map { it.id })
    }

    @Test fun `an unknown role is treated as a custom folder`() {
        val exotic = listOf(mailbox("weird-1", role = "templates"), mailbox("inbox-1", role = "inbox"))
        assertEquals(listOf("inbox-1", "weird-1"), moveTargets(exotic, null).map { it.id })
    }

    @Test fun `an account with a single folder offers nothing`() {
        // The reader hides its menu entry on this — a picker with nothing to pick is a dead end.
        assertTrue(moveTargets(listOf(mailbox("inbox-1", role = "inbox")), "inbox-1").isEmpty())
        assertTrue(moveTargets(emptyList(), "inbox-1").isEmpty())
    }

    @Test fun `ids are matched exactly, not by prefix`() {
        // IMAP ids are paths: a subfolder of the current folder is a legitimate destination.
        val nested = listOf(mailbox("Work"), mailbox("Work/2026"), mailbox("Workshop"))
        assertEquals(listOf("Work/2026", "Workshop"), moveTargets(nested, "Work").map { it.id })
    }
}
