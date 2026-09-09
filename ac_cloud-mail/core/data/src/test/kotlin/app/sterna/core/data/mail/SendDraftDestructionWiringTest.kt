package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, in exactly [DraftAddressingWiringTest]'s form
 */
class SendDraftDestructionWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /**
     * The parameter lines of `fun [function](`, comments dropped and trimmed — the DECLARATION,
     * which is where the defaults live and which [bodyOf] cannot see at all.
     */
    private fun declarationLinesOf(function: String): List<String> {
        val lines = DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
        val from = lines.indexOfFirst { Regex("""^(suspend )?fun $function\($""").matches(it) }
        check(from >= 0) {
            "MailRepository has no multi-line 'fun $function(' — renamed, or the signature was " +
                "folded onto one line, which would hide this rule rather than break it"
        }
        val to = lines.drop(from + 1).indexOfFirst { it.startsWith("):") }
        check(to >= 0) { "'$function' parameter list does not close with a '):' line" }
        return lines.subList(from + 1, from + 1 + to)
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    }

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun theQueuedRowBuysItsDestroyFromTheOneDecision() {
        assertEquals(
            "enqueueSend must put the question to replaceableDraftIdOrNull — the pure function a " +
                "test can execute, itself built on draftReplacementIsFaithful — with the send's " +
                "own inputs: the id, the attachment count, and the composer's verdict",
            listOf("replaceableDraftIdOrNull(draftEmailId, attachments.size, bodyIsLossy) { id ->"),
            codeLinesNaming(bodyOf("enqueueSend"), "replaceableDraftIdOrNull("),
        )
    }

    @Test fun theDecisionIsHandedTheServerReadAndNotAConstant() {
        // THE middle line: the one that ties the call above to the proof below. Pinned whole,
        // because `{ true }` here is shorter than the shipped lambda and `contains` sees nothing.
        assertEquals(
            "the addressing proof handed to replaceableDraftIdOrNull must be the server read " +
                "sendCarriesDraftAddressing(credentials, id, ccTrimmed, bccTrimmed) — nothing " +
                "else. A lambda " +
                "answering a constant (`{ true }`) reports 'addressing carried' while offline, " +
                "with the cache purged, or for a message no longer in the folder: the id is then " +
                "written on the row and the destroy takes the only copy that carried the Cc, in " +
                "silence, with sendCarriesDraftAddressing left as dead code no build warning " +
                "reaches. And the arguments are `ccTrimmed` AND `bccTrimmed`, both of them: a " +
                "blind copy does leave this phone — the envelope's RCPT TO delivers it — so " +
                "dropping `bccTrimmed`, as shipped until 2026-08-25, makes the proof " +
                "unmeetable for any message with a Cci and leaves its draft in Drafts for ever.",
            listOf("sendCarriesDraftAddressing(credentials, id, ccTrimmed, bccTrimmed)"),
            codeLinesNaming(bodyOf("enqueueSend"), "sendCarriesDraftAddressing("),
        )
    }

    @Test fun theUnprovenVerdictIsWhatANewCallSiteGetsForFree() {
        assertEquals(
            "enqueueSend must declare `bodyIsLossy: Boolean = true` — the UNKNOWN. The default " +
                "is the whole safety of the parameter: a call site that forgets it must land on " +
                "'the composer could not vouch for this body', which writes no id and destroys " +
                "nothing. Flipped to `= false`, every forgetful call site is silently re-armed " +
                "and its draft destroyed on delivery, with the KDoc still promising the opposite.",
            listOf("bodyIsLossy: Boolean = true,"),
            declarationLinesOf("enqueueSend").filter { "bodyIsLossy" in it },
        )
    }

    @Test fun theRowCarriesTheProvenIdAndNeverTheRawOne() {
        // THE line. performSend destroys what this field names; `= draftEmailId` is the defect.
        assertEquals(
            "the outbox row must carry the id the decision returned — never the raw parameter, " +
                "which would re-authorise the destroy on every unproven send",
            listOf("draftEmailId = replaceableDraftId,"),
            codeLinesNaming(bodyOf("enqueueSend"), "draftEmailId ="),
        )
    }

    @Test fun theSendsAddressingProofIsTheSaveOnes() {
        val body = bodyOf("sendCarriesDraftAddressing")
        assertEquals(
            "only JMAP is exempt (its reopen goes through Email/get, so the composer's fields ARE " +
                "the original's addressing); IMAP must fall through to the server read",
            listOf("if (credentials.protocol != MailProtocol.IMAP) return true"),
            codeLinesNaming(body, "return true"),
        )
        assertEquals(
            "the outgoing message's proof is draftReplacementAddressing(cc, bcc) — the same " +
                "function the save uses. The Bcc counts even though the submission still writes " +
                "no `Bcc:` header (it must not: that would hand the hidden recipients to every " +
                "visible one) — the blind copy is DELIVERED, by the envelope's RCPT TO, so " +
                "nothing is lost by destroying the draft it came from. Back to `cc.toSet()` and " +
                "the server read (message.cc + message.bcc) names an address the replacement " +
                "never offers: no id on the row, no destroy, the draft outlives the sent mail",
            listOf("replacement = draftReplacementAddressing(cc, bcc),"),
            codeLinesNaming(body, "replacement ="),
        )
        assertEquals(
            "the original's addressing must come from imapDraftAddressing — the same read the " +
                "save path uses, whose bail-outs answer null (unknown), never an empty set — " +
                "under the send path's own deadline: this read runs inside enqueueSend BEFORE the " +
                "outbox row is inserted, while the message exists only in the composer's RAM, and " +
                "an unbounded read against a silent server (soTimeout 0) leaves the text there. " +
                "⚠ The deadline bounds the connect and the reads, counted once the account's IMAP " +
                "mutex is held — NOT the wait for that mutex, which nothing bounds. The expiry " +
                "surfaces as a SocketTimeoutException, which " +
                "the draftAddressingIsCarried wrapper reads as unknown: no destroy, message sent.",
            listOf("originalAddressing = { id -> imapDraftAddressing(credentials, id, SEND_PROOF_BUDGET_MS) },"),
            codeLinesNaming(body, "originalAddressing ="),
        )
        assertEquals(
            "the deadline is a named constant of MailRepository.kt, of the order of the other " +
                "bounded reads (JMAP_DISCOVERY_BUDGET_MS = 10 s) — losing the definition, or " +
                "inlining a 0 (= ImapBudget.NO_BUDGET = block for ever), reopens the window",
            listOf("private const val SEND_PROOF_BUDGET_MS = 10_000"),
            DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { "SEND_PROOF_BUDGET_MS =" in it },
        )
    }

    @Test fun deliveryDestroysOnlyWhatTheRowNames() {
        val destroy = bodyOf("performSend").lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { "destroyDraft(" in it || "draftEmailId" in it }
        assertEquals(
            "performSend must destroy only the id its row carries, best-effort, strictly after a " +
                "successful delivery — no other destroy may exist on the send path. Both lines " +
                "whole: the `?.let` guard (a row with no id destroys NOTHING, which is how an " +
                "unproven send is spared) and the destroy itself, under the numbering the row " +
                "carries (#99) and never one read here.",
            listOf(
                "item.draftEmailId?.let {",
                "runCatching { destroyDraft(credentials, it, item.draftUidValidity) }",
            ),
            destroy,
        )
        // "No other destroy on the send path" said of the path, not of one function: a destroy
        // inside the delivery itself would run BEFORE performSend's success ordering, and one in
        // enqueueSend would run before anything was even sent.
        assertEquals(
            "the delivery may not destroy anything itself — its failure must leave the draft " +
                "untouched, which only holds if the destroy stays in performSend, after it",
            emptyList<String>(),
            codeLinesNaming(bodyOf("performDelivery"), "destroyDraft("),
        )
        assertEquals(
            "queueing may not destroy anything either — at enqueue time nothing has been sent",
            emptyList<String>(),
            codeLinesNaming(bodyOf("enqueueSend"), "destroyDraft("),
        )
    }
}
