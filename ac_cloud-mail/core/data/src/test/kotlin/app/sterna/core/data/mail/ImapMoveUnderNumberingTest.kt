package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the move's numbering guard is PLUGGED IN — the source-text half of
 */
class ImapMoveUnderNumberingTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, whitespace normalised. Whole
     *  lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

    /**
     * The same, for a needle that has to catch SYNONYMS.
     */
    private fun codeLinesNaming(body: String, needle: Regex): List<String> =
        codeLinesOf(body).filter { needle.containsMatchIn(it) }

    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * Any mention of a numbering, under any spelling: `recordedUidValidity`, `recordedNumbering`,
     */
    private val anyNumbering = Regex("(?i)(numbering|validity|frozen|recorded)")

    // ---- the three single-message movers ------------------------------------------------------

    /**
     * `archive` and `moveToMailbox` each read the numbering ON THE SPOT, for the folder the message
     */
    @Test fun `the single-message movers oppose the source folder's recorded numbering`() {
        val expected = mapOf(
            "archive" to "val landed = imap.move(credentials, mb, uid, dest, recordedUidValidity(credentials, mb))",
            "moveToMailbox" to
                "val landed = imap.move(credentials, mb, uid, targetMailboxId, recordedUidValidity(credentials, mb))",
        )
        expected.forEach { (function, line) ->
            val body = bodyOf(function)
            assertEquals(
                "$function must oppose the numbering recorded for the SOURCE folder to what the " +
                    "server states in the SELECT it is about to move under. Without the argument " +
                    "the call still compiles and still moves — it simply confirms nothing. " +
                    "Body was:\n$body",
                listOf(line),
                codeLinesNaming(body, "imap.move("),
            )
        }
    }

    /**
     * `delete` IS THE BIN, AND IT IS ALSO THE SECOND HALF OF A MOVE BETWEEN ACCOUNTS (#189).
     */
    @Test fun `the bin opposes the stamp it was handed, falling back on the folder's record`() {
        val body = bodyOf("delete")
        assertEquals(
            "delete must oppose the numbering it was HANDED — the stamp of the tick, when a " +
                "cross-account move hands one — and fall back on the source folder's record when " +
                "it was handed none. Dropping the `expectedUidValidity ?:` still compiles and " +
                "still bins: it simply confirms nothing minutes after the gesture. Dropping the " +
                "`?: recordedUidValidity(...)` refuses every swipe-to-delete on IMAP. " +
                "Body was:\n$body",
            listOf("val landed = imap.move(credentials, mb, uid, trash, numbering ?: recordedUidValidity(credentials, mb))"),
            codeLinesNaming(body, "imap.move("),
        )
        assertEquals(
            "and delete must mention a numbering on exactly these five lines, under ANY name: the " +
                "three that turn what the caller froze into what goes on the wire (a refusal " +
                "included), the one that hands that number to the wire, and the one that FREEZES " +
                "what the move itself reported about the Trash (not a read — the server stated it " +
                "in the same response as the uid, and a UID MOVE never SELECTs its destination). " +
                "Any other is a second read at execution. Body was:\n$body",
            listOf(
                "val numbering = when (val verdict = numberingToOppose(frozen)) {",
                "NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mb, null)",
                "is NumberingToOppose.Select -> verdict.stamp",
                "val landed = imap.move(credentials, mb, uid, trash, numbering ?: recordedUidValidity(credentials, mb))",
                "lastImapMove[emailId] = ImapLoc(trash, it, landed.destinationUidValidity)",
            ),
            codeLinesNaming(body, anyNumbering),
        )
        val signature = signatureOf("delete")
        assertEquals(
            "and it takes what was frozen LAST and optional, AS A TYPE: the swipe, the " +
                "notification action and the conversation delete all carry NothingFrozen by " +
                "default, which must keep meaning 'fall back on the record'. A `Long?` here is " +
                "the defect itself — it cannot tell that default apart from `Frozen(null)`, a " +
                "ticked row with nothing to oppose, which must be refused. Signature was:\n$signature",
            listOf("frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,"),
            codeLinesNaming(signature, anyNumbering),
        )
    }

    /**
     * The undo of a move is a move, and it is the one that puts the message BACK: it selects the
     */
    @Test fun `the restore opposes the frozen numbering of the folder it takes the mail out of`() {
        val body = bodyOf("restoreAll")
        assertEquals(
            "restoreAll must oppose the CURRENT folder's numbering — the one it SELECTs — not " +
                "the source it is restoring to. ⛔ And it must NAME THE TWO FOLDERS IN THIS CALL, " +
                "as `route.currentFolder` / `route.sourceFolder`: they are interchangeable " +
                "Strings, so swapping them compiles, and it then SELECTs the inbox while moving " +
                "Trash-side UIDs into the Trash — live inbox mail thrown away wherever the two " +
                "folders share a numbering. Laundered through `val currentFolder = " +
                "route.currentFolder` the swap touches no watched line and the whole suite stays " +
                "green; named here, it changes this pinned line. Body was:\n$body",
            listOf(
                "imap.moveBatch(credentials, route.currentFolder, route.uids, route.sourceFolder, " +
                    "route.frozenUidValidity)",
            ),
            codeLinesNaming(body, "imap.moveBatch("),
        )
        assertEquals(
            "and the re-fetch of the restored rows must read them from the folder they went home " +
                "to, named the same way. `route.currentFolder` here fetches UIDs of one folder in " +
                "another: nothing comes back, the restored rows are never re-cached nor marked " +
                "`recentLocalMoves`, they vanish from the list until the next full sync — and the " +
                "notifier may then announce them as new mail. Body was:\n$body",
            listOf("runCatching { imap.fetchByUids(credentials, route.sourceFolder, newUids) }"),
            codeLinesNaming(body, "imap.fetchByUids("),
        )
        assertEquals(
            "and everything the move back REPORTS must be read on these two lines, whole: " +
                "`it.first in moved.uids` credits the ids that came home (turn it into " +
                "`moved.uids.values` and nothing is ever credited — 'restore failed' on a restore " +
                "that worked), and `.values` is where the mail is NOW (turn it into `.keys` and " +
                "the re-fetch asks for the UIDs it has just left). Neither mutation touches any " +
                "other line of this function. Body was:\n$body",
            listOf(
                "restored += route.messages.filter { it.first in moved.uids }.map { it.second }",
                "val newUids = moved.uids.values.toList()",
            ),
            codeLinesNaming(body, "moved."),
        )
        assertEquals(
            "restoreAll must mention a numbering on exactly these two lines: the routing decision " +
                "it EXECUTES, and the move that hands the frozen number to the wire. Any other, " +
                "under ANY name, is a read at execution — and here a read is not merely stale, it " +
                "answers NULL for every folder the user never opened, which refuses every Undo of " +
                "a delete on such an account. Body was:\n$body",
            listOf(
                "val routes = UidValidity.imapUndoRoutes(targets.map { it.emailId to it.sourceMailboxId }) " +
                    "{ lastImapMove[it] }",
                "imap.moveBatch(credentials, route.currentFolder, route.uids, route.sourceFolder, " +
                    "route.frozenUidValidity)",
            ),
            codeLinesNaming(body, anyNumbering),
        )
    }

    /**
     * THE UNDO ABSORBS A REFUSED MOVE-BACK, AND THE APP LAYER DEPENDS ON IT WITHOUT SAYING SO.
     */
    @Test fun `a move-back that is refused stops here, and never reaches the banner`() {
        val body = bodyOf("restoreAll")
        assertEquals(
            "restoreAll no longer wraps the move-back it sends: a refusal on ONE route now " +
                "travels up to InboxViewModel.undo()'s onFailure, which draws the red " +
                "SyncErrorBanner over the list — the whole Undo reported as failed, on top of " +
                "the routes that did come home. Body was:\n$body",
            listOf("val moved = runCatching {"),
            codeLinesNaming(body, "val moved ="),
        )
        assertEquals(
            "and both protocols must absorb it the same way — one route (or one folder's group) " +
                "gives up, the others still travel, and the ids that did not come home are the " +
                "RETURN VALUE. Body was:\n$body",
            listOf("}.getOrNull() ?: return@forEach", "}.getOrNull() ?: return@forEach"),
            codeLinesNaming(body, "getOrNull()"),
        )
    }

    /**
     * WHAT IS FROZEN, at the four sites that write an Undo landing: the numbering the move's own
     */
    @Test fun `every undo landing freezes the numbering COPYUID stated for the destination`() {
        val source = DaoQuerySource.mailSource("MailRepository")
        assertEquals(
            "an Undo landing was written with something other than the destination numbering the " +
                "move itself reported, or a new site touches `lastImapMove` unseen. That value is " +
                "the ONLY statement of the destination's numbering the app ever gets: a UID MOVE " +
                "does not SELECT its destination.",
            listOf(
                "lastImapMove[emailId] = ImapLoc(dest, it, landed.destinationUidValidity)",
                "lastImapMove[emailId] = ImapLoc(targetMailboxId, it, landed.destinationUidValidity)",
                "lastImapMove[id] = ImapLoc(dest, it, landed.destinationUidValidity)",
                "val routes = UidValidity.imapUndoRoutes(targets.map { it.emailId to it.sourceMailboxId }) " +
                    "{ lastImapMove[it] }",
                "lastImapMove[emailId] = ImapLoc(trash, it, landed.destinationUidValidity)",
            ),
            codeLinesNaming(source, "lastImapMove["),
        )
    }

    // ---- the batch mover, and the three bulk gestures that feed it ----------------------------

    /**
     * On the batch path the numbering ARRIVES; it is not read here. The bulk gestures are the ones
     */
    @Test fun `the batch move takes the numbering it is handed, and reads none of its own`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must hand `moveBatch` the numbering it received. Body was:\n$body",
            listOf("runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest, expectedUidValidity) }"),
            codeLinesNaming(body, "imap.moveBatch("),
        )
        assertEquals(
            "imapMoveGroup must mention a numbering on exactly these two lines — the one that " +
                "hands the parameter to the wire, and the one that FREEZES what the move itself " +
                "reported about the destination (not a read: the server stated it in the same " +
                "response as the UIDs). Any other, under ANY name (`recordedUidValidity`, its " +
                "synonym `recordedNumbering`, a shadowing `val expectedUidValidity = … ?: …`), is " +
                "a read AT EXECUTION: it answers the number a background pass recorded AFTER the " +
                "renumbering, the guard compares it with itself, concludes SAME, and the UID MOVE " +
                "goes out on UIDs that name other, live messages. Body was:\n$body",
            listOf(
                "runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest, expectedUidValidity) }",
                "lastImapMove[id] = ImapLoc(dest, it, landed.destinationUidValidity)",
            ),
            codeLinesNaming(body, anyNumbering),
        )
        assertEquals(
            "and it must READ no numbering at all — the closed list above names two lines, and " +
                "neither may become a lookup. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, Regex("recorded(UidValidity|Numbering)")),
        )
    }

    /**
     * And the refusal must feed `failed` WITHOUT a catch of its own. `ImapNumberingUnconfirmed`
     */
    @Test fun `a refused batch move fails its ids instead of counting them moved`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "the whole group must land in `failed` when the move does not go out. Body was:\n$body",
            listOf(".onFailure { failed += uidToId.values }"),
            codeLinesNaming(body, ".onFailure"),
        )
        assertEquals(
            "and nothing may be counted as succeeded outside the success branch — nor, INSIDE " +
                "it, beyond what the move proved left the source (`MoveCreditsOnlyWhatLeftTheSource" +
                "Test`): an OK says the command ran, and a UID MOVE on a uid that is already gone " +
                "is a no-op the server accepts. Body was:\n$body",
            listOf("succeeded += outcome.gone"),
            codeLinesNaming(body, "succeeded +="),
        )
    }

    /**
     * The three bulk gestures, each handing its group's own numbering down. The key is the
     */
    @Test fun `each bulk gesture hands the batch its own source folder's numbering`() {
        val expected = mapOf(
            "archiveAll" to "else -> imapMoveGroup(credentials, source, ids, dest, succeeded, failed, expectedUidValidity[source])",
            "moveAllToMailbox" to
                "else -> imapMoveGroup(credentials, source, ids, targetMailboxId, succeeded, failed, expectedUidValidity[source])",
            "deleteAll" to "else -> imapMoveGroup(credentials, source, ids, trash, succeeded, failed, expectedUidValidity[source])",
        )
        expected.forEach { (function, line) ->
            val body = bodyOf(function)
            assertEquals(
                "$function must hand imapMoveGroup the numbering frozen for THAT source folder. " +
                    "Body was:\n$body",
                listOf(line),
                codeLinesNaming(body, "imapMoveGroup("),
            )
            assertEquals(
                "$function must mention a numbering on exactly that one line and no other, under " +
                    "ANY name: it is called from a ViewModel that froze one at the gesture, and " +
                    "any read here answers the number a refresh recorded AFTER the renumbering. " +
                    "Body was:\n$body",
                listOf(line),
                codeLinesNaming(body, anyNumbering),
            )
        }
    }

    /**
     * THE BLIND SPOT that got the first delivery rejected: the rules above each look at a mover
     */
    @Test fun `every caller of a bulk mover inside the repository hands the numbering on`() {
        val calls = codeLinesNaming(
            DaoQuerySource.mailSource("MailRepository"),
            // Not preceded by a dot: `emailBodyDao.deleteAll()` is a table wipe, not a mover.
            Regex("""(?<![.\w])(archiveAll|moveAllToMailbox|deleteAll|reportSpamAll|notSpamAll)\("""),
        ).filterNot { it.startsWith("suspend fun ") }
        assertEquals(
            "a bulk mover is called inside MailRepository without handing its numbering on. On " +
                "IMAP every one of them ends in a UID MOVE, and one that opposes nothing is " +
                "REFUSED — the gesture fails whole, it does not degrade.",
            listOf(
                "return moveAllToMailbox(credentials, emailIds, junk, expectedUidValidity)",
                "return moveAllToMailbox(credentials, emailIds, inbox, expectedUidValidity)",
            ),
            calls,
        )
    }

    /**
     * The parameter itself, pinned on each declaration — the map must stay a map keyed by source
     */
    @Test fun `the bulk gestures take a per-source-folder numbering, defaulting to nothing`() {
        listOf("archiveAll", "moveAllToMailbox", "deleteAll", "reportSpamAll", "notSpamAll").forEach { function ->
            val signature = signatureOf(function)
            assertEquals(
                "$function must take the numbering as a map keyed by SOURCE FOLDER: one number " +
                    "for a selection spanning two folders is one of them opposed to the other. " +
                    "The default is 'nothing frozen', which licenses nothing — any other default " +
                    "is a licence. Signature was:\n$signature",
                listOf("expectedUidValidity: Map<String, Long?> = emptyMap(),"),
                codeLinesNaming(signature, "expectedUidValidity"),
            )
        }
        val signature = signatureOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup takes ONE folder's number, and takes it without a default: it is " +
                "private, its three callers all have one to hand, and a default here is a call " +
                "site that silently stops opposing anything. Signature was:\n$signature",
            listOf("expectedUidValidity: Long?,"),
            codeLinesNaming(signature, "expectedUidValidity"),
        )
    }

    /** The declaration lines of [function] — from its `fun` line to the line its body opens on.
     *  These signatures are written one parameter per line precisely so this can read them. */
    private fun signatureOf(function: String): String {
        val lines = DaoQuerySource.mailSource("MailRepository").lines()
        val start = lines.indexOfFirst { Regex("""\bfun $function\(""").containsMatchIn(it) }
        check(start >= 0) { "MailRepository declares no '$function' — did it get renamed?" }
        val end = (start until lines.size).first { lines[it].trimEnd().endsWith("{") }
        return lines.subList(start, end + 1).joinToString("\n")
    }
}
