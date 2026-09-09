package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, as [DraftAddressingWiringTest] does and for the
 */
class LocalDraftSaveWiringTest {

    private fun bodyOf(function: String): String = DaoQuerySource.mailFunctionBody("MailRepository", function)

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    /**
     * The parameter list of `fun [function]` in the `mail` file [file], comments cut and lines
     */
    private fun declarationOf(file: String, function: String): List<String> {
        val lines = DaoQuerySource.mailSource(file).lines()
        val at = lines.indexOfFirst { Regex("""\bfun\s+$function\s*\(""").containsMatchIn(it) }
        assertTrue("$file declares no 'fun $function(' any more — was it renamed?", at >= 0)
        val closes = lines.drop(at + 1).indexOfFirst { it.trimStart().startsWith(")") }
        assertTrue("the parameter list of $file.$function never closes", closes >= 0)
        return codeOf(lines.subList(at, at + closes + 2).joinToString("\n"))
    }

    @Test fun `saveDraft itself touches no network at all`() {
        // The point of the volet, stated where it can rot: every byte that leaves goes through
        // uploadDraft, which is reached only after the row is durable. A single `imap.` or
        // `connect(` back in this body is the old order restored.
        val body = codeOf(bodyOf("saveDraft"))
        val network = body.filter { line ->
            listOf("imap.", "client.", "connect(", "jmapDraftAttachments(", "imapDraftAttachments(")
                .any { it in line }
        }
        assertEquals(
            "MailRepository.saveDraft must reach the network only through uploadDraft, which " +
                "saveDraftLocalFirst calls AFTER the row is written:",
            emptyList<String>(), network,
        )
    }

    @Test fun `the save is assembled out of the functions the tests execute`() {
        val body = codeOf(bodyOf("saveDraft"))
        val at = body.indexOfFirst { it == "val target = localDraftTarget(" }
        assertTrue("MailRepository.saveDraft no longer looks its row up with localDraftTarget", at >= 0)
        assertEquals(
            "⛔ the row a save lands on must be looked up by BOTH keys, with the REAL statements: " +
                "`forServerDraft = { null }`, or dropping the second lookup, mints a fresh row on " +
                "every re-save of an edited server draft — N rows, N Message-IDs, N uploads and N " +
                "attempts to destroy the same original. ⛔ And the row it found is READ OUT, whole: " +
                "`target.existing?.takeIf { … }` compiles, is in no other window of this file, and " +
                "is the one line that carries the stored body to localDraftBodyToWrite — narrowed " +
                "there, a save that lost its body writes an empty one onto the only copy again, and " +
                "saveDraftLocalFirst's rollback goes back to deleting a row a PREVIOUS save wrote:",
            listOf(
                "val target = localDraftTarget(",
                "replacesEmailId,",
                "byId = { localDraftDao.byId(credentials.id, it) },",
                "forServerDraft = { localDraftDao.forServerDraft(credentials.id, it) },",
                ")",
                "val existing = target.existing",
            ),
            body.drop(at).take(6),
        )
        assertEquals(
            "⛔ the server draft to destroy must go through serverDraftReplacedBy: a local id " +
                "handed on reaches destroyDraft, and on IMAP it ends in a UID",
            listOf("val serverReplaced = serverDraftReplacedBy(replacesEmailId)"),
            body.filter { "serverDraftReplacedBy(" in it },
        )
        assertEquals(
            "the whole order — write, stage, upload, and consume only on success — is one function " +
                "so a test can run it",
            listOf("return saveDraftLocalFirst("),
            body.filter { "saveDraftLocalFirst(" in it },
        )
        assertEquals(
            "⛔ the lost-body guard's emptiness term must be HANDED DOWN, never re-derived here " +
                "(#131). `typedBodyIsBlank = body.isBlank()` compiles and reads innocent, and it " +
                "is the defect itself: `body` is the plain-text PROJECTION of the rich body, so a " +
                "bullet tapped on an empty composer makes it `\"- \"`, the guard stands down, and " +
                "the only copy of a local draft is replaced by those two characters. " +
                "`localDraftRow` carries a default for the callers whose body IS the typed text; " +
                "this call site is the one for which that default is wrong.",
            listOf("typedBodyIsBlank = typedBodyIsBlank,"),
            body.filter { it.startsWith("typedBodyIsBlank") },
        )
    }

