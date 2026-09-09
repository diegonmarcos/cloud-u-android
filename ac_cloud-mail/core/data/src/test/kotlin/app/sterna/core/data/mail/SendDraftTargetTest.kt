package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a send destroys, and what leaving the composer gives back — both EXECUTED (#95).
 */
class SendDraftTargetTest {

    // -- the pair a send carries -----------------------------------------------------------------

    @Test fun `a draft the server issued is destroyed under the composer's own numbering`() {
        // Both pairs are filled and they DIFFER: only reading the composer's can pass.
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = "imap:acc:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = "imap:acc:Drafts:7",
                replacedServerDraftUidValidity = 12L,
            ),
        )
    }

    @Test fun `a JMAP draft carries no numbering, and none is invented for it`() {
        assertEquals(
            SendDraftTarget("Mdeadbeef", null),
            sendDraftTarget(
                editingDraftId = "Mdeadbeef",
                editingDraftUidValidity = null,
                replacedServerDraftId = "imap:acc:Drafts:7",
                replacedServerDraftUidValidity = 12L,
            ),
        )
    }

    @Test fun `a reopened local draft sends the SERVER draft it stands in for, numbering included`() {
        // The defect this closes. The composer holds the ROW's id, for which sendDraftEmailId
        // answers null — correctly — and before this nobody named the server draft any more: the
        // row was consumed by the send, the mask lifted, and the pre-edit text reappeared in Drafts
        // beside the message that had just gone out.
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d1",
                editingDraftUidValidity = null,
                replacedServerDraftId = "imap:acc:Drafts:41",
                replacedServerDraftUidValidity = 900L,
            ),
        )
    }

    @Test fun `the id and the numbering are never taken from two different places`() {
        // The pair is one answer. A row's id beside the composer's numbering is the worst of
        // both: on IMAP a UID EXPUNGE opposed to the wrong UIDVALIDITY hits ANOTHER message of
        // Drafts. So 12L must NOT come out here, though the composer holds it.
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d2",
                editingDraftUidValidity = 12L,
                replacedServerDraftId = "imap:acc:Drafts:41",
                replacedServerDraftUidValidity = 900L,
            ),
        )
    }

    @Test fun `a local draft written from scratch replaces nothing, and destroys nothing`() {
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d3",
                editingDraftUidValidity = 12L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ),
        )
    }

    @Test fun `no draft at all is no destroy, and the numbering goes with the id`() {
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = null,
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ),
        )
    }

    @Test fun `a replaced id this phone minted is refused too, in belt`() {
        // The row's `replacesEmailId` is written by localDraftTarget and should never hold a local
        // id — this is the belt on top of that braces, and it must take the numbering with it.
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d4",
                editingDraftUidValidity = 12L,
                replacedServerDraftId = LOCAL_DRAFT_ID_PREFIX + "d9",
                replacedServerDraftUidValidity = 900L,
            ),
        )
    }

    // -- the fidelity verdict, applied to the PAIR -----------------------------------------------

    @Test fun `a body this composer cannot reproduce withholds the id AND its numbering`() {
        // The scheduled table has no column for the verdict, so the id itself is the carrier: no id
        // means "keep the original, live with the duplicate" (#63). The numbering goes with it.
        // A number left beside a null id destroys nothing today — it is the STATEMENT that is the
        // defect: half a pair on a row that waits for hours is the shape the next reader copies.
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = "imap:acc:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ).unlessBodyIsLossy(true),
        )
    }

    @Test fun `a clean verdict leaves the pair whole, both members`() {
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = "imap:acc:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ).unlessBodyIsLossy(false),
        )
    }

    @Test fun `the verdict also takes away a server draft named off the local row`() {
        // The pair the ROW carries is withheld exactly as the composer's own is: a draft this phone
        // never read whole keeps its server copy whichever way that copy is named.
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d8",
                editingDraftUidValidity = null,
                replacedServerDraftId = "imap:acc:Drafts:41",
                replacedServerDraftUidValidity = 900L,
            ).unlessBodyIsLossy(true),
        )
    }

    // -- the account guard, applied to the PAIR --------------------------------------------------

    @Test fun `a draft read under another account is not carried into the one being written as`() {
        // The refusal, and the only one on this path. A new copy is about to be created on B
        // while the original lives on A: carrying the id would have B's delivery worker destroy a
        // name it never issued — on JMAP spared in silence, on IMAP a bare NUMBER in B's own
        // Drafts, which two accounts of one server can hold under the same UIDVALIDITY.
        assertEquals(
            SendDraftTarget(null, null),
            SendDraftTarget("imap:accA:Drafts:41", 900L)
                .unlessDraftBelongsElsewhere(draftAccountId = "accA", writingAccountId = "accB"),
        )
    }

    @Test fun `the ordinary single-account save keeps the pair whole, both members`() {
        // The other half, and the one everybody plays: inverted, this leaves a SECOND draft in
        // Drafts at every save, for every user, because nothing names the original any more.
        assertEquals(
            SendDraftTarget("imap:accA:Drafts:41", 900L),
            SendDraftTarget("imap:accA:Drafts:41", 900L)
                .unlessDraftBelongsElsewhere(draftAccountId = "accA", writingAccountId = "accA"),
        )
    }

    @Test fun `no draft account means no server draft open, and refuses nothing`() {
        // A brand-new message, a reply, a local row that replaces nothing: the ordinary route. The
        // pair it is applied to is empty there anyway, but the rule is stated on a full one so that
        // "null refuses" cannot be smuggled in as a safety net.
        assertEquals(
            SendDraftTarget("Mdeadbeef", null),
            SendDraftTarget("Mdeadbeef", null)
                .unlessDraftBelongsElsewhere(draftAccountId = null, writingAccountId = "accB"),
        )
    }

    @Test fun `an unknown writing account cannot prove sameness, so the pair is withheld`() {
        assertEquals(
            SendDraftTarget(null, null),
            SendDraftTarget("imap:accA:Drafts:41", 900L)
                .unlessDraftBelongsElsewhere(draftAccountId = "accA", writingAccountId = null),
        )
    }

    @Test fun `an account it cannot name is still another account`() {
        // Not a prefix, not a "looks like the same server": two sub-accounts of ONE login mint
        // the same draft ids (#31), which is the case where a sloppy comparison costs a message.
        assertEquals(
            SendDraftTarget(null, null),
            SendDraftTarget("imap:acc:Drafts:41", 900L)
                .unlessDraftBelongsElsewhere(draftAccountId = "acc", writingAccountId = "acc2"),
        )
    }

    @Test fun `the two guards compose the way the scheduled route chains them`() {
        // Fidelity and addressing are DIFFERENT questions and neither replaces the other: the
        // scheduled route asks both, in this order. A clean body under another account is still
        // withheld — and that is the leg an "unlessBodyIsLossy is enough" reading loses.
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = "imap:accA:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ).unlessBodyIsLossy(false)
                .unlessDraftBelongsElsewhere(draftAccountId = "accA", writingAccountId = "accB"),
        )
        assertEquals(
            SendDraftTarget("imap:accA:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = "imap:accA:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ).unlessBodyIsLossy(false)
                .unlessDraftBelongsElsewhere(draftAccountId = "accA", writingAccountId = "accA"),
        )
    }

    // -- which account the carried id was read under ----------------------------------------------

    @Test fun `a server id answers with the account it was frozen under`() {
        // Both fields are filled and they DIFFER: reading the local one instead passes the lease's
        // account off as the draft's, and the guard above then compares the wrong pair of names.
        assertEquals(
            "accA",
            carriedDraftAccountId(
                editingDraftId = "imap:accA:Drafts:41",
                serverDraftAccountId = "accA",
                localRowAccountId = "accZ",
            ),
        )
    }

    @Test fun `a reopened local row answers with the account its lease was taken under`() {
        // The route that arms NO server account at all: `editingDraftAccountId` stays null there
        // on purpose, and the pair the send carries is the row's `replacesEmailId` — a draft of the
        // account the row belongs to. Answering the server field here answers null, i.e. "unknown",
        // and the guard lets an id of A ride a send made as B.
        assertEquals(
            "accZ",
            carriedDraftAccountId(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d1",
                serverDraftAccountId = null,
                localRowAccountId = "accZ",
            ),
        )
    }

    @Test fun `a local id answers off the lease even when a server account is also set`() {
        // The discriminator is the ID, never "whichever field happens to be filled": a `?:` would
        // be right by coincidence today and wrong in silence the day a route arms both.
        assertEquals(
            "accZ",
            carriedDraftAccountId(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d2",
                serverDraftAccountId = "accA",
                localRowAccountId = "accZ",
            ),
        )
    }

    @Test fun `no id at all answers no account`() {
        assertEquals(
            null,
            carriedDraftAccountId(
                editingDraftId = null,
                serverDraftAccountId = "accA",
                localRowAccountId = null,
            ),
        )
    }

    @Test fun `the composer's pair and its account are read off the same id`() {
        // The witness for the pair above: what `sendDraftTarget` answers with, and what
        // `carriedDraftAccountId` names, must be the SAME draft — here the row's server copy,
        // which belongs to the account the row was leased under and not to any server account the
        // composer holds. Read apart, the guard would compare A's name with B's draft.
        val target = sendDraftTarget(
            editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d3",
            editingDraftUidValidity = null,
            replacedServerDraftId = "imap:accZ:Drafts:41",
            replacedServerDraftUidValidity = 900L,
        )
        val account = carriedDraftAccountId(
            editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d3",
            serverDraftAccountId = null,
            localRowAccountId = "accZ",
        )
        assertEquals(SendDraftTarget("imap:accZ:Drafts:41", 900L), target)
        assertEquals("accZ", account)
        assertEquals(
            SendDraftTarget("imap:accZ:Drafts:41", 900L),
            target.unlessDraftBelongsElsewhere(draftAccountId = account, writingAccountId = "accZ"),
        )
        assertEquals(
            SendDraftTarget(null, null),
            target.unlessDraftBelongsElsewhere(draftAccountId = account, writingAccountId = "accB"),
        )
    }

    // -- what closing the screen gives back ------------------------------------------------------

    @Test fun `a send in flight gives NOTHING back, neither the row nor the queue`() {
        // The defect this closes. The send owns the message and consumes both itself; releasing
        // the local row from under it puts it back to PENDING, the upload worker takes it, and a
        // copy of the message that has just gone out is filed in the server's Drafts — which the
        // startup re-arm then keeps putting back.
        assertEquals(
            AbandonPlan(null, null, null),
            abandonPlan(
                sending = true,
                emptying = false,
                parkOutbox = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d1",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `a composer with no queued row still gives its own draft back`() {
        // The other half, and the reason the guard cannot simply be moved to the bottom: every
        // reopened local draft has a null outbox id, and a release written under the outbox `?:
        // return` would never run — the row would stay EDITING for ever, invisible to the upload
        // worker and to the startup re-arm alike.
        assertEquals(
            AbandonPlan(LOCAL_DRAFT_ID_PREFIX + "d5", null, null),
            abandonPlan(
                sending = false,
                emptying = false,
                parkOutbox = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d5",
                outboxId = null,
            ),
        )
    }

    @Test fun `both are given back when both are held and nothing is in flight`() {
        assertEquals(
            AbandonPlan(LOCAL_DRAFT_ID_PREFIX + "d6", 42L, null),
            abandonPlan(
                sending = false,
                emptying = false,
                parkOutbox = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d6",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `a queued row with no local draft behind it is given back on its own`() {
        assertEquals(
            AbandonPlan(null, 7L, null),
            abandonPlan(sending = false, emptying = false, parkOutbox = false, localDraftId = null, outboxId = 7L),
        )
    }

    @Test fun `a send in flight keeps the local row even when there is no queued row`() {
        assertEquals(
            AbandonPlan(null, null, null),
            abandonPlan(sending = true, emptying = false, parkOutbox = false, localDraftId = LOCAL_DRAFT_ID_PREFIX + "d7", outboxId = null),
        )
    }

    @Test fun `emptying the draft gives the phone's own row back to nobody`() {
        // The close that accompanies a DELETION, and it must hand the row to no one. Given back,
        // it goes PENDING and its upload is re-armed at the row's own instant — for a draft saved
        // with no network, NOW — so the text the user has just emptied races the delete to the
        // server and is filed in Drafts while she reads that the deletion failed.
        //
        // And it is decided HERE rather than by blanking the composer's field before the call,
        // which is where it lived: one field cannot carry both "which row I hold" and "give nothing
        // back this once". Blanked, a submit() that FAILED left the row EDITING with nobody left to
        // release it — never uploaded again, and not a word on screen.
        assertEquals(
            AbandonPlan(null, 42L, null),
            abandonPlan(
                sending = false,
                emptying = true,
                parkOutbox = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d9",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `a send in flight beats an empty save, and gives back nothing at all`() {
        // THE ORDER OF THE TWO GUARDS, and nothing else pins it: swap the two `return`s and this
        // is the only case that changes. `sending` must be read FIRST. Answered as an emptying
        // instead, the queued outbox row goes back to the QUEUE — the very row the send in flight is
        // consuming — and the same message is delivered twice (#70).
        assertEquals(
            AbandonPlan(null, null, null),
            abandonPlan(
                sending = true,
                emptying = true,
                parkOutbox = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d8",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `emptying still puts the queued row back in the queue`() {
        // The two are independent, and only the local row is withheld: nothing is being saved, so a
        // message reopened from the outbox is not being moved anywhere and goes back to the queue
        // rather than evaporating on an empty save (#70).
        assertEquals(
            AbandonPlan(null, 7L, null),
            abandonPlan(sending = false, emptying = true, parkOutbox = false, localDraftId = null, outboxId = 7L),
        )
    }

    // -- what closing over a LOST body does to the queued row ------------------------------------

    @Test fun `closing over a lost body parks the queued row instead of giving it back`() {
        // The defect this closes. The send route refuses a lost body over a queued row with
        // "Close this screen and reopen the message" — and closing gave the row back to the queue,
        assertEquals(
            AbandonPlan(LOCAL_DRAFT_ID_PREFIX + "d10", null, 42L),
            abandonPlan(
                sending = false,
                emptying = false,
                parkOutbox = true,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d10",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `a queued row parked on close is parked, and not ALSO given back`() {
        // One row, one door: a plan that named it in both slots would park it and then re-queue
        // it, or the other way round, depending on which coroutine lands first.
        val plan = abandonPlan(sending = false, emptying = false, parkOutbox = true, localDraftId = null, outboxId = 7L)
        assertEquals(AbandonPlan(null, null, 7L), plan)
        assertNull("the row must not be handed to releaseOutboxEdit as well", plan.outboxToRelease)
    }

    @Test fun `a send in flight beats the park, and gives back nothing at all`() {
        // The send owns the row and consumes it when it lands (consumeEditingOutbox); parking it
        // from under the send would leave a FAILED row beside a message that went out.
        assertEquals(
            AbandonPlan(null, null, null),
            abandonPlan(
                sending = true,
                emptying = false,
                parkOutbox = true,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d11",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `emptying withholds the local row and still parks the queued one`() {
        // `emptying` only ever decides the phone's own row; the queued row keeps the answer the
        // lost body gave it.
        assertEquals(
            AbandonPlan(null, null, 42L),
            abandonPlan(
                sending = false,
                emptying = true,
                parkOutbox = true,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d12",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `there is nothing to park when no queued row is held`() {
        assertEquals(
            AbandonPlan(LOCAL_DRAFT_ID_PREFIX + "d13", null, null),
            abandonPlan(sending = false, emptying = false, parkOutbox = true, localDraftId = LOCAL_DRAFT_ID_PREFIX + "d13", outboxId = null),
        )
    }

    // -- who gets the row back when a SAVE is over -----------------------------------------------

    @Test fun `a save that went through hands the row back, or it stays EDITING for ever`() {
        // The defect this closes. `abandon()` is the only other release and only the CANCEL
        // of the screen calls it: a save closes on ComposeState.Done without passing through, on
        assertEquals(
            "the row must go back to the upload worker. Under the SAME account this is a real " +
                "no-op — the save already rewrote the row PENDING and giveLocalDraftEditBack " +
                "refuses any state but EDITING — and under ANOTHER account it is the only " +
                "thing left that can release it",
            LOCAL_DRAFT_ID_PREFIX + "d1",
            localDraftLeaseAfterSave(LOCAL_DRAFT_ID_PREFIX + "d1", saved = true),
        )
    }

    @Test fun `a save that failed keeps the row, or the worker uploads the text being typed`() {
        // And giving it back on the failure is NOT the fix — word for word abandonPlan's
        // own argument, one gesture along. The composer is still open, the user still holds the
        assertNull(
            "a failed save releases NOTHING: the screen is still open on that row",
            localDraftLeaseAfterSave(LOCAL_DRAFT_ID_PREFIX + "d2", saved = false),
        )
    }

    @Test fun `a composer holding no row of its own releases nothing on a save that went through`() {
        // The lease is read AS IT STANDS at the end of the gesture, never captured at the start:
        // on the empty-save routes the row is consumed INSIDE the gesture, so what is read here is
        // null and must stay null. Released from a stale id it goes back to PENDING, and the
        // upload worker files in the server's Drafts the draft that was just deleted, for good.
        assertNull(
            "nothing is held, so nothing may be handed to the upload worker",
            localDraftLeaseAfterSave(null, saved = true),
        )
    }

    @Test fun `a save that failed with no row held releases nothing either`() {
        // The weak one of the four, and said so rather than dressed up: `(null, false)` answers
        // null under every variant of this shape, inversion included, so it falsifies nothing on
        // its own. It is here to close the table — a decision that started FABRICATING an id out
        // of a composer that holds none is the only thing it can catch.
        assertNull(
            "nothing is held and nothing went through: there is no id to hand anybody",
            localDraftLeaseAfterSave(null, saved = false),
        )
    }
}
