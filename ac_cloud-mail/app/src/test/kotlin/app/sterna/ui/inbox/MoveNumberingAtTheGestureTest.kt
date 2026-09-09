package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class MoveNumberingAtTheGestureTest {

    // -- the three selection gestures ----------------------------------------------------------

    @Test fun `the bulk delete freezes the numbering before it delegates`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "deleteSelected() must read the numbering once, over the WHOLE selection, at the top " +
                "of the gesture. Body was:\n$body",
            listOf("val numbering = selectionNumbering(keys)"),
            codeLinesNaming(body, "selectionNumbering("),
        )
        assertEquals(
            "and hand the account's own share of it to the batch — `deleteAll` is a MOVE to the " +
                "Trash, and it is the most ordinary gesture in the app. Body was:\n$body",
            listOf(") { c, batch -> repo.deleteAll(c, batch, numbering[c.id].orEmpty()) }"),
            codeLinesNaming(body, "repo.deleteAll("),
        )
        assertReadBeforeDelegating(body, "deleteSelected")
    }

    @Test fun `the bulk archive freezes the numbering before it delegates`() {
        val body = body(INBOX_VIEW_MODEL, "archiveSelected")
        assertEquals(
            "archiveSelected() must read the numbering at the gesture. ⛔ It cannot be an " +
                "expression body any more: reading a numbering is a suspending call, and an " +
                "expression body has nowhere to put it. Body was:\n$body",
            listOf("val numbering = selectionNumbering(keys)"),
            codeLinesNaming(body, "selectionNumbering("),
        )
        assertEquals(
            "and hand the account's own share of it to the batch. Body was:\n$body",
            listOf(") { c, ids -> repo.archiveAll(c, ids, numbering[c.id].orEmpty()) }"),
            codeLinesNaming(body, "repo.archiveAll("),
        )
        assertEquals(
            "and it must act on the keys it captured BEFORE the launch: `bulkBatched` reads " +
                "`_selectedKeys.value` when it has none, and the selection may already be cleared " +
                "by then. Body was:\n$body",
            listOf(
                "val keys = _selectedKeys.value",
                "val numbering = selectionNumbering(keys)",
                "keys = keys,",
            ),
            codeLinesNaming(body, "keys"),
        )
        assertReadBeforeDelegating(body, "archiveSelected")
    }

    @Test fun `the bulk move freezes the numbering before it delegates`() {
        val body = body(INBOX_VIEW_MODEL, "moveSelectedTo")
        assertEquals(
            "moveSelectedTo() must read the numbering of the MOVABLE subset — the messages of " +
                "another account are not moved and have no folder here to oppose. Body was:\n$body",
            listOf("val numbering = selectionNumbering(movable)"),
            codeLinesNaming(body, "selectionNumbering("),
        )
        assertEquals(
            "and hand the account's own share of it to the batch. Body was:\n$body",
            listOf(") { c, batch -> repo.moveAllToMailbox(c, batch, targetMailboxId, numbering[c.id].orEmpty()) }"),
            codeLinesNaming(body, "repo.moveAllToMailbox("),
        )
        assertReadBeforeDelegating(body, "moveSelectedTo")
    }

    /**
     * The two spam gestures. On IMAP `reportSpamAll` / `notSpamAll` are `moveAllToMailbox` under
     */
    @Test fun `the spam gestures freeze the numbering before they delegate`() {
        mapOf(
            "reportSpamSelected" to "bulkBatched(keys = keys) { c, ids -> repo.reportSpamAll(c, ids, numbering[c.id].orEmpty()) }",
            "notSpamSelected" to "bulkBatched(keys = keys) { c, ids -> repo.notSpamAll(c, ids, numbering[c.id].orEmpty()) }",
        ).forEach { (name, delegation) ->
            val body = body(INBOX_VIEW_MODEL, name)
            assertEquals(
                "$name() must read the numbering at the gesture. Body was:\n$body",
                listOf("val numbering = selectionNumbering(keys)"),
                codeLinesNaming(body, "selectionNumbering("),
            )
            assertEquals(
                "and hand the account's own share of it to the batch. Body was:\n$body",
                listOf(delegation),
                codeLinesNaming(body, "bulkBatched("),
            )
            assertEquals(
                "and clear the selection SYNCHRONOUSLY, at the tap: `bulkBatched` clears it too, " +
                    "but a suspension later — and a bar left up is a second tap firing the same " +
                    "batch again on the same keys. Body was:\n$body",
                listOf("clearSelection()"),
                codeLinesNaming(body, "clearSelection"),
            )
            assertReadBeforeDelegating(body, name)
        }
    }

    /** The same synchronous clear on the archive, for the same reason — it is the gesture that
     *  stopped being synchronous when the freeze was added to it. */
    @Test fun `the archive clears the selection at the tap, not a suspension later`() {
        val body = body(INBOX_VIEW_MODEL, "archiveSelected")
        assertEquals(
            "archiveSelected() must clear the selection before the suspending read. Body was:\n$body",
            listOf("clearSelection()"),
            codeLinesNaming(body, "clearSelection"),
        )
        assertTrue(
            "…and before it, not after: the whole point is that nothing suspends in between. " +
                "Body was:\n$body",
            body.indexOf("clearSelection()") < body.indexOf("selectionNumbering("),
        )
    }

    private fun assertReadBeforeDelegating(body: String, name: String) = assertTrue(
        "$name must freeze the numbering BEFORE handing the batch over: read inside the lambda " +
            "it answers whatever a background refresh has recorded by the time the batch runs, " +
            "which is the number that licenses the move the guard exists to refuse. Body was:\n$body",
        body.indexOf("selectionNumbering(") < body.indexOf("bulkBatched("),
    )

    // -- the shared read -----------------------------------------------------------------------

    /**
     * The helper itself. It must ask the REPOSITORY (which answers null on JMAP, where an id
     */
    @Test fun `the numbering is read per source folder, from the repository, off the id`() {
        val body = body(INBOX_VIEW_MODEL, "selectionNumbering")
        assertEquals(
            "the read must be the repository's protocol-guarded one. Body was:\n$body",
            listOf("if (folder !in folders) folders[folder] = repo.recordedUidValidity(credentials, folder)"),
            codeLinesNaming(body, "repo."),
        )
        assertEquals(
            "and the folder must come from the id, the same way the repository groups by it. " +
                "Body was:\n$body",
            listOf("val folder = ImapMailService.mailboxOf(key.emailId) ?: return@forEach"),
            codeLinesNaming(body, "mailboxOf("),
        )
        assertEquals(
            "and the map must be keyed by ACCOUNT first: the same mailbox id in two accounts is " +
                "two different folders with two different numberings, and a unified selection " +
                "spans both. Body was:\n$body",
            listOf("val folders = byAccount.getOrPut(credentials.id) { mutableMapOf() }"),
            codeLinesNaming(body, "getOrPut"),
        )
    }

    /** And nowhere else. A second call, inside `bulkBatched` or inside a batch lambda, is a
     *  read at execution — see this class's header. */
    @Test fun `the numbering is read at the five gestures and nowhere else`() {
        assertEquals(
            "selectionNumbering is declared once and called from exactly the five bulk gestures " +
                "— delete, archive, move, report-spam and not-spam. Every one of them is a UID " +
                "MOVE on IMAP.",
            listOf(
                "private suspend fun selectionNumbering(keys: Collection<EmailKey>): Map<String, Map<String, Long?>> {",
                "val numbering = selectionNumbering(keys)",
                "val numbering = selectionNumbering(keys)",
                "val numbering = selectionNumbering(movable)",
                "val numbering = selectionNumbering(keys)",
                "val numbering = selectionNumbering(keys)",
            ),
            codeLinesNaming(INBOX_VIEW_MODEL.readText(), "selectionNumbering("),
        )
    }

    // -- the sender screen ---------------------------------------------------------------------

    /**
     * "Delete every message from this sender" is the only bulk path in the app that goes through a
     */
    @Test fun `the sender delete freezes the numbering where it reads the ids`() {
        val body = body(SENDER_VIEW_MODEL, "askDelete")
        assertEquals(
            "askDelete must freeze one numbering per source folder, off the ids it has just " +
                "read — not off the cached rows' mailboxId, which is not what the repository " +
                "routes the batch by. Body was:\n$body",
            listOf("val numbering = ids.mapNotNull { ImapMailService.mailboxOf(it) }.distinct()"),
            codeLinesNaming(body, "ImapMailService.mailboxOf("),
        )
        assertEquals(
            "and the number itself must come from the repository's protocol-guarded read, once " +
                "per distinct source folder. Body was:\n$body",
            listOf(".associateWith { repo.recordedUidValidity(credentials, it) }"),
            codeLinesNaming(body, "recordedUidValidity("),
        )
        assertEquals(
            "and it must travel on the pending confirmation, with the ids. Body was:\n$body",
            listOf("_state.value = _state.value.copy(pending = PendingDelete(sender, ids, numbering))"),
            codeLinesNaming(body, "PendingDelete("),
        )
    }

    /** And the confirmation must READ NOTHING: it takes what was frozen, exactly as it takes
     *  the ids. A `?:` fallback here "for a confirmation opened before the field existed" is the
     *  defect back, in the shape that reads as harmless. */
    @Test fun `the sender confirmation opposes what was frozen and reads no numbering itself`() {
        val body = body(SENDER_VIEW_MODEL, "confirmDelete")
        assertEquals(
            "confirmDelete must hand the delete the numbering the confirmation carries. " +
                "Body was:\n$body",
            listOf("val result = runCatching { repo.deleteAll(credentials, ids, pending.numbering) }"),
            codeLinesNaming(body, "repo.deleteAll("),
        )
        assertEquals(
            "and must not look a numbering up for itself, under ANY name: what it could read is " +
                "the number recorded AFTER the renumbering it exists to refuse. Body was:\n$body",
            listOf("val result = runCatching { repo.deleteAll(credentials, ids, pending.numbering) }"),
            codeLinesNaming(body, Regex("(?i)(numbering|uidvalidity)")),
        )
    }

    // -- the closed list of callers ------------------------------------------------------------

    /**
     * THE BLIND SPOT that got the first delivery rejected: every rule above looks at a gesture
     */
    @Test fun `every caller of a bulk mover in the app hands over a frozen numbering`() {
        val calls = appSources().flatMap { file ->
            codeLinesNaming(file.readText(), Regex("""\brepo\.(archiveAll|moveAllToMailbox|deleteAll|reportSpamAll|notSpamAll)\("""))
        }.sorted()
        assertEquals(
            "a bulk mover is called somewhere that freezes no numbering — on IMAP every one of " +
                "these is a UID MOVE, and one that opposes nothing is REFUSED, not risked. Freeze " +
                "at the gesture (selectionNumbering, or the ids' own folders) and pass the map.",
            listOf(
                ") { c, batch -> repo.deleteAll(c, batch, numbering[c.id].orEmpty()) }",
                ") { c, batch -> repo.moveAllToMailbox(c, batch, targetMailboxId, numbering[c.id].orEmpty()) }",
                ") { c, ids -> repo.archiveAll(c, ids, numbering[c.id].orEmpty()) }",
                "bulkBatched(keys = keys) { c, ids -> repo.notSpamAll(c, ids, numbering[c.id].orEmpty()) }",
                "bulkBatched(keys = keys) { c, ids -> repo.reportSpamAll(c, ids, numbering[c.id].orEmpty()) }",
                "val result = runCatching { repo.deleteAll(credentials, ids, pending.numbering) }",
            ),
            calls,
        )
    }

    // -- reading the sources -------------------------------------------------------------------
    // Same readers as BulkSelectionWiringTest, deliberately duplicated rather than shared: a
    // helper these lints agree on is a helper a single edit can loosen for all of them at once.

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

    /** The same, for a needle that has to catch SYNONYMS: a rule pinned to one spelling is a rule
     *  a rename walks past. Used where the forbidden thing is "a numbering read, under any name". */
    private fun codeLinesNaming(body: String, needle: Regex): List<String> =
        codeLinesOf(body).filter { needle.containsMatchIn(it) }

    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** Every Kotlin source of the app module — what a closed list of CALLERS has to be read over:
     *  a rule that looks only at the files it already knows about cannot see a new caller. */
    private fun appSources(): List<File> =
        File(root, APP_SOURCES).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The lines of [file] that are code, with comments taken off. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /** The declaration of `fun`/`val` [name] in [file] and its body, as text. Fails loudly when the
     *  declaration is not found — a rename must break these rules rather than satisfy them. */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val SENDER_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/sender/MailBySenderViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val SENDER_VIEW_MODEL: File by lazy { File(root, SENDER_VIEW_MODEL_PATH) }
    }
}