    @Test fun `the things saveDraftLocalFirst is given are the real store, whole lines`() {
        // `record` is the newest of them and the easiest to drop: a save that fails offline
        // leaves the row for the upload worker, and if the attempt is not written down the row's
        //
        // And two that are about the draft ALREADY saved:
        //  - `existing` is what tells the orchestration whether this save created the row it is
        val body = codeOf(bodyOf("saveDraft"))
        val at = body.indexOfFirst { it == "return saveDraftLocalFirst(" }
        assertTrue("MailRepository.saveDraft no longer calls saveDraftLocalFirst( — follow it", at >= 0)
        assertEquals(
            "the adapters handed to the orchestration are what LocalDraftSaveTest stands in for. " +
                "`upsert = { }`, a stage that returns emptyList() or `record = { _, _ -> }` leave " +
                "every executed test green and do nothing at all on a real phone:",
            listOf(
                "return saveDraftLocalFirst(",
                "row = row,",
                "existing = existing,",
                "nowMillis = System.currentTimeMillis(),",
                "upsert = { localDraftDao.upsert(it) },",
                "stage = { stageLocalDraftAttachments(localDraftDir(it.id), attachments) },",
                "discard = { discardLocalDraft(credentials.id, it.id) },",
                "upload = { saved -> uploadDraft(credentials, saved, attachments) },",
                "record = { saved, attempt ->",
                "localDraftDao.recordAttempt(",
                "credentials.id, saved.id, LocalDraftState.PENDING, attempt.attemptCount, attempt.error,",
                "attempt.atMillis, attempt.notBeforeMillis,",
                ")",
                "},",
                "schedule = { job -> localDraftScheduler?.schedule(job.accountId, job.id, job.delayMillis) },",
                ")",
            ),
            body.drop(at).take(16),
        )
    }

    @Test fun `the booking seam does not suspend, and the signature says so`() {
        // THE guard the whole cancelled path rests on, and it had no witness at all: put
        // `suspend` back on `schedule` and everything stays green, because the harness's fake never
        //
        // WHOLE LINES: `schedule: suspend (LocalDraftUploadJob) -> Unit,` is a LONGER line, so
        // any `contains`-style check would be blind to exactly the mutation this exists for.
        val source = DaoQuerySource.mailSource("LocalDraftSave")
        val at = source.indexOf("internal suspend fun saveDraftLocalFirst(")
        assertTrue("LocalDraftSave no longer declares saveDraftLocalFirst( — renamed?", at >= 0)
        assertEquals(
            "the seams of the save, and which of them may suspend:",
            listOf(
                "internal suspend fun saveDraftLocalFirst(",
                "row: LocalDraftEntity,",
                "existing: LocalDraftEntity?,",
                "nowMillis: Long,",
                "upsert: suspend (LocalDraftEntity) -> Unit,",
                "stage: suspend (LocalDraftEntity) -> List<OutboxAttachment>,",
                "discard: suspend (LocalDraftEntity) -> Unit,",
                "upload: suspend (LocalDraftEntity) -> DraftSaveOutcome,",
                "record: suspend (LocalDraftEntity, LocalDraftAttempt) -> Unit,",
                "schedule: (LocalDraftUploadJob) -> Unit,",
                "): DraftSaveOutcome {",
            ),
            codeOf(source.substring(at, source.indexOf('{', at) + 1)),
        )
    }

