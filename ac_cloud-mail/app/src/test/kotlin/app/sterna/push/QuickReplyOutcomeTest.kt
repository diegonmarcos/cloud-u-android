package app.sterna.push

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision behind a notification quick reply, EXECUTED — not re-derived. Every expectation
 */
class QuickReplyOutcomeTest {

    private val original = Email(
        id = "m1",
        subject = "Sprint notes",
        from = listOf(EmailAddress(name = "Alice", email = "alice@example.com")),
    )

    // --- Nothing typed: nothing to save, and nothing to take down ---

    @Test fun `whitespace is not a reply`() {
        assertEquals(QuickReplyOutcome.Ignore, quickReplyOutcome("   ", original))
    }

    @Test fun `no text at all is not a reply`() {
        assertEquals(QuickReplyOutcome.Ignore, quickReplyOutcome(null, original))
    }

    // --- The normal path: the cached row alone addresses the reply, no network involved ---

    @Test fun `the cached original gives the sender and a Re subject`() {
        assertEquals(
            QuickReplyOutcome.Send("alice@example.com", "Re: Sprint notes", "on my way"),
            quickReplyOutcome("on my way", original),
        )
    }

    @Test fun `a subject already answering is not answered twice`() {
        assertEquals(
            QuickReplyOutcome.Send("alice@example.com", "Re: Sprint notes", "ok"),
            quickReplyOutcome("ok", original.copy(subject = "Re: Sprint notes")),
        )
        // The existing rule is case-insensitive and keeps the subject exactly as it came.
        assertEquals(
            QuickReplyOutcome.Send("alice@example.com", "re: sprint notes", "ok"),
            quickReplyOutcome("ok", original.copy(subject = "re: sprint notes")),
        )
    }

    @Test fun `a message with no subject answers under the bare prefix`() {
        // Pinned as shipped today, trailing space included — this test is a lock, not a redesign.
        assertEquals(
            QuickReplyOutcome.Send("alice@example.com", "Re: ", "ok"),
            quickReplyOutcome("ok", original.copy(subject = null)),
        )
        assertEquals(
            QuickReplyOutcome.Send("alice@example.com", "Re: ", "ok"),
            quickReplyOutcome("ok", original.copy(subject = "")),
        )
    }

    @Test fun `the text is queued exactly as typed`() {
        val typed = "  see you at 5\n"
        assertEquals(
            QuickReplyOutcome.Send("alice@example.com", "Re: Sprint notes", typed),
            quickReplyOutcome(typed, original),
        )
    }

    // --- A Reply-To on the cached row sends the quick reply where the sender asked ---
    //
    // Executed, against literals: the notification's Reply field is the one place the user cannot
    // see the recipient before sending, so answering the set-aside From here is invisible.

    @Test fun `a quick reply goes to the Reply-To when the sender set one`() {
        val o = original.copy(replyTo = listOf(EmailAddress(email = "support@example.com")))
        assertEquals(
            QuickReplyOutcome.Send("support@example.com", "Re: Sprint notes", "on my way"),
            quickReplyOutcome("on my way", o),
        )
    }

    @Test fun `a quick reply without a Reply-To still goes to the sender`() {
        assertEquals(
            QuickReplyOutcome.Send("alice@example.com", "Re: Sprint notes", "on my way"),
            quickReplyOutcome("on my way", original.copy(replyTo = emptyList())),
        )
    }

    @Test fun `a Reply-To answers even when the message has no sender at all`() {
        // Daemon mail with a Reply-To and no From: there IS somewhere to answer, so the text is
        // queued rather than handed back.
        val o = original.copy(from = emptyList(), replyTo = listOf(EmailAddress(email = "ops@example.com")))
        assertEquals(
            QuickReplyOutcome.Send("ops@example.com", "Re: Sprint notes", "ack"),
            quickReplyOutcome("ack", o),
        )
    }

    // --- No recipient: the text comes back, and the notification must not be taken down ---

    @Test fun `an uncached message hands the text back`() {
        // "a notification implies a cached message" is FALSE — the cache can be evicted, or the
        // row can belong to an account whose credentials are gone.
        assertEquals(
            QuickReplyOutcome.HandBack("Re: ", "the text I typed"),
            quickReplyOutcome("the text I typed", null),
        )
    }

    @Test fun `a message with no sender hands the text back`() {
        // Reachable: Notifications.actionsFor offers Reply on a mail that has a subject and no
        // From at all (NotificationActionsTest, "no sender keeps its actions at sender and
        // subject") — daemon mail, or a malformed header.
        assertEquals(
            QuickReplyOutcome.HandBack("Re: Sprint notes", "the text I typed"),
            quickReplyOutcome("the text I typed", original.copy(from = emptyList())),
        )
        assertEquals(
            QuickReplyOutcome.HandBack("Re: Sprint notes", "the text I typed"),
            quickReplyOutcome("the text I typed", original.copy(from = listOf(EmailAddress(email = "")))),
        )
    }

    // --- After the attempt: which way round these two ends go IS the bug ---
    //
    // No source lint can hold this. A lint sees a `dismiss(…)` line and a `notifySendFailed(…)`
    // line and is satisfied whichever branch each sits in — swap them and the suite stays green
    // while a successful send raises a false "not sent" and a failed one wipes the text. So the
    // decision is a function, and the function is EXECUTED here against literals.

    @Test fun `a queued reply, and only a queued reply, takes the notification down`() {
        val send = QuickReplyOutcome.Send("alice@example.com", "Re: Sprint notes", "on my way")
        assertEquals(QuickReplyAct.Dismiss, quickReplyAct(send, queued = true))
    }

    @Test fun `a send that did not make it hands the text back`() {
        // The notification stays up too: it is the only other copy of what was typed.
        val send = QuickReplyOutcome.Send("alice@example.com", "Re: Sprint notes", "on my way")
        assertEquals(
            QuickReplyAct.HandBack("Re: Sprint notes", "on my way"),
            quickReplyAct(send, queued = false),
        )
    }

    @Test fun `nothing to answer is still a hand-back after the attempt`() {
        assertEquals(
            QuickReplyAct.HandBack("Re: ", "the text I typed"),
            quickReplyAct(QuickReplyOutcome.HandBack("Re: ", "the text I typed"), queued = false),
        )
    }

    @Test fun `nothing typed leaves everything exactly as it was`() {
        assertEquals(QuickReplyAct.DoNothing, quickReplyAct(QuickReplyOutcome.Ignore, queued = false))
        assertEquals(QuickReplyAct.DoNothing, quickReplyAct(QuickReplyOutcome.Ignore, queued = true))
    }

    @Test fun `an address that is only whitespace is no address`() {
        // MailRepository.enqueueSend trims, then require()s a non-empty recipient: a blank address
        // throws INSIDE the send, where the throw used to eat the text. It has to be caught here,
        // before the Outbox is asked for anything.
        assertEquals(
            QuickReplyOutcome.HandBack("Re: Sprint notes", "hello"),
            quickReplyOutcome("hello", original.copy(from = listOf(EmailAddress(email = "   ")))),
        )
    }
}
