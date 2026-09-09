package app.sterna.ui.message

import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.mail.ReadReceiptPreview
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.imap.CryptoKind
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-receipt decision, EXECUTED — the whole truth table of [offeredReadReceipt], the capture
 */
class ReadReceiptOfferTest {

    private val asked = ReadReceiptRequest(
        receiptTo = listOf("sender@example.org"),
        originalSubject = "Quarterly report",
        originalMessageId = "<abc@example.org>",
        deliveredTo = "alias@mine.example",
    )

    // -- 1. the truth table -----------------------------------------------------------------------

    /** The ONE yes: switched on, the reader settled on it, nobody has answered, and somebody is
     *  named. */
    @Test fun `a message read by a person, with the switch on, may ask`() {
        assertEquals(
            asked,
            offeredReadReceipt(
                ReadReceiptSetting.ON,
                ReadMarking.READER_SETTLE_UNREAD,
                asked,
                answered = false,
            ),
        )
    }

    /**
     * The setting nobody has answered yet is NOT the setting turned on. This is the row the
     */
    @Test fun `a setting still being read asks nothing`() {
        assertNull(
            offeredReadReceipt(
                ReadReceiptSetting.NOT_LOADED,
                ReadMarking.READER_SETTLE_UNREAD,
                asked,
                answered = false,
            ),
        )
    }

    /** Off is off: the default, and what the overwhelming majority of installs will always be. */
    @Test fun `the switch off asks nothing`() {
        assertNull(
            offeredReadReceipt(
                ReadReceiptSetting.OFF,
                ReadMarking.READER_SETTLE_UNREAD,
                asked,
                answered = false,
            ),
        )
    }

    /**
     * The eight other ways a message becomes read. A swipe, a multi-select, "mark all read", a
     */
    @Test fun `a message marked read by anything but the reader asks nothing`() {
        assertNull(offeredReadReceipt(ReadReceiptSetting.ON, ReadMarking.OTHER, asked, answered = false))
    }

    /**
     * Reopening a message that was already read asks nothing — the trigger is the transition, and
     */
    @Test fun `a message that was already read asks nothing`() {
        assertNull(
            offeredReadReceipt(
                ReadReceiptSetting.ON,
                ReadMarking.READER_SETTLE_ALREADY_READ,
                asked,
                answered = false,
            ),
        )
    }

    /** No such header at all: the answer for very nearly every message ever opened. */
    @Test fun `a message with no such header asks nothing`() {
        assertNull(
            offeredReadReceipt(
                ReadReceiptSetting.ON,
                ReadMarking.READER_SETTLE_UNREAD,
                null,
                answered = false,
            ),
        )
    }

    /**
     * The header was there and named nothing usable — `<>`, a bare word, a group with no
     */
    @Test fun `a header naming no usable address asks nothing, and does not throw`() {
        assertNull(
            offeredReadReceipt(
                ReadReceiptSetting.ON,
                ReadMarking.READER_SETTLE_UNREAD,
                asked.copy(receiptTo = emptyList()),
                answered = false,
            ),
        )
    }

    /**
     * REPLAYING THE DECISION IS NOT FORGETTING THE ANSWER.
     */
    @Test fun `a question the reader has answered is not asked again`() {
        assertNull(
            "a refusal, or a receipt already queued, ends the question for this message",
            offeredReadReceipt(
                ReadReceiptSetting.ON,
                ReadMarking.READER_SETTLE_UNREAD,
                asked,
                answered = true,
            ),
        )
    }

    /** Which of the three answers count as "had their say", run rather than described. */
    @Test fun `only a reader who has said something has answered`() {
        assertFalse("nothing said yet", ReadReceiptAnswer.PENDING.answered)
        assertTrue("said no", ReadReceiptAnswer.DECLINED.answered)
        assertTrue("said yes", ReadReceiptAnswer.ACCEPTED.answered)
    }