    @Test fun `every value the composer hands over is on the row, argument by argument`() {
        // THE mutation that survived the first pass: `bodyIsLossy = bodyIsLossy` → `= false`
        // compiles and leaves the whole suite green. `uploadDraft` reads its verdict off the ROW
        //
        // The row builder is EXECUTED by LocalDraftSaveTest (every argument of localDraftRow lands
        // on the entity, and the entity is what both uploads read). What no JVM test can execute is
        val body = codeOf(bodyOf("saveDraft"))
        val at = body.indexOfFirst { it == "val row = localDraftRow(" }
        assertTrue("MailRepository.saveDraft no longer builds its row with localDraftRow", at >= 0)
        assertEquals(
            "every argument here carries a value from the composer to what goes out on the wire. " +
                "A literal, a default or a condition ANDed onto one of them is a field silently " +
                "dropped from the draft on BOTH protocols, with every executed test still green:",
            listOf(
                "val row = localDraftRow(",
                "accountId = credentials.id,",
                "id = target.id,",
                "messageId = localDraftMessageId(existing) { newMessageId(credentials.username) },",
                "to = recipients,",
                "cc = cc,",
                "bcc = bcc,",
                "subject = subject,",
                "body = body,",
                // …and the styling that belongs to that body (#131). Dropped here, a styled draft
                // is stored as text on both protocols, and the bold is gone at the next reopen.
                "html = html,",
                "fromName = fromName,",
                "fromEmail = fromEmail,",
                "inReplyTo = inReplyTo,",
                "references = references,",
                "replacesEmailId = serverReplaced,",
                "replacesUidValidity = localDraftUidValidity(serverReplaced) { mailboxId ->",
                "mailboxUidValidityDao.recorded(credentials.id, mailboxId)",
                "},",
                "bodyIsLossy = bodyIsLossy,",
                // Beside it, NOT instead of it. The column keeps the disjunction (every destroy
                // guard downstream reads it); this one is the composer's own loss, and it is all
                // the WRITE guard weighs — folded back together, erasing a marked draft's body is
                // refused in silence again.
                "composerBodyWasLost = composerBodyWasLost,",
                // …and the EMPTINESS of what was TYPED, which travels beside it and not
                // inside `body` (#131). `body` is the plain-text projection now, and a bullet on
                "typedBodyIsBlank = typedBodyIsBlank,",
                "requestReceipt = requestReceipt,",
                "existing = existing,",
                "nowMillis = System.currentTimeMillis(),",
                ")",
            ),
            body.drop(at).take(25),
        )
    }

    @Test fun `saveDraft gives the composer's own loss no default, so a new call site cannot forget it`() {
        val declaration = declarationOf("MailRepository", "saveDraft")
        assertTrue(
            "MailRepository.saveDraft must declare `composerBodyWasLost: Boolean` with NO default " +
                "value. A `= false` there is silent: the next save path written (a save on leave, " +
                "an incoming share, a worker) compiles without saying anything, and the first time " +
                "it runs from a composer that came back from a parcel without its body it writes " +
                "that empty body over the ONLY copy of the user's text — no screen says a word, " +
                "and no test in this repository would see it. Declaration was:\n" +
                declaration.joinToString("\n") { "    $it" },
            "composerBodyWasLost: Boolean," in declaration,
        )
    }

    @Test fun `localDraftRow gives it no default either, for the same silence`() {
        val declaration = declarationOf("LocalDraftSave", "localDraftRow")
        assertTrue(
            "localDraftRow must declare `composerBodyWasLost: Boolean` with NO default value. It " +
                "is the argument the write guard weighs, so a `= false` here lets any future " +
                "builder of a row erase the stored body of a draft whose composer lost it, with " +
                "every executed rule of this suite still green. Declaration was:\n" +
                declaration.joinToString("\n") { "    $it" },
            "composerBodyWasLost: Boolean," in declaration,
        )
    }

    @Test fun `the destroy verdict is read off the row, on both protocols`() {
        // The consumer of `bodyIsLossy`. Both branches must ask the ROW — the value the composer
        // set, frozen at save time — and not a literal: `false` at either site authorises the
        // destroy of an original this composer cannot reproduce, on that protocol only.
        assertEquals(
            "both uploads must weigh the row's own bodyIsLossy before finishDraftSave may destroy " +
                "the original (#63)",
            listOf(
                "attachments.size, parts.size, row.bodyIsLossy,",
                "attachments.size, blobs.size, row.bodyIsLossy,",
            ),
            codeLinesNaming(bodyOf("uploadDraft"), "bodyIsLossy"),
        )
    }

    @Test fun `the Message-ID the row froze is the one the row builder is given`() {
        assertEquals(
            "the row's Message-ID must come from localDraftMessageId(existing) — a fresh mint here " +
                "gives the same draft a new name on every save, which is what main did",
            listOf("messageId = localDraftMessageId(existing) { newMessageId(credentials.username) },"),
            codeLinesNaming(bodyOf("saveDraft"), "messageId ="),
        )
    }

