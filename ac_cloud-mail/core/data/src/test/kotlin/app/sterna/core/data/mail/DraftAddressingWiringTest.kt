package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, exactly as [DestroyChecksTheFolderWiringTest]
 */
class DraftAddressingWiringTest {

    private fun bodyOf(file: String, function: String): String =
        DaoQuerySource.mailFunctionBody(file, function)

    /** The code lines of [body] — comments dropped, so prose can neither satisfy a rule nor break
     *  one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLines(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    /** …the ones naming [needle]. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLines(body).filter { needle in it }

    @Test fun theSaveAsksWhetherTheAddressingSurvivedBeforeDestroyingAnything() {
        assertEquals(
            "uploadDraft must put the question to draftAddressingIsCarried — the pure function a " +
                "test can execute — not to a predicate written inline where nothing reaches it",
            listOf("val addressingIsCarried = draftAddressingIsCarried("),
            codeLinesNaming(bodyOf("MailRepository", "uploadDraft"), "draftAddressingIsCarried("),
        )
    }

    @Test fun theProofIsTheCcAndTheBccSinceTheAppendedDraftCarriesBoth() {
        // THE line of the counter-expertise, and it turned over on 2026-08-25. Until then
        // OutgoingMime.build wrote From/To/Cc and no `Bcc:`, so an address typed into Bcc reached
        assertEquals(
            "the replacement's proof is draftReplacementAddressing(ccTrimmed, bccTrimmed) — the " +
                "Cc AND the Bcc. Back to `ccTrimmed.toSet()` and every save of a draft with a " +
                "Cci leaves a second copy in Drafts plus the 'original kept' notice, because the " +
                "server-side read (ccAndBccOf = message.cc + message.bcc) sees the Bcc: header " +
                "the APPEND now writes while the replacement offers nothing to match it. And the " +
                "inclusion stays one way round: a bcc that came back EMPTY still fails the proof " +
                "and still keeps the original (#63), which is what draftReplacementAddressing " +
                "does NOT decide — draftAddressingIsCarried does",
            listOf(
                "replacement = draftReplacementAddressing(ccTrimmed, bccTrimmed),",
                "replacement = requestReceipt,",
                "replacement = requestReceipt,",
            ),
            codeLinesNaming(bodyOf("MailRepository", "uploadDraft"), "replacement ="),
        )
    }

    @Test fun theOriginalsAddressingIsReadFromTheServerForTheReplacedDraft() {
        assertEquals(
            "the original's addressing must come from imapDraftAddressing, for the id being " +
                "replaced and this account — the cache has no cc column to answer with. " +
                "ImapBudget.NO_BUDGET explicitly, which means `this CALL SITE names no deadline` " +
                "and no longer means `nothing bounds this read`: under the interactive save it is " +
                "the historic unbounded read (its own hang window is a pre-existing question, " +
                "tracked separately), while under the DEFERRED upload the coroutine's own " +
                "ImapReadBudget applies to it (#95) — wanted, since an expiry reads as `unknown`, " +
                "i.e. no destroy. Only the SEND path, which holds the message in RAM while it " +
                "reads, carries a deadline written at its call site.",
            listOf("originalAddressing = { id -> imapDraftAddressing(credentials, id, ImapBudget.NO_BUDGET) },"),
            codeLinesNaming(bodyOf("MailRepository", "uploadDraft"), "originalAddressing ="),
        )
    }

    @Test fun theVerdictIsTheOneJustComputedAndTheJmapBranchSaysWhyItIsExempt() {
        // Three lines, in this order: the read, the IMAP branch handing its verdict on, and the
        // JMAP branch's literal `true` (its reopen goes through Email/get, so the composer's
        // fields ARE the original's). `= true` on the IMAP one is the whole defect, restored.
        assertEquals(
            "the IMAP save must hand draftReplacementIsFaithful the verdict it just read; only " +
                "the JMAP branch, which reopens through Email/get, may pass a literal true",
            listOf(
                "val addressingIsCarried = draftAddressingIsCarried(",
                "addressingIsCarried = addressingIsCarried,",
                "addressingIsCarried = true,",
            ),
            codeLinesNaming(bodyOf("MailRepository", "uploadDraft"), "addressingIsCarried"),
        )
    }

    @Test fun anAddressingThatCannotBeReadReturnsUnknownAndNotAnEmptySet() {
        // `?: return emptySet()` here is a purged cache row, or an id whose UID is gone, passing
        // itself off as "this draft had no copied parties" — and the original is destroyed.
        val body = bodyOf("MailRepository", "imapDraftAddressing")
        assertEquals(
            "every bail-out of imapDraftAddressing must answer null (unknown, keep the " +
                "original) — never an empty set, which reads as 'nothing to lose'",
            listOf(
                "val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull() ?: return null",
                "val uid = ImapMailService.uidOf(emailId) ?: return null",
            ),
            codeLinesNaming(body, "?: return"),
        )
        assertEquals(
            "the addressing itself is the server's envelope, for the folder the cached row names " +
                "— under the caller's own deadline, passed through whole: dropping `budgetMs` " +
                "here re-opens the unbounded read for the send path however bounded its call is",
            listOf("return imap.ccAndBccOf(credentials, cached.mailboxId, uid, budgetMs)"),
            codeLinesNaming(body, "ccAndBccOf("),
        )
    }

    /**
     * The EMPTYING gestures ask the same question, and until 2026-08-25 they asked nothing at all
     */
    @Test fun theEmptyingAsksTheServerWhetherTheOriginalWasCopiedToAnybody() {
        assertEquals(
            "⛔ the whole body. Every line of it can be mutated into the loss with every " +
                "executable test still green: `replacement = emptySet()` → any non-empty set and " +
                "an original with a Cc suddenly counts as 'nothing to lose'; " +
                "`EMPTIED_DRAFT_PROOF_BUDGET_MS` → NO_BUDGET, and the gesture made with the " +
                "radio off waits on a socket with no deadline; " +
                "`imapDraftAddressing` → the CACHE, whose cc/bcc columns answer empty for every " +
                "row written before schema v21 (no back-fill), which is the loss itself; " +
                "`return true` moved out of the JMAP branch and the IMAP read is skipped " +
                "altogether; the protocol test inverted and JMAP pays a read it cannot make while " +
                "IMAP asserts an emptiness nobody established. ⭐ The `true` is legitimate on JMAP " +
                "ALONE, and for the reason uploadDraft's own `addressingIsCarried = true` gives: " +
                "a JMAP draft reopens through Email/get, which returns the server's to/cc/bcc, so " +
                "the fields the user has just emptied ARE the original's addressing. Body was:",
            listOf(
                // The braces are IN the expected list, and the body is compared WHOLE. Dropped
                // — `.drop(1).dropLast(1)`, which reads as tidier — the opening brace's own line
                "{",
                "if (credentials.protocol == MailProtocol.IMAP) {",
                "return draftAddressingIsCarried(",
                "replacesEmailId = emailId,",
                "replacement = emptySet(),",
                "originalAddressing = { id -> imapDraftAddressing(credentials, id, EMPTIED_DRAFT_PROOF_BUDGET_MS) },",
                ")",
                "}",
                "return true",
                "}",
            ),
            codeLines(bodyOf("MailRepository", "emptiedDraftAddressingIsProvenEmpty")),
        )
    }

    @Test fun anUnreadableEnvelopeEntryIsNotSilentlyDroppedFromTheAddressing() {
        // A server can hand back a recipient with an empty address. `mapNotNull { it.email }`
        // would drop it, and if it was the only Cc the set is empty — "nothing to lose".
        val body = bodyOf("ImapMailService", "ccAndBccOf")
        assertEquals(
            "the envelope read must go through copiedAddressesOrNull, which answers null when " +
                "an entry is present but yields no address",
            listOf("copiedAddressesOrNull(message.cc + message.bcc)"),
            codeLinesNaming(body, "copiedAddressesOrNull("),
        )
        assertEquals(
            "a message that is not there is unknown too",
            listOf("val message = session.fetchByUid(uid) ?: return@onMailbox null"),
            codeLinesNaming(body, "fetchByUid("),
        )
        assertEquals(
            "no filtering shape may thin this set out — that is exactly the loss",
            emptyList<String>(),
            listOf("mapNotNull", "filterNotNull", "filterNot", "orEmpty()")
                .filter { codeLinesNaming(body, it).isNotEmpty() },
        )
    }
}
