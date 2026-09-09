package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, for the reason [DestroyChecksTheFolderWiringTest]
 */
class DeleteResolvesOrCreatesTrashWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun `the IMAP delete creates a Trash folder rather than refusing`() {
        val body = bodyOf("delete")
        assertEquals(
            "the IMAP branch must resolve the trash role and, failing that, CREATE the folder — " +
                "exactly what archive() does one call along. An `error(…)` here is the defect " +
                "itself: on a server without SPECIAL-USE the user cannot delete at all. Whole " +
                "line, arguments included. Body was:\n$body",
            listOf("""val trash = imapRoleFolder(credentials, "trash") ?: run { imap.createFolder(credentials, "Trash"); "Trash" }"""),
            codeLinesNaming(body, "imapRoleFolder("),
        )
    }

    @Test fun `the JMAP delete resolves by name or creates, through the two helpers`() {
        val body = bodyOf("delete")
        assertEquals(
            "the JMAP branch must go through the pair — role, then THIS account's folders by " +
                "name, then a creation. Reading `rolesToMailboxId[\"trash\"]` inline again (with " +
                "or without an `error(…)` behind it) is the defect back. Whole line. Body was:\n$body",
            listOf("val trash = trashMailboxId(ctx) ?: createTrashFolder(ctx)"),
            codeLinesNaming(body, "trashMailboxId("),
        )
    }

    @Test fun `no delete anywhere on this path falls back on All Mail`() {
        // Archive has that fallback because "all mail" IS an acceptable archive. It is not an
        // acceptable BIN: the message would stay in the list, so the user's delete would appear
        // to do nothing while the message is still there.
        listOf("delete", "createTrashFolder", "trashMailboxId").forEach { function ->
            val body = bodyOf(function)
            assertEquals(
                "$function must never route a delete to the All-Mail folder: a message moved " +
                    "there is still in the list, so the delete silently does nothing. Body was:\n$body",
                emptyList<String>(),
                codeLinesNaming(body, """rolesToMailboxId["all"]"""),
            )
        }
    }

    @Test fun `the by-name lookup reads THIS account's folders, and only through the runnable rule`() {
        val body = bodyOf("trashMailboxId")
        assertEquals(
            "the fallback must run [trashFolderByName] over `ctx.mailboxes` — the folders of the " +
                "account the message belongs to. The global folder cache holds the last-synced " +
                "account only, so from the unified inbox a delete would aim at another account's " +
                "folder id and the move would no-op in silence (#31). Whole line. Body was:\n$body",
            listOf("val byName = trashFolderByName(ctx.mailboxes) ?: return null"),
            codeLinesNaming(body, "trashFolderByName("),
        )
    }

    @Test fun `the role read is the trash role, and no neighbouring one`() {
        val body = bodyOf("trashMailboxId")
        assertEquals(
            "the role this resolves is `trash` and nothing else. Aiming at `junk` (or any other " +
                "role) sends every delete on a NORMAL account into the spam folder — filtered out " +
                "of the lists and emptied on the server's own timer. Whole line. Body was:\n$body",
            listOf("""ctx.rolesToMailboxId["trash"]?.let { return it }"""),
            codeLinesNaming(body, "rolesToMailboxId["),
        )
    }

    @Test fun `the folder this creates is declared a Trash, or nothing at all`() {
        val body = bodyOf("createTrashFolder")
        assertEquals(
            "both creations must ask for a TRASH: the first with the special-use role, the second " +
                "with no role at all for the servers that refuse a client-set one. A role of " +
                "`junk` here hands the user a bin the server treats as spam — filtered, and often " +
                "purged on a timer. Whole lines, arguments included. Body was:\n$body",
            listOf(
                """client.createMailbox(ctx.session, ctx.accountId, "Trash", "trash", ctx.auth)""",
                """client.createMailbox(ctx.session, ctx.accountId, "Trash", null, ctx.auth)""",
            ),
            codeLinesNaming(body, "createMailbox("),
        )
    }

    @Test fun `a trash learned by name or created is written back into the context`() {
        assertEquals(
            "a name match must be cached into the role map, or every message of a bulk delete " +
                "re-runs the whole resolution. Whole line: the map must gain `trash`, and the " +
                "account's own mailboxes must survive the rebuild",
            listOf(
                "context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, " +
                    """ctx.rolesToMailboxId + ("trash" to byName), ctx.mailboxes)""",
            ),
            codeLinesNaming(bodyOf("trashMailboxId"), "context = Context("),
        )
        assertEquals(
            "⛔ and a CREATED folder above all: without this line every message of a bulk delete " +
                "tries to create its own Trash and the account fills up with them",
            listOf(
                "context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, " +
                    """ctx.rolesToMailboxId + ("trash" to id), ctx.mailboxes)""",
            ),
            codeLinesNaming(bodyOf("createTrashFolder"), "context = Context("),
        )
    }

    @Test fun `the creation's last resort re-reads the server, never the global cache`() {
        val body = bodyOf("createTrashFolder")
        assertEquals(
            "a server that refuses both creations may already hold a bin: re-read THIS account's " +
                "mailboxes and match the role, then the name — over the re-read list `mbs`, never " +
                "the global cache. Whole lines. Body was:\n$body",
            listOf(
                "val mbs = runCatching { client.getMailboxes(ctx.session, ctx.accountId, ctx.auth) }.getOrDefault(emptyList())",
                """mbs.firstOrNull { it.role == "trash" }?.id""",
                "?: trashFolderByName(mbs)",
            ),
            codeLinesNaming(body, "mbs"),
        )
    }
}
