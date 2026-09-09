package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawer's fold/unfold choice (per account, persisted), EXECUTED: the write `AccountStore`
 */
class CollapsedFoldersTest {

    private fun account(id: String, folders: Map<String, Boolean> = emptyMap()) = StoredAccount(
        id = id,
        server = "mail.example.test",
        username = "$id@example.test",
        collapsedFolders = folders,
    )

    @Test fun `the fold lands on the named account and on NO other`() {
        val after = withCollapsedFolder(
            listOf(account("a"), account("b"), account("c")),
            id = "b",
            folderId = "Travail",
            collapsed = true,
        )
        assertEquals(
            "folding a folder on one account folded it on another. Written without the " +
                "`if (it.id == id)` guard, one chevron folds whatever carries the same id " +
                "everywhere — and on IMAP the ids are PATHS, so a namesake folder on the " +
                "neighbouring account folds itself with nobody having asked (#92/#121).",
            listOf(emptyMap<String, Boolean>(), mapOf("Travail" to true), emptyMap()),
            after.map { it.collapsedFolders },
        )
        assertEquals("the account list was reordered or lost a record", listOf("a", "b", "c"), after.map { it.id })
    }

    /**
     * THE test of this branch. The registry is the EXPLICIT choice per folder, so unfolding must
     */
    @Test fun `unfolding WRITES false, it does not drop the key`() {
        val after = withCollapsedFolder(
            listOf(account("a", mapOf("Travail" to true))),
            id = "a",
            folderId = "Travail",
            collapsed = false,
        ).single()
        assertEquals(
            "unfolding a folder removed its key instead of storing false. An absent key means " +
                "'nobody decided anything', which is not what the user just did.",
            mapOf("Travail" to false),
            after.collapsedFolders,
        )
    }

    @Test fun `unfolding a folder nobody had touched still writes false`() {
        val after = withCollapsedFolder(listOf(account("a")), id = "a", folderId = "Travail", collapsed = false)
            .single()
        assertEquals(
            "unfolding a folder that carried no key wrote nothing at all. This is the path the " +
                "'reveal the new child' write takes when a subfolder is created under a parent " +
                "nobody had folded, and it must be recorded like any other choice.",
            mapOf("Travail" to false),
            after.collapsedFolders,
        )
    }

    @Test fun `a second folder of the same account is left alone`() {
        val after = withCollapsedFolder(
            listOf(account("a", mapOf("Perso" to true, "Vieux" to false))),
            id = "a",
            folderId = "Travail",
            collapsed = true,
        ).single()
        assertEquals(
            "writing one folder's choice rewrote the account's whole registry.",
            mapOf("Perso" to true, "Vieux" to false, "Travail" to true),
            after.collapsedFolders,
        )
    }

    @Test fun `an unknown id changes nothing`() {
        val before = listOf(account("a", mapOf("Travail" to true)), account("b"))
        val after = withCollapsedFolder(before, id = "ghost", folderId = "Travail", collapsed = true)
        assertEquals("a write for an id no account carries touched a record", before, after)
    }

    @Test fun `nothing but this one field moves`() {
        val settled = account("a").copy(
            accountName = "Ana perso",
            syncWindow = SyncWindow.YEAR_1,
            notificationsEnabled = false,
            uploadSentCopy = false,
            showOnlySubscribedFolders = true,
            watchedFolders = setOf("mbx-2"),
            color = 0x33445566,
        )
        val after = withCollapsedFolder(listOf(settled), id = "a", folderId = "mbx-2", collapsed = true).single()
        assertEquals(
            "writing a fold rebuilt the record instead of copying it: another setting moved. The " +
                "store saves the account list as ONE blob, so a field dropped here is a setting " +
                "lost on disk — watchedFolders above shares this record's ids on purpose.",
            settled.copy(collapsedFolders = mapOf("mbx-2" to true)),
            after,
        )
    }

    // ── the IMAP rename: the id IS the path, so the keys have to follow ─────────────────────────

    /**
     * THE defect of this half. On IMAP a folder's id is its PATH, so renaming it CHANGES the id
     */
    @Test fun `an IMAP rename re-keys the folder AND its descendants, keeping their values`() {
        val after = withRenamedCollapsedFolder(
            listOf(account("a", mapOf("Travail" to false, "Travail/Client A" to true, "Perso" to true))),
            id = "a",
            oldId = "Travail",
            newId = "Boulot",
            delimiter = "/",
        ).single()
        assertEquals(
            "renaming Travail to Boulot must move its own key AND every key under it, values " +
                "unchanged. A false left behind is the whole defect: the renamed folder then reads " +
                "as 'nobody decided' and the drawer's default folds the folder the user had opened " +
                "by hand. A true turned false (or the reverse) is a re-decision nobody asked for. " +
                "Perso is the control: an unrelated folder must not move.",
            mapOf("Boulot" to false, "Boulot/Client A" to true, "Perso" to true),
            after.collapsedFolders,
        )
    }

