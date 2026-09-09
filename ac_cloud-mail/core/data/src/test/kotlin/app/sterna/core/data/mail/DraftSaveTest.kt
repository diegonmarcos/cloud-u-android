package app.sterna.core.data.mail

import app.sterna.core.imap.ImapAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The rule that keeps re-saving a draft from destroying it (#63): the original may only be
 */
class DraftSaveTest {

    @Test fun aPlainDraftWithEveryAttachmentCarriedMayReplaceTheOriginal() {
        assertTrue(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 2, attachmentsCarried = 2, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    @Test fun noAttachmentsAtAllIsFaithful() {
        assertTrue(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    @Test fun aDroppedAttachmentKeepsTheOriginal() {
        // The blocker: one part couldn't be re-attached (upload refused, staged file gone).
        // Destroying the original here deletes the only copy of that file.
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 2, attachmentsCarried = 1, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    @Test fun everyAttachmentDroppedKeepsTheOriginal() {
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 1, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    @Test fun aFlattenedBodyKeepsTheOriginalEvenWithNoAttachments() {
        // A draft authored elsewhere in HTML is flattened to plain text on open; saving it back
        // cannot restore the formatting, so the HTML original stays put.
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = true,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    /**
     * The new refusal, everything else being perfect: no attachments, nothing flattened. A save
     * that cannot show the addressing survived is not faithful, whatever else it got right.
     */
    @Test fun anAddressingThatIsNotCarriedKeepsTheOriginalOnItsOwn() {
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = false,
                receiptRequestIsCarried = true,
            ),
        )
    }

    /**
     * A replacement that never left, every other leg green — and the original still stands.
     */
    @Test fun aReplacementThatNeverLeftProvesNothingAndKeepsTheOriginal() {
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = false,
                attachmentsIn = 1, attachmentsCarried = 1, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    /**
     * THE cheap half of the same damage, and the one no other leg can catch: a draft with NO
     */
    @Test fun `a replacement that never left keeps the original even with nothing to count`() {
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = false,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    /**
     * The mirror, same values: the term is the APPEND's real answer and not a constant `false`.
     */
    @Test fun theSameSaveThatDidGoOutStillReplacesTheOriginal() {
        assertTrue(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 1, attachmentsCarried = 1, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = true,
            ),
        )
    }

    // ---- draftAddressingIsCarried: the unknown, and the inclusion ----

    /**
     * THE distinction of this fix, half one. `null` = the addressing could NOT be established
     */
    @Test fun anAddressingThatCouldNotBeReadIsNotAnEmptyOne() {
        assertFalse(draftAddressingIsCarried(original = null, replacement = emptySet()))
        assertFalse(
            draftAddressingIsCarried(
                original = null,
                replacement = setOf("jordan.lee@masto.top"),
            ),
        )
    }

    /**
     * THE distinction of this fix, half two, and deliberately a SEPARATE test from the one
     */
    @Test fun anAddressingReadAsEmptyIsCarriedByAnything() {
        assertTrue(draftAddressingIsCarried(original = emptySet(), replacement = emptySet()))
        assertTrue(
            draftAddressingIsCarried(
                original = emptySet(),
                replacement = setOf("jordan.lee@masto.top"),
            ),
        )
    }

    /** The measured symptom: the original had a Cc, the reopened composer had none. */
    @Test fun aDroppedRecipientIsNotCarried() {
        assertFalse(
            draftAddressingIsCarried(
                original = setOf("jordan.lee@masto.top"),
                replacement = emptySet(),
            ),
        )
    }

    /** One of two dropped is still a loss. */
    @Test fun oneOfSeveralRecipientsDroppedIsNotCarried() {
        assertFalse(
            draftAddressingIsCarried(
                original = setOf("jordan.lee@masto.top", "sam.diaz@masto.top"),
                replacement = setOf("jordan.lee@masto.top"),
            ),
        )
    }

    /**
     * The inclusion runs ONE way: original ⊆ replacement. A replacement that ADDED an address
     */
    @Test fun anAddressAddedByTheUserStillCarriesTheOriginal() {
        assertTrue(
            draftAddressingIsCarried(
                original = setOf("jordan.lee@masto.top"),
                replacement = setOf("jordan.lee@masto.top", "sam.diaz@masto.top"),
            ),
        )
    }

    /** A mailbox re-typed in another case, or with the whitespace a chip leaves, is the same one. */
    @Test fun caseAndWhitespaceDoNotMakeAnAddressLost() {
        assertTrue(
            draftAddressingIsCarried(
                original = setOf("  Jordan.Lee@Masto.TOP "),
                replacement = setOf("jordan.lee@masto.top"),
            ),
        )
    }

    /** A blank is not an address, so it cannot be the thing that was lost. */
    @Test fun aBlankEntryIsNotAnAddressThatWentMissing() {
        assertTrue(draftAddressingIsCarried(original = setOf("", "   "), replacement = emptySet()))
    }

    // ---- draftReplacementAddressing: what the replacement may offer as proof (2026-08-25) ----

    /**
     * THE test of this volet, and it EXECUTES the decision rather than reading the call site.
     */
    @Test fun aBlindCopyIsCarriedByTheReplacementsOwnBcc() {
        assertTrue(
            draftAddressingIsCarried(
                original = setOf("sam.diaz@masto.top"),
                replacement = draftReplacementAddressing(
                    cc = listOf("jordan.lee@masto.top"),
                    bcc = listOf("sam.diaz@masto.top"),
                ),
            ),
        )
    }

    /**
     * The safe direction, unchanged: a replacement whose Bcc came back EMPTY (the reopen gave
     */
    @Test fun aReplacementWithNoBccDoesNotCarryABlindCopy() {
        assertFalse(
            draftAddressingIsCarried(
                original = setOf("sam.diaz@masto.top"),
                replacement = draftReplacementAddressing(
                    cc = listOf("jordan.lee@masto.top"),
                    bcc = emptyList(),
                ),
            ),
        )
    }

    /** And the Cc did not stop counting: both fields are offered, neither replaces the other. */
    @Test fun theCcIsStillOfferedBesideTheBcc() {
        assertTrue(
            draftAddressingIsCarried(
                original = setOf("jordan.lee@masto.top"),
                replacement = draftReplacementAddressing(
                    cc = listOf("jordan.lee@masto.top"),
                    bcc = listOf("sam.diaz@masto.top"),
                ),
            ),
        )
        assertEquals(
            setOf("jordan.lee@masto.top", "sam.diaz@masto.top"),
            draftReplacementAddressing(
                cc = listOf("jordan.lee@masto.top"),
                bcc = listOf("sam.diaz@masto.top"),
            ),
        )
    }

    // ---- draftAddressingIsCarried(replacesEmailId, …): the decision the save actually runs ----

    /** Nothing is being replaced, so nothing will be destroyed and there is nothing to prove. */
    @Test fun aBrandNewDraftReplacesNothingAndNeedsNoProof() = runTest {
        var read = false
        val carried = draftAddressingIsCarried(
            replacesEmailId = null,
            replacement = emptySet(),
            originalAddressing = { read = true; emptySet() },
        )
        assertTrue(carried)
        assertFalse("nothing to replace must not cost a server round trip", read)
    }

    /** The id being replaced is the one whose addressing is read — not some other draft's. */
    @Test fun theAddressingIsReadForTheDraftBeingReplaced() = runTest {
        val asked = mutableListOf<String>()
        val carried = draftAddressingIsCarried(
            replacesEmailId = "imap:acct:Drafts:42",
            replacement = setOf("jordan.lee@masto.top"),
            originalAddressing = { id -> asked += id; setOf("jordan.lee@masto.top") },
        )
        assertTrue(carried)
        assertTrue("the replaced id is what gets read", asked == listOf("imap:acct:Drafts:42"))
    }

    /**
     * Offline: the read throws. That is an unknown, not an empty addressing, so the original
     * stays. This is the bench case "airplane mode, reopen a draft, save it".
     */
    @Test fun aReadThatFailsLeavesTheOriginalAlone() = runTest {
        val carried = draftAddressingIsCarried(
            replacesEmailId = "imap:acct:Drafts:42",
            replacement = setOf("jordan.lee@masto.top"),
            originalAddressing = { throw IOException("offline") },
        )
        assertFalse(carried)
    }

    /** A message that is no longer there reads as null, and null is an unknown too. */
    @Test fun anOriginalThatCannotBeFoundLeavesTheOriginalAlone() = runTest {
        val carried = draftAddressingIsCarried(
            replacesEmailId = "imap:acct:Drafts:42",
            replacement = setOf("jordan.lee@masto.top"),
            originalAddressing = { null },
        )
        assertFalse(carried)
    }

    /** The measured symptom, end to end through the decision the save runs. */
    @Test fun theServerCopyThatStillHoldsTheCcIsNotDestroyed() = runTest {
        val carried = draftAddressingIsCarried(
            replacesEmailId = "imap:acct:Drafts:42",
            // The reopened composer shows To only: the Cc line came back folded and empty.
            replacement = emptySet(),
            originalAddressing = { setOf("jordan.lee@masto.top") },
        )
        assertFalse(carried)
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = carried,
                receiptRequestIsCarried = true,
            ),
        )
    }

    /** A draft with no copied parties at all is still replaced in place — one draft, not two. */
    @Test fun aDraftWithNoCopiedPartiesIsStillReplacedInPlace() = runTest {
        val carried = draftAddressingIsCarried(
            replacesEmailId = "imap:acct:Drafts:42",
            replacement = emptySet(),
            originalAddressing = { emptySet() },
        )
        assertTrue(carried)
        assertTrue(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = carried,
                receiptRequestIsCarried = true,
            ),
        )
    }

    /**
     * The EMPTYING gestures (#69 × #95) ask THIS question, with an empty replacement — the
     */
    @Test fun theEmptyingProvesAnEmptyAddressingOnlyWhenTheServerSaidSo() = runTest {
        val reads = listOf<suspend (String) -> Set<String>?>(
            { emptySet() },                    // the server answered: copied to nobody
            { setOf("jordan.lee@masto.top") }, // the server answered: there was a Cc
            { null },                          // the row or the UID is gone: no answer
            { throw IOException("offline") },  // …and neither is this one
        )
        assertEquals(
            "only the first may license the destroy. A guard that read the last two as \"nothing " +
                "to lose\" expunges the only copy that still holds those recipients, silently — " +
                "and on this route the user is left with no draft at all",
            listOf(true, false, false, false),
            reads.map { read -> draftAddressingIsCarried("imap:acct:Drafts:42", emptySet(), read) },
        )
    }

    // ---- copiedAddressesOrNull: what the envelope read may and may not round down ----

    /** The ordinary read: every entry names a mailbox, so the set is the addressing. */
    @Test fun everyReadableEntryIsInTheAddressing() {
        assertEquals(
            setOf("jordan.lee@masto.top", "sam.diaz@masto.top"),
            copiedAddressesOrNull(
                listOf(
                    ImapAddress(name = "Jordan Lee", email = "jordan.lee@masto.top"),
                    ImapAddress(name = null, email = "sam.diaz@masto.top"),
                ),
            ),
        )
    }

    /** No entries at all is a real answer: this draft has no copied parties, and loses none. */
    @Test fun noEntriesReadsAsAnEmptyAddressingNotAsUnknown() {
        assertEquals(emptySet<String>(), copiedAddressesOrNull(emptyList()))
    }

    /**
     * The entry the server hands back with no address at all — measured on the bench, a server
     */
    @Test fun theOnlyEntryBeingUnreadableIsUnknownAndNotAnEmptyAddressing() {
        val read = copiedAddressesOrNull(listOf(ImapAddress(name = "aa", email = null)))
        assertNull(read)
        // And the decision that follows from it: the original stays.
        assertFalse(draftAddressingIsCarried(original = read, replacement = setOf("aa")))
        // Read as an empty addressing instead, the very same save destroys the original.
        assertTrue(draftAddressingIsCarried(original = emptySet(), replacement = setOf("aa")))
    }

    /** One unreadable entry among readable ones makes the whole read unknown, not a shorter set. */
    @Test fun oneUnreadableEntryDoesNotLeaveAnAmputatedAddressing() {
        assertNull(
            copiedAddressesOrNull(
                listOf(
                    ImapAddress(name = "Jordan Lee", email = "jordan.lee@masto.top"),
                    ImapAddress(name = "aa", email = null),
                ),
            ),
        )
    }

    /** An address present but blank says as little as one that is absent. */
    @Test fun aBlankAddressIsAsUnreadableAsAMissingOne() {
        assertNull(copiedAddressesOrNull(listOf(ImapAddress(name = "aa", email = "   "))))
    }

    /**
     * `Cc: undisclosed-recipients:;`, the entry the envelope parser now produces for it, and the
     */
    @Test fun aCcThatIsOnlyAGroupNamingNobodyKeepsTheServerCopy() {
        val read = copiedAddressesOrNull(
            listOf(ImapAddress(name = "undisclosed-recipients", email = null)),
        )
        assertNull("a group naming nobody is addressing that yielded no address: unknown", read)
        assertFalse(
            "and an unknown addressing keeps the original — the copy is the only thing left " +
                "holding `Cc: undisclosed-recipients:;`",
            draftAddressingIsCarried(original = read, replacement = setOf("quelqu-un@masto.top")),
        )
        assertTrue(
            "whereas an addressing read as EMPTY authorises the destroy, which is precisely what " +
                "the dropped group used to produce",
            draftAddressingIsCarried(original = emptySet(), replacement = setOf("quelqu-un@masto.top")),
        )
    }

    // ---- draftReceiptRequestIsCarried: the same rule, for the read receipt (D2) ---------------

    /**
     * THE distinction, half one, and the reason this field could not be in the guard before the
     */
    @Test fun aReceiptRequestThatCouldNotBeReadIsNotTheAbsenceOfOne() {
        assertFalse(draftReceiptRequestIsCarried(original = null, replacement = false))
    }

    /**
     * …and the limit of that conservatism: when the REPLACEMENT asks for a receipt, nothing the
     */
    @Test fun aReplacementThatAsksForOneCarriesAnythingIncludingAnUnknown() {
        assertTrue(draftReceiptRequestIsCarried(original = null, replacement = true))
        assertTrue(draftReceiptRequestIsCarried(original = true, replacement = true))
        assertTrue(draftReceiptRequestIsCarried(original = false, replacement = true))
    }

    /** Half two, deliberately a separate test: a draft that really asked for nothing loses
     *  nothing, so it may be replaced whatever the new one does. */
    @Test fun anOriginalThatAskedForNothingIsCarriedByAnything() {
        assertTrue(draftReceiptRequestIsCarried(original = false, replacement = false))
        assertTrue(draftReceiptRequestIsCarried(original = false, replacement = true))
    }

    /** The loss itself: the original asked, the replacement does not. */
    @Test fun aReceiptRequestTheReplacementDroppedIsNotCarried() {
        assertFalse(draftReceiptRequestIsCarried(original = true, replacement = false))
    }

    /** And the ordinary case, which must NOT leave a duplicate behind. */
    @Test fun aReceiptRequestStillAskedForIsCarried() {
        assertTrue(draftReceiptRequestIsCarried(original = true, replacement = true))
    }

    /** On its own, everything else being perfect, it keeps the original. */
    @Test fun aDroppedReceiptRequestKeepsTheOriginalOnItsOwn() {
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = false,
            ),
        )
    }

    // ---- draftReceiptRequestIsCarried(replacesEmailId, …): what the save actually runs --------

    @Test fun aBrandNewDraftAsksNoServerAboutAReceipt() = runTest {
        var read = false
        val carried = draftReceiptRequestIsCarried(
            replacesEmailId = null,
            replacement = false,
            originalRequested = { read = true; true },
        )
        assertTrue(carried)
        assertFalse("nothing to replace must not cost a round trip", read)
    }

    @Test fun theReceiptIsReadForTheDraftBeingReplaced() = runTest {
        val asked = mutableListOf<String>()
        val carried = draftReceiptRequestIsCarried(
            replacesEmailId = "imap:acct:Drafts:42",
            replacement = false,
            originalRequested = { id -> asked += id; false },
        )
        assertTrue(carried)
        assertEquals(listOf("imap:acct:Drafts:42"), asked)
    }

    /** A replacement that asks for a receipt proves itself: no round trip is spent on the
     *  original, because no answer could change the verdict. */
    @Test fun aReplacementThatAsksForOneCostsNoRoundTrip() = runTest {
        var read = false
        val carried = draftReceiptRequestIsCarried(
            replacesEmailId = "imap:acct:Drafts:42",
            replacement = true,
            originalRequested = { read = true; null },
        )
        assertTrue(carried)
        assertFalse("nothing could be lost, so nothing needs reading", read)
    }

    /** Offline: the read throws, so the answer is unknown and the original stays. */
    @Test fun aFailedReceiptReadLeavesTheOriginalAlone() = runTest {
        assertFalse(
            draftReceiptRequestIsCarried(
                replacesEmailId = "imap:acct:Drafts:42",
                replacement = false,
                originalRequested = { throw IOException("offline") },
            ),
        )
    }

    /** A server that will not serve the header answers null — likewise unknown. */
    @Test fun aServerThatCannotAnswerLeavesTheOriginalAlone() = runTest {
        assertFalse(
            draftReceiptRequestIsCarried(
                replacesEmailId = "imap:acct:Drafts:42",
                replacement = false,
                originalRequested = { null },
            ),
        )
    }

    /** The D2 symptom, end to end through the decision the save runs: the reopened composer shows
     *  the box clear, so the replacement asks for nothing while the original did. */
    @Test fun theServerCopyThatStillHoldsTheReceiptRequestIsNotDestroyed() = runTest {
        val carried = draftReceiptRequestIsCarried(
            replacesEmailId = "jmap:d1",
            replacement = false,
            originalRequested = { true },
        )
        assertFalse(carried)
        assertFalse(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = carried,
            ),
        )
    }

    /** …and the other half of the debt: an ordinary draft nobody asked a receipt for is still
     *  replaced in place. One draft, not two, every time it is saved. */
    @Test fun anOrdinaryDraftIsStillReplacedInPlace() = runTest {
        val carried = draftReceiptRequestIsCarried(
            replacesEmailId = "jmap:d1",
            replacement = false,
            originalRequested = { false },
        )
        assertTrue(carried)
        assertTrue(
            draftReplacementIsFaithful(
                replacementWentOut = true,
                attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false,
                addressingIsCarried = true,
                receiptRequestIsCarried = carried,
            ),
        )
    }
}
