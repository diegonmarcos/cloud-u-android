package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeViewModel.kt` as text and proves nothing
 */
class DraftNumberingFreezeWiringTest {

    @Test fun `the numbering is frozen at the first read that can answer it, and nowhere else`() {
        val prepare = codeLines(bodyOf("prepare"))
        val id = prepare.indexOf(REMEMBER_ID)
        val freeze = prepare.indexOf(FREEZE)
        assertTrue(
            "prepare() must still remember the reopened draft's id as '$REMEMBER_ID' — this rule " +
                "pins the freeze NEXT TO it and must fail loudly rather than pin it beside " +
                "something it never located. Body was:\n${prepare.joinToString("\n")}",
            id >= 0,
        )
        assertTrue(
            "prepare() must still freeze the numbering as '$FREEZE'. Body was:\n" +
                prepare.joinToString("\n"),
            freeze >= 0,
        )
        assertEquals(
            "every line of prepare() that names the reopened draft's id, the account it was read " +
                "under, or the numbering it belongs to — whole lines, IN ORDER (the restore side " +
                "reads all three back from the undone send in bindQueuedRow, pinned right " +
                "below). The reopen branch arms " +
                "THE ID AND ITS ACCOUNT TOGETHER, before the coroutine, in the same breath as the " +
                "pessimistic `editingDraftLossy = true` that makes an early id harmless, and only " +
                "afterwards freezes the numbering at the first line that can answer it. ⛔ The " +
                "account BELOW the numbering is the ordering this rule was rewritten to refuse: " +
                "it puts the account inside the coroutine, so a reopen that fails (offline, the " +
                "process killed, no credentials) leaves an id armed with a NULL account — and a " +
                "null account is the one value `unlessDraftBelongsElsewhere` waves THROUGH. The " +
                "reader then switches \"From\" to another account over her still-visible text, " +
                "taps Save, and A's draft id is used to replace a row under B: on a server where " +
                "two accounts mint the same draft id (#31) that OVERWRITES B's own pending draft " +
                "with A's text, which was its only copy. The numbering, for its part, must still " +
                "be read at the earliest possible moment and never again: anywhere later is a " +
                "different number — the one a refresh recorded after the renumbering, i.e. " +
                "exactly the value that lets the expunge through on another message. An extra " +
                "line here (an alias, a 're-read to be safe') is one of those defects restored. " +
                "Body was:\n" +
                prepare.joinToString("\n"),
            listOf(
                REMEMBER_ID,
                FREEZE_ACCOUNT,
                FREEZE,
            ),
            prepare.filter {
                it.startsWith("editingDraftId =") ||
                    it.startsWith("editingDraftUidValidity =") ||
                    it.startsWith("editingDraftAccountId =")
            },
        )
        val bind = codeLines(bodyOf("bindQueuedRow"))
        assertEquals(
            "every line of bindQueuedRow() — the restore side, run by the restored lambda of " +
                "prepare() on the hand-over and by the resume of a composer rebuilt after a " +
                "process death on the row it took back — that names the id, its account or its " +
                "numbering: all three read back from the record, whole lines, IN ORDER, and " +
                "nothing re-read. Body was:\n" + bind.joinToString("\n"),
            listOf(
                "editingDraftId = d.draftEmailId",
                "editingDraftUidValidity = d.draftUidValidity",
                "editingDraftAccountId = d.fromAccountId",
            ),
            bind.filter {
                it.startsWith("editingDraftId =") ||
                    it.startsWith("editingDraftUidValidity =") ||
                    it.startsWith("editingDraftAccountId =")
            },
        )
        val carry = prepare.indexOfFirst { "carryDraftAttachments(" in it }
        assertTrue(
            "prepare() no longer carries the reopened draft's attachments — did the branch move?",
            carry >= 0,
        )
        assertTrue(
            "⭐ …and the freeze must come BEFORE the attachment carry. Nothing above asserted this " +
                "— the ordered list is filtered, so it cannot see a line that is not in it, and " +
                "moving the freeze down past the carry left every rule in this file green while " +
                "restoring #99 whole: the carry suspends once per attachment on a network " +
                "download, which is exactly the room a background pass needs to notice Drafts " +
                "renumbered and write the NEW number to the store. Read after that, the freeze " +
                "answers the numbering of the renumbering, the guard compares it with itself, and " +
                "the expunge lands on another message. Body was:\n" + prepare.joinToString("\n"),
            freeze < carry,
        )
        assertTrue(
            "…and in the reopen branch the id must be armed BEFORE the freeze, not after. This is " +
                "the assertion that INVERTED: the id used to come last because it was what armed " +
                "the destroy, and a draft whose fetch died never got one at all — Save then wrote " +
                "a second draft in silence. The id is now armed on entry beside a pessimistic " +
                "verdict, so it arms nothing on its own; what is laid last is the LOWERING of that " +
                "verdict (see DraftVerdictWiringTest), and a numbering read after an id that " +
                "cannot destroy is exactly as safe as one read before it. Body was:\n" +
                prepare.joinToString("\n"),
            id < freeze,
        )
        val account = prepare.indexOf(FREEZE_ACCOUNT)
        assertTrue(
            "prepare() must still carry the reopened draft's attachments, and must still freeze " +
                "the account as '$FREEZE_ACCOUNT' — `credentials()` is deliberately not `suspend`, " +
                "so the account can be read at the entry, beside the id, before anything can fail. " +
                "Body was:\n" + prepare.joinToString("\n"),
            carry >= 0 && account >= 0,
        )
        assertEquals(
            "⭐ A SERVER DRAFT ID NEVER TRAVELS WITHOUT THE ACCOUNT IT WAS READ UNDER: the account " +
                "freeze is the very NEXT code line after the id, one gesture, nothing between " +
                "them, and both before `viewModelScope.launch`. ⛔ This is the assertion the " +
                "previous version of this file got wrong: it read `id < freeze && freeze < " +
                "account && account < carry`, i.e. it REQUIRED the account to be frozen inside the " +
                "coroutine, after the fetch. Every way the reopen can fail then leaves an id " +
                "armed with a NULL account — offline, the process killed and prepare() re-run " +
                "under a bar `applied` has already redrawn, or the early `credentials() ?: " +
                "return@launch`, which never even reaches the catch. That is precisely the value " +
                "`unlessDraftBelongsElsewhere` (core/data/…/mail/LocalDraftReopen.kt) lets " +
                "THROUGH, because null is supposed to mean \"no server draft is open behind this " +
                "composer\". WHAT THE READER LOSES: draft D of account A reopened, link dead, her " +
                "text still on screen; she switches \"From\" to account B (the picker covers " +
                "every account and resets nothing) and taps Save. The guard waves D through, " +
                "`repo.saveDraft(B, …, replacesEmailId = D)` reaches `forServerDraft(B, D)`, and " +
                "server draft ids collide between two accounts of one server (#31) — B's own " +
                "pending draft is overwritten in place by A's text, the only copy of it. Body " +
                "was:\n" + prepare.joinToString("\n"),
            listOf(REMEMBER_ID, FREEZE_ACCOUNT),
            prepare.subList(id, (id + 2).coerceAtMost(prepare.size)),
        )
        // The FIRST `viewModelScope.launch {` of prepare() belongs to another branch — the one
        // that prefills a reply. The coroutine meant here is the one that opens AFTER the account
        // freeze, so it is looked for from there; searching from the top pins nothing at all.
        val launch = (account until prepare.size).firstOrNull { prepare[it] == "viewModelScope.launch {" } ?: -1
        assertTrue(
            "…and both are armed OUTSIDE the coroutine: this branch's `viewModelScope.launch {` " +
                "must come after them, and the numbering and the attachment carry after that. The " +
                "whole order this file pins is id → account → launch → numbering → carry. Pushed " +
                "inside the launch, the account is frozen only on the runs that get that far — " +
                "and a reopen that never gets that far is exactly the case the guard has to " +
                "survive. Body was:\n" + prepare.joinToString("\n"),
            launch > account && launch < freeze && freeze < carry,
        )
    }

    /**
     * The account is frozen exactly twice — the restore, and the reopen — and never looked up
     */
    @Test fun `the account is frozen where the numbering is, and written nowhere else`() {
        val lines = codeLines(source())
        assertEquals(
            "exactly two writes: the restore of a message coming back from the queue, and the " +
                "freeze taken when a saved draft is opened. \u26d4 A qualified receiver counts as " +
                "a write, which is why this looks for the assignment and not for the start of the " +
                "line: `this.editingDraftAccountId = option.accountId` written into selectFrom " +
                "sails past a startsWith, and it is the reported defect itself — the picker " +
                "re-freezing the account it was never allowed to touch. \u26d4 And still exactly " +
                "TWO after the freeze moved up to the entry of the reopen branch: MOVED, not " +
                "duplicated. A second write a few lines below the first is a value free to go " +
                "stale against the id it describes, and the later one wins — which on this path " +
                "means the account is whatever the picker was showing by then. Lines were:\n" +
                lines.filter { "editingDraftAccountId" in it }.joinToString("\n"),
            listOf("editingDraftAccountId = d.fromAccountId", FREEZE_ACCOUNT),
            lines.filter { ASSIGNS_ACCOUNT.containsMatchIn(it) },
        )
        assertEquals(
            "…and it is declared once, as a plain field of this class. Lines were:",
            listOf("private var editingDraftAccountId: String? = null"),
            lines.filter { it.startsWith("private var editingDraftAccountId") },
        )
        val at = lines.indexOf("private var editingDraftAccountId: String? = null")
        assertTrue(
            "⛔ …and it is a FIELD, not a computed one: `get() = _selectedFrom.value?.accountId ?: " +
                "field` compiles, leaves every other rule in this file green, and restores the " +
                "whole defect one layer beneath them — the expunge would again be addressed to " +
                "whatever identity the \"From\" picker happens to be showing, while the two rules " +
                "above go on pinning a freeze that no longer decides anything. The line after the " +
                "declaration was: " + lines.getOrNull(at + 1),
            lines.getOrNull(at + 1)?.let { !it.startsWith("get()") && !it.startsWith("set(") } == true,
        )
    }

    /**
     * An assignment to the frozen account, however it is written — bare, or through a qualified
     * `this.`, which a `startsWith` never sees.
     */
    private val ASSIGNS_ACCOUNT = Regex("""^(this\.)?editingDraftAccountId\s*=[^=]""")

    @Test fun `it is never read again at save time`() {
        val everywhere = codeLines(source()).filter { "recordedUidValidityForDraft" in it }
        assertEquals(
            "the accessor may be called EXACTLY ONCE in this class, at the read. A second call — " +
                "in saveDraft, in submit, in a helper 'to be safe' — re-opens the whole defect: it " +
                "answers the numbering of the renumbering the guard exists to refuse, and every " +
                "test on the repository side stays green. It is the same reason " +
                "InboxViewModel.flushPendingDestroy is deliberately not `suspend`. Lines were:\n" +
                everywhere.joinToString("\n"),
            listOf(FREEZE),
            everywhere,
        )
    }

    @Test fun `both destroying saves are handed the frozen numbering, not a fresh one`() {
        val save = codeLines(bodyOf("saveDraft"))
        assertEquals(
            "an emptied draft is DISCARDED — a permanent expunge (#69) — and must carry the number " +
                "frozen when the composer opened it, captured beside the id before abandon() runs. " +
                "Body was:\n${save.joinToString("\n")}",
            listOf(
                "val original = editingDraftId",
                "val originalUidValidity = editingDraftUidValidity",
                "val originalAccountId = editingDraftAccountId",
            ),
            save.filter { it.startsWith("val original") },
        )
        assertEquals(
            "and the discard call must be handed that captured value — nothing else, and no `null` " +
                "placeholder: `null` destroys nothing, so an emptied draft would come back on the " +
                "next sync. Body was:\n${save.joinToString("\n")}",
            listOf("repo.discardDraft(destroyingCredentials(), original, originalUidValidity)"),
            save.filter { "repo.discardDraft(" in it },
        )
        assertEquals(
            "re-saving an opened draft destroys the server copy it replaces, so repo.saveDraft must " +
                "carry the frozen numbering for it too, under its own named argument. Passing " +
                "editingDraftId's numbering read afresh here is the defect this fix closes. It now " +
                "travels inside the pair the account guard is asked on (⛔ pinned right below, or " +
                "this rule could be satisfied by a pair built from a fresh read). " +
                "Body was:\n${save.joinToString("\n")}",
            listOf(
                "replacesEmailId = replaces.emailId,",
                "replacesUidValidity = replaces.uidValidity,",
            ),
            save.filter { it.startsWith("replaces") },
        )
        assertEquals(
            "…and that pair is built from the two FROZEN values, in one statement: the id read at " +
                "the open and the numbering read beside it (#99). Rebuilt from " +
                "`recordedUidValidityForDraft` here — or from any other read — this is the whole " +
                "defect back, one layer beneath a rule that goes on pinning the argument names. " +
                "Body was:\n${save.joinToString("\n")}",
            listOf("val replaces = SendDraftTarget(emailId = editingDraftId, uidValidity = editingDraftUidValidity)"),
            save.filter { it.startsWith("val replaces") },
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** The body of `fun [name](` in `ComposeViewModel.kt`, braces balanced. */
    private fun bodyOf(name: String): String {
        val code = codeLines(source()).joinToString("\n")
        val at = code.indexOf("fun $name(")
        check(at >= 0) { "ComposeViewModel.kt declares no '$name(' — did it get renamed?" }
        val start = code.indexOf('{', code.indexOf(')', at)) + 1
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    /** The lines of [text] that are code, trimmed, with comments taken off — the comments here
     *  name the very calls and the very ordering the rules above pin. */
    private fun codeLines(text: String): List<String> = text.lines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
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

    private fun source(): String = COMPOSE_VIEW_MODEL.readText()

    companion object {
        private const val REMEMBER_ID = "editingDraftId = draftId"
        private const val FREEZE =
            "editingDraftUidValidity = repo.recordedUidValidityForDraft(credentials, draftId)"
        private const val FREEZE_ACCOUNT = "editingDraftAccountId = credentials()?.id"

        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}