    /**
     * The prefix is `oldId + delimiter`, never `oldId`. `Travailleur` starts with `Travail` and
     */
    @Test fun `a folder whose name merely STARTS with the old one is left alone`() {
        val after = withRenamedCollapsedFolder(
            listOf(account("a", mapOf("Travail" to true, "Travailleur" to false, "Travailleur/X" to true))),
            id = "a",
            oldId = "Travail",
            newId = "Boulot",
            delimiter = "/",
        ).single()
        assertEquals(
            "Travailleur is a sibling, not a child: only `Travail` and what sits under " +
                "`Travail/` may move. A prefix test on the bare id drags every namesake along.",
            mapOf("Boulot" to true, "Travailleur" to false, "Travailleur/X" to true),
            after.collapsedFolders,
        )
    }

    /**
     * Dovecot's delimiter is `.` — the other branch `MailRepository.renameFolder` resolves and
     * hands down. A re-key that only knows "/" moves nothing at all on such an account.
     */
    @Test fun `the delimiter handed in is the one used, dot included`() {
        val after = withRenamedCollapsedFolder(
            listOf(account("a", mapOf("INBOX.Travail" to false, "INBOX.Travail.Client A" to true))),
            id = "a",
            oldId = "INBOX.Travail",
            newId = "INBOX.Boulot",
            delimiter = ".",
        ).single()
        assertEquals(
            "on a Dovecot account the whole subtree is keyed with dots; a re-key hard-wired to '/' " +
                "leaves the children behind and they fold themselves on the next launch.",
            mapOf("INBOX.Boulot" to false, "INBOX.Boulot.Client A" to true),
            after.collapsedFolders,
        )
    }

    @Test fun `the rename lands on the named account and on NO other`() {
        val before = listOf(
            account("a", mapOf("Travail" to false)),
            account("b", mapOf("Travail" to true)),
        )
        val after = withRenamedCollapsedFolder(before, id = "a", oldId = "Travail", newId = "Boulot", delimiter = "/")
        assertEquals(
            "on IMAP the ids are PATHS, so a namesake folder on the neighbouring account is a " +
                "routine collision (#92/#121): renaming a folder on one account must not touch the " +
                "registry of another.",
            listOf(mapOf("Boulot" to false), mapOf("Travail" to true)),
            after.map { it.collapsedFolders },
        )
        assertEquals("the account list was reordered or lost a record", listOf("a", "b"), after.map { it.id })
    }

    @Test fun `a rename for an id no account carries changes nothing`() {
        val before = listOf(account("a", mapOf("Travail" to false)))
        val after = withRenamedCollapsedFolder(before, id = "ghost", oldId = "Travail", newId = "Boulot", delimiter = "/")
        assertEquals("a rename for an unknown account touched a record", before, after)
    }

    @Test fun `a rename moves nothing but this one field`() {
        val settled = account("a", mapOf("mbx-2" to true)).copy(
            accountName = "Ana perso",
            syncWindow = SyncWindow.YEAR_1,
            watchedFolders = setOf("mbx-2"),
            color = 0x33445566,
        )
        val after = withRenamedCollapsedFolder(listOf(settled), id = "a", oldId = "mbx-2", newId = "mbx-9", delimiter = "/")
            .single()
        assertEquals(
            "re-keying a fold rebuilt the record instead of copying it: another setting moved. The " +
                "store saves the account list as ONE blob, so a field dropped here is a setting " +
                "lost on disk — watchedFolders is re-keyed by its OWN function next door and must " +
                "not be touched from here.",
            settled.copy(collapsedFolders = mapOf("mbx-9" to true)),
            after,
        )
    }

    // ── what a record already on disk decodes to ────────────────────────────────────────────────

    /**
     * There is no migration and there must not be one: the account list is one `@Serializable` blob
     */
    private val json = Json { ignoreUnknownKeys = true }

    private val preBranch =
        """[{"id":"a1","server":"imap.example.test","username":"demo@example.test",
           |"accountName":"Demo","protocol":"IMAP","imapHost":"imap.example.test",
           |"smtpHost":"smtp.example.test"}]""".trimMargin().replace("\n", "")

    @Test fun `a record written before this field decodes to nobody having chosen`() {
        val decoded = json.decodeFromString<List<StoredAccount>>(preBranch).single()
        assertEquals(
            "an account stored before 'collapsedFolders' existed must decode to an EMPTY registry, " +
                "i.e. every folder drawn exactly as it is today.",
            emptyMap<String, Boolean>(),
            decoded.collapsedFolders,
        )
    }

    @Test fun `a stored registry is read back as it was written, false included`() {
        val stored = preBranch.replace(
            "\"id\":\"a1\"",
            "\"id\":\"a1\",\"collapsedFolders\":{\"Travail\":true,\"Perso\":false}",
        )
        val decoded = json.decodeFromString<List<StoredAccount>>(stored).single()
        assertEquals(
            "the registry must survive the round trip with BOTH values. Lose the false entries and " +
                "the field is half write-only: a folder the user opened by hand comes back as " +
                "'nobody decided'.",
            mapOf("Travail" to true, "Perso" to false),
            decoded.collapsedFolders,
        )
    }
}