    /** The rest of the table, spelled out: every combination that is not the single yes. */
    @Test fun `the table has exactly one yes`() {
        val yeses = ReadReceiptSetting.entries.flatMap { setting ->
            ReadMarking.entries.flatMap { marking ->
                listOf(false, true).map { answered ->
                    Triple(
                        "$setting + $marking + answered=$answered",
                        offeredReadReceipt(setting, marking, asked, answered) != null,
                        answered,
                    )
                }
            }
        }.filter { it.second }.map { it.first }

        assertEquals(
            "exactly one of the eighteen (setting × marking × answered) triples may raise the " +
                "question: the switch turned on, the reader's own unread → read transition, and " +
                "nobody having answered yet",
            listOf("ON + READER_SETTLE_UNREAD + answered=false"),
            yeses,
        )
    }

    // -- 2. the capture ---------------------------------------------------------------------------

    /**
     * `deliveredTo` is carried into the request, and it is the address the message ARRIVED at —
     */
    @Test fun `the capture carries the address the message arrived at`() {
        val request = readReceiptRequest(
            email(
                dispositionNotificationTo = "Ann Lee <ann@example.org>",
                subject = "Quarterly report",
                messageId = listOf("<first@example.org>", "<second@example.org>"),
            ),
            deliveredTo = "alias@mine.example",
            outgoingSubject = "Quarterly report",
        )

        assertEquals("alias@mine.example", request.deliveredTo)
        assertEquals(listOf("ann@example.org"), request.receiptTo)
        assertEquals("Quarterly report", request.originalSubject)
        assertEquals(
            "the message's own id is the FIRST one the header carries, not the last",
            "<first@example.org>",
            request.originalMessageId,
        )
    }

    /** Nothing is invented when the caller does not know the address: null travels as null, and
     *  the repository's own fallback (identity, then login) is what answers. */
    @Test fun `an unknown delivery address stays unknown`() {
        assertNull(
            readReceiptRequest(email(dispositionNotificationTo = "ann@example.org"), null, "Hi")
                .deliveredTo,
        )
    }

    /** A message with no ids at all answers null rather than throwing on an empty list. */
    @Test fun `a message with no id of its own captures none`() {
        assertNull(
            readReceiptRequest(email(dispositionNotificationTo = "ann@example.org"), null, "Hi")
                .originalMessageId,
        )
    }

    /** A message carrying no such header captures an empty list, which asks nothing (above). */
    @Test fun `a message with no header captures no address`() {
        assertEquals(
            emptyList<String>(),
            readReceiptRequest(email(), "me@mine.example", "Hi").receiptTo,
        )
    }

    /**
     * THE SUBJECT THAT LEAVES THE DEVICE IS THE COVER ONE, and the one on the [Email] is not
     */
    @Test fun `the receipt carries the cover subject, never the one on the message shown`() {
        val request = readReceiptRequest(
            email(
                dispositionNotificationTo = "ann@example.org",
                subject = "Quarterly budget review",
            ),
            deliveredTo = "me@mine.example",
            outgoingSubject = "[...]",
        )

        assertEquals("[...]", request.originalSubject)
        assertFalse(
            "the protected subject must not appear anywhere in what gets sent: $request",
            "Quarterly budget review" in request.toString(),
        )
    }

    /**
     * No fallback when the cover is unknown. A null subject is a receipt titled "Read" with no
     */
    @Test fun `an unknown cover subject stays unknown, and does not fall back to the screen`() {
        assertNull(
            readReceiptRequest(
                email(
                    dispositionNotificationTo = "ann@example.org",
                    subject = "Quarterly budget review",
                ),
                deliveredTo = "me@mine.example",
                outgoingSubject = null,
            ).originalSubject,
        )
    }

    // -- 3. the two directions the replay has to get right -----------------------------------------