    @Test fun `the appended draft is built from the row and not from the arguments`() {
        // The one line the whole Message-ID story rests on. `outgoing(credentials, …)` here — the
        // shape main had — mints a fresh id inside and the row's name never reaches the server.
        assertEquals(
            "the IMAP APPEND must be built by localDraftOutgoing from the row that was just written",
            listOf("localDraftOutgoing(row, credentials.username, parts, System.currentTimeMillis()),"),
            codeLinesNaming(bodyOf("uploadDraft"), "localDraftOutgoing("),
        )
        assertEquals(
            "and nothing on the draft path may go back to outgoing(), which mints its own",
            emptyList<String>(),
            codeLinesNaming(bodyOf("uploadDraft"), "outgoing(").filterNot { "localDraftOutgoing(" in it },
        )
    }

    @Test fun `the UIDVALIDITY is read for this account, at save time`() {
        assertEquals(
            "the numbering must be frozen while it is still true — the row may wait weeks, and the " +
                "upload's last act is a delete addressed by UID (#99)",
            listOf(
                "replacesUidValidity = localDraftUidValidity(serverReplaced) { mailboxId ->",
                "mailboxUidValidityDao.recorded(credentials.id, mailboxId)",
            ),
            codeOf(bodyOf("saveDraft")).filter { "localDraftUidValidity(" in it || "mailboxUidValidityDao" in it },
        )
    }

    @Test fun `a local draft's files are its own, never the outbox's`() {
        // Read over the WHOLE file, not one function: what matters is that `localDraftFilesDir`
        // is resolved in exactly one place and that no draft path ever names `outboxFilesDir`.
        // An outbox item's staged bytes die with the send; a draft's wait as long as the draft
        // does. One directory for both is one purge away from an amputated draft.
        val source = DaoQuerySource.mailSource("MailRepository")
        assertEquals(
            "the draft staging tree must be resolved in one place, from its own field",
            listOf(
                "private val localDraftFilesDir: java.io.File,",
                "java.io.File(localDraftFilesDir, localDraftDirName(id))",
            ),
            codeLinesNaming(source, "localDraftFilesDir"),
        )
        assertEquals(
            "consuming a draft takes its row AND its files, together — and both best-effort: this " +
                "runs after the draft is on the server and after the original it replaced was " +
                "destroyed, so a throw here reports a failure on a save that fully succeeded",
            listOf(
                "{",
                "runCatching { localDraftDao.deleteById(accountId, id) }",
                "runCatching { localDraftDir(id).deleteRecursively() }",
                "}",
            ),
            codeOf(bodyOf("discardLocalDraft")),
        )
    }

    @Test fun `a JMAP draft already on the server is never re-uploaded because Room hiccuped`() {
        // The twin of the IMAP branch's `runCatching { refresh(...) }`. By that line client.saveDraft
        // has returned an id: the draft IS on the server. A Room failure propagating out of the
        // upload is caught as KEPT_ON_DEVICE, keeps the local row, and the next attempt appends the
        // same draft a second time — a duplicate created by a cache write.
        val body = codeOf(bodyOf("uploadDraft"))
        val at = body.indexOfFirst { it.startsWith("cacheSavedDraft(") }
        assertTrue("uploadDraft no longer caches the just-saved JMAP draft — did it move?", at > 0)
        assertEquals(
            "the optimistic Drafts-list cache write must not be able to fail the upload",
            "runCatching {", body[at - 1],
        )
    }

    @Test fun `parking an edited row writes the state and NOTHING else`() {
        // The whole body, in order. Two things it must not do, and both were in the first draft
        // of this fix:
        assertEquals(
            "parkOutboxEdit must flip the state of an EDITING row and touch no other column:",
            listOf(
                "val row = outboxDao.byId(id) ?: return",
                "if (row.state != OutboxState.EDITING) return",
                "outboxDao.setState(id, state)",
            ),
            codeOf(bodyOf("parkOutboxEdit")).drop(1).dropLast(1),
        )
    }
}
