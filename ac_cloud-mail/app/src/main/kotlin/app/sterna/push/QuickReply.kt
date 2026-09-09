package app.sterna.push

import app.sterna.core.jmap.model.Email
import app.sterna.ui.compose.replyRecipient

/**
 * What is to become of the text typed into a new-mail notification's reply field. That text exists
 */
sealed interface QuickReplyOutcome {
    /** Nothing was typed: nothing to queue, nothing to give back, and the notification stays. */
    data object Ignore : QuickReplyOutcome

    /** Queue it in the Outbox — durable, retried, visible, editable. */
    data class Send(val to: String, val subject: String, val body: String) : QuickReplyOutcome

    /** Nobody to answer: give the text back to the user instead of dropping it. */
    data class HandBack(val subject: String, val body: String) : QuickReplyOutcome
}

sealed interface QuickReplyAct {
    data object DoNothing : QuickReplyAct

    data object Dismiss : QuickReplyAct

    data class HandBack(val subject: String, val body: String) : QuickReplyAct
}

/**
 * The decision AFTER the attempt, and the one that has to be a function: which way round these two
 */
fun quickReplyAct(outcome: QuickReplyOutcome, queued: Boolean): QuickReplyAct = when (outcome) {
    QuickReplyOutcome.Ignore -> QuickReplyAct.DoNothing
    is QuickReplyOutcome.HandBack -> QuickReplyAct.HandBack(outcome.subject, outcome.body)
    is QuickReplyOutcome.Send ->
        if (queued) QuickReplyAct.Dismiss else QuickReplyAct.HandBack(outcome.subject, outcome.body)
}

/**
 * The decision, pure: no network, no Android, no repository. [cached] is null when the local cache
 */
fun quickReplyOutcome(text: String?, cached: Email?): QuickReplyOutcome {
    if (text.isNullOrBlank()) return QuickReplyOutcome.Ignore
    val subject = replySubject(cached?.subject)
    // Trimmed like `enqueueSend` trims before its require(): an address that is only whitespace is
    // no address, and finding that out inside the send is how the text got lost.
    val to = cached?.let { replyRecipient(it) }.orEmpty().trim()
    if (to.isEmpty()) return QuickReplyOutcome.HandBack(subject, text)
    return QuickReplyOutcome.Send(to, subject, text)
}

private fun replySubject(subject: String?): String =
    subject.orEmpty().let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" }
