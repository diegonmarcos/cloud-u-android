package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, for the reason [DestroyChecksTheFolderWiringTest]
 */
class DeleteAllResolvesOrCreatesTrashWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** Every CODE line of [text] — comment lines and blanks dropped, trailing comments cut,
     *  whitespace normalised — so prose can neither satisfy a rule nor break one, and the
     *  assertions compare WHOLE lines (`contains` is blind to anything appended to a line). */
    private fun codeLines(text: String): List<String> =
        text.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .map { withoutTrailingComment(it).replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }

    /** [line] without its trailing `//` comment — string-aware, so a `"http://…"` is not cut. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun codeLinesNaming(text: String, needle: String): List<String> =
        codeLines(text).filter { needle in it }

    @Test fun `the whole body is these lines, and nothing else decides`() {
        val body = bodyOf("deleteAll")
        assertEquals(
            "⛔ EVERY line of deleteAll, in order. The bin is resolved the way delete() finds it " +
                "— role then creation on IMAP, role-then-by-name then creation on JMAP — the only " +
                "ids that fail are those whose SOURCE folder cannot be read, the already-in-Trash " +
                "branch still drops the rows locally, and imapMoveGroup still gets the frozen " +
                "numbering of ITS folder (#99). Body was:\n$body",
            listOf(
                "{",
                "if (emailIds.isEmpty()) return BulkResult.EMPTY",
                "unlistFromTrashPurge(credentials.id, emailIds)",
                "if (settings?.markReadOnDelete?.first() == true) markSelectionRead(credentials, emailIds)",
                "if (credentials.protocol == MailProtocol.IMAP) {",
                "val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()",
                """val trash = imapRoleFolder(credentials, "trash") ?: run { imap.createFolder(credentials, "Trash"); "Trash" }""",
                "emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->",
                "when {",
                "source == null -> failed += ids",
                "source == trash -> deleteFromCacheAndIndex(credentials.id, ids)",
                "else -> imapMoveGroup(credentials, source, ids, trash, succeeded, failed, expectedUidValidity[source])",
                "}",
                "}",
                "return BulkResult(succeeded, failed, dest = trash)",
                "}",
                "val ctx = connect(credentials)",
                "val trash = trashMailboxId(ctx) ?: createTrashFolder(ctx)",
                "return jmapMoveAll(ctx, emailIds, trash)",
                "}",
            ),
            codeLines(body),
        )
    }

    @Test fun `an absent bin no longer fails the batch`() {
        val body = bodyOf("deleteAll")
        assertEquals(
            "⛔ the ONLY reason an id may be failed here is that its source folder cannot be " +
                "read — an unparsable IMAP id names no folder to move from. `trash == null` back " +
                "in that condition is the defect itself: twenty selected messages, twenty " +
                "failures, nothing moved, on an account where deleting them ONE BY ONE works. " +
                "Whole line. Body was:\n$body",
            listOf("source == null -> failed += ids"),
            codeLinesNaming(body, "failed += "),
        )
        assertEquals(
            "…and no line of this body may test the bin for absence at all: both protocols " +
                "resolve-or-create, so there is no null left to branch on. Body was:\n$body",
            emptyList<String>(),
            codeLines(body).filter { "trash == null" in it || "trash != null" in it },
        )
    }

    @Test fun `nothing returns early for want of a bin`() {
        val body = bodyOf("deleteAll")
        assertEquals(
            "⛔ the JMAP branch used to answer `?: return BulkResult(emptySet(), emailIds." +
                "toSet())` — the whole selection reported failed. The only BulkResult this may " +
                "return are the empty-input guard and the IMAP branch's own result; the JMAP " +
                "branch returns through jmapMoveAll. Whole lines. Body was:\n$body",
            listOf(
                "if (emailIds.isEmpty()) return BulkResult.EMPTY",
                "return BulkResult(succeeded, failed, dest = trash)",
            ),
            codeLinesNaming(body, "BulkResult"),
        )
    }

    @Test fun `the resolution is not wrapped in a runCatching`() {
        val body = bodyOf("deleteAll")
        // Offline, resolving the bin goes over the socket and throws: the batch fails and NOTHING
        // is created on the user's server. Swallowed, the throw would become a null, and a null
        // is what the two branches above no longer know how to refuse — a swallowed IMAP failure
        // would move messages onto a folder name that was never created.
        assertEquals(
            "no runCatching may stand in deleteAll: a resolution that fails must take the batch " +
                "down, not be turned into a silent fallback. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "runCatching"),
        )
    }

    @Test fun `the bulk delete never falls back on All Mail`() {
        // Same reason as the single delete: "all mail" is an acceptable archive and not an
        // acceptable bin — the messages stay in the list, so the batch would look like a no-op.
        val body = bodyOf("deleteAll")
        assertEquals(
            "deleteAll must never route a delete to the All-Mail folder. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, """rolesToMailboxId["all"]"""),
        )
        assertEquals(
            "…nor read the role map inline again: the pair trashMailboxId/createTrashFolder is " +
                "the one way this repository finds a bin to delete into. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "rolesToMailboxId["),
        )
    }
}