    /**
     * The setting changing under a standing question, both ways, through the ONE decision that is
     */
    @Test fun `the switch moving takes the question away and puts it back`() {
        assertNull(
            "turned off with a banner up, the question must go: the reader has just said they do " +
                "not want to be asked",
            offeredReadReceipt(
                ReadReceiptSetting.OFF,
                ReadMarking.READER_SETTLE_UNREAD,
                asked,
                answered = false,
            ),
        )
        assertNotNull(
            "turned on with the message already open, the question must appear — nothing else " +
                "will happen on that message: it is read, and the pager will not settle again",
            offeredReadReceipt(
                ReadReceiptSetting.ON,
                ReadMarking.READER_SETTLE_UNREAD,
                asked,
                answered = false,
            ),
        )
    }

    // -- 4. what the reader is told after saying yes -----------------------------------------------

    /**
     * The preview is RELAYED, never rebuilt. The strings below are deliberately not the ones
     */
    @Test fun `what was queued is what the state carries`() {
        val queued = ReadReceiptPreview(
            subject = "not a wording this app builds",
            body = "nor is this — it comes back from the row that was queued",
        )

        val state = readReceiptSendOutcome(queued)

        assertSame(queued, (state as ReadReceiptState.Queued).preview)
    }

    /** Nothing queued is a failure, said out loud: not Idle (the button back as if nothing
     *  happened) and not Queued (naming an outbox row that does not exist). */
    @Test fun `nothing queued is reported as a failure`() {
        assertEquals(ReadReceiptState.Failed, readReceiptSendOutcome(null))
    }

    // -- 5. what an OPEN is allowed to write into the cover ---------------------------------------

    /**
     * THE ROW THIS WHOLE FUNCTION EXISTS FOR — re-opening a message decrypted earlier in this
     */
    @Test fun `re-opening a decrypted message does not overwrite the cover with the protected subject`() {
        val held = coverSubjectAfterOpen(
            held = "[...]",
            opened = email(subject = "Quarterly budget review"),
            crypto = decrypted,
        )

        assertEquals("[...]", held)
        assertNotEquals("Quarterly budget review", held)
    }

    /** Locked is still the ENVELOPE — nothing has been decrypted yet, so its subject is the cover
     * and it is the freshest read of it there is. Not swept up by a wider guard. */
    @Test fun `an encrypted message that is still locked hands over its own subject`() {
        assertEquals(
            "Invoice 4021",
            coverSubjectAfterOpen("[...]", email(subject = "Invoice 4021"), MessageCrypto.Locked(CryptoKind.PGP_ENCRYPTED)),
        )
    }

    /** Ordinary mail, which is very nearly all of it: the fetched original wins, as it always did. */
    @Test fun `an ordinary message hands over its own subject`() {
        assertEquals(
            "Lunch on Thursday",
            coverSubjectAfterOpen("stale row subject", email(subject = "Lunch on Thursday"), null),
        )
    }

    /** A null never erases what the cached row already found — in every crypto state. */
    @Test fun `an open with no subject at all keeps what is held`() {
        assertEquals("[...]", coverSubjectAfterOpen("[...]", email(subject = null), null))
        assertEquals(
            "[...]",
            coverSubjectAfterOpen("[...]", email(subject = null), MessageCrypto.Locked(CryptoKind.PGP_SIGNED)),
        )
        assertEquals("[...]", coverSubjectAfterOpen("[...]", email(subject = null), decrypted))
    }

    /** And no fallback the other way either: nothing held plus a decrypted open is NOTHING. The
     *  receipt then goes out untitled, which loses nothing and leaks nothing. */
    @Test fun `nothing held and a decrypted open stays nothing`() {
        assertNull(coverSubjectAfterOpen(null, email(subject = "Quarterly budget review"), decrypted))
    }

    private val decrypted = MessageCrypto.Decrypted(
        signature = PgpSignatureState.NONE,
        signatureUserId = null,
        signatureKeyId = 0L,
        wasEncrypted = true,
    )

    private fun email(
        dispositionNotificationTo: String? = null,
        subject: String? = null,
        messageId: List<String> = emptyList(),
    ) = Email(
        id = "m1",
        subject = subject,
        messageId = messageId,
        dispositionNotificationTo = dispositionNotificationTo,
    )
}
