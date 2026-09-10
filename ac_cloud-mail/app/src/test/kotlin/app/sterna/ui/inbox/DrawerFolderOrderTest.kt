package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE ORDER OF THE FOLDER DRAWER, RUN — the assertion that would have caught #247.
 *
 * The drawer had never sorted by name. `mailboxTree` ordered each level by `folderRank` alone, and
 * `folderRank` answers 6 for every folder without a role — so every folder the owner had made tied,
 * `sortedBy` is stable, and the list came out in whatever order the server had listed it in. The
 * old comment said so out loud ("custom folders keep their server order").
 *
 * The names below are the owner's REAL ones, and they are the reason this matters. `AO`, `BO`, `CO`,
 * `DO`, `EO`, `FO` are group headers; `Aa`/`Ab`/`Ac` and `Ba`/`Bc`/`Bd` are the members of the first
 * two groups. That scheme only reads correctly under a sort that puts the uppercase second letter
 * before the lowercase one — see `drawerFolderOrder`, which is why the drawer orders by code point
 * and NOT with the picker's `Collator`. A collator would answer "Aa Large" before "AO SIZE" and
 * file every header after the members it introduces.
 */
class DrawerFolderOrderTest {

    private fun folder(name: String, role: String? = null, parentId: String? = null, unread: Int = 0) =
        Mailbox(id = name, name = name, role = role, parentId = parentId, unreadForList = unread)

    /** The drawer's own resolver, minus Android: only the nine translated roles differ from `name`. */
    private val displayName: (Mailbox) -> String = { it.name }

    private fun order(folders: List<Mailbox>) =
        mailboxTree(folders, collapsed = emptySet(), displayName = displayName).map { it.mailbox.name }

    @Test fun `the owner's own folder list, fed shuffled, comes out in their scheme's order`() {
        // Shuffled deliberately, and NOT into the order the owner pasted: that paste WAS the bug.
        val shuffled = listOf(
            "Cloud - VPS Providers", "Bc Last 7 days", "FO SENDER", "Aa Large (≥10MB)",
            "VPS Oracle", "CO STATE", "01 Inbox - noAlerts", "Ac Small (<1MB)",
            "DO ATTACH", "VPS Git", "BO TIME", "Ab Medium (1-10MB)",
            "EO PRIORITY", "VPS Google", "Ba Last 24h", "AO SIZE",
        ).map { folder(it) }

        assertEquals(
            listOf(
                // Digits sort ahead of letters, so the numbered folder leads.
                "01 Inbox - noAlerts",
                // Group A: the header, then its three members.
                "AO SIZE",
                "Aa Large (≥10MB)",
                "Ab Medium (1-10MB)",
                "Ac Small (<1MB)",
                // Group B: same shape.
                "BO TIME",
                "Ba Last 24h",
                "Bc Last 7 days",
                // "CO STATE" before "Cloud …": 'O' is code point 0x4F, 'l' is 0x6C.
                "CO STATE",
                "Cloud - VPS Providers",
                "DO ATTACH",
                "EO PRIORITY",
                "FO SENDER",
                "VPS Git",
                "VPS Google",
                "VPS Oracle",
            ),
            order(shuffled),
        )
    }

    @Test fun `the standard folders stay pinned above the owner's own, whatever they are called`() {
        // "01 Inbox - noAlerts" begins with a digit and would otherwise lead the whole drawer; a
        // folder the SERVER tagged `inbox` still outranks it, because rank is weighed before name.
        val folders = listOf(
            folder("01 Inbox - noAlerts"),
            folder("AO SIZE"),
            folder("Zulu", role = "archive"),
            folder("Alpha", role = "inbox"),
        )
        assertEquals(listOf("Alpha", "Zulu", "01 Inbox - noAlerts", "AO SIZE"), order(folders))
    }

    @Test fun `a child stays under its own parent after the sort, not merged into the top level`() {
        val folders = listOf(
            folder("VPS Oracle", parentId = "Cloud - VPS Providers"),
            folder("AO SIZE"),
            folder("VPS Git", parentId = "Cloud - VPS Providers"),
            folder("Cloud - VPS Providers"),
            folder("VPS Google", parentId = "Cloud - VPS Providers"),
        )
        assertEquals(
            listOf(
                Triple("AO SIZE", 0, false),
                Triple("Cloud - VPS Providers", 0, true),
                // Sorted among THEMSELVES, one level in — never hoisted out to sort with the root.
                Triple("VPS Git", 1, false),
                Triple("VPS Google", 1, false),
                Triple("VPS Oracle", 1, false),
            ),
            mailboxTree(folders, emptySet(), displayName)
                .map { Triple(it.mailbox.name, it.depth, it.hasChildren) },
        )
    }

    /**
     * The three roles that are TRANSLATED but not ranked — `all`, `flagged`, `important` are shown
     * through a string resource and still answer `folderRank` 6, so they are sorted among the
     * owner's own folders. Sorting them on `mailbox.name` would order a Spanish drawer by an
     * English word that appears nowhere on the screen.
     */
    @Test fun `a translated role that is not pinned sorts where the reader sees it, not by its raw name`() {
        val folders = listOf(
            folder("Zzz raw name", role = "flagged"),
            folder("Beta"),
            folder("Alpha"),
        )
        // Displayed as "Destacados" (the Spanish label), it belongs between Alpha and Beta…
        val spanish: (Mailbox) -> String = { if (it.role == "flagged") "Destacados" else it.name }
        assertEquals(
            listOf("Alpha", "Beta", "Destacados"),
            mailboxTree(folders, emptySet(), spanish).map { spanish(it.mailbox) },
        )
        // …whereas its raw name would have put it last on every phone, translated or not.
        assertEquals(
            listOf("Alpha", "Beta", "Zzz raw name"),
            mailboxTree(folders, emptySet()) { it.name }.map { it.mailbox.name },
        )
    }
}
