package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.mail.ReadReceiptHeader
import app.sterna.core.data.mail.ReadReceiptPreview
import app.sterna.core.jmap.model.Email

/**
 * Whether a message that asked for a read receipt (RFC 8098) may raise the question. Nothing here
 */
enum class ReadReceiptSetting {
    /** DataStore has not answered yet. Not "off": nothing is offered and nothing is remembered, so
     *  the next message settled on asks again. Treating this as "on" would put the question in front
     *  of every reader who never asked for it. */
    NOT_LOADED,

    /** Read and off (the default): the header is ignored, no banner, no menu entry, no trace. */
    OFF,

    /** Read and on: the app ASKS. Not "sends" — there is deliberately no third position. Turning the
     *  switch on is consent to be asked, never consent to answer. */
    ON,
}

/** How a message came to be marked read — the parameter that carries "a person had it on screen",
 * which no boolean called `read` can carry. Do not add a value meaning "close enough": the eight
 *  non-reader ways a message becomes read are all [OTHER]. */
enum class ReadMarking {
    /** The reader's pager SETTLED on this message and it was still unread until that moment — the
     *  unread → read transition, and the only one a human made. */
    READER_SETTLE_UNREAD,

    /** The reader settled on a message that was ALREADY read. No transition, so nothing is offered:
     * reopening must not re-ask, which keeps this work free of an "already answered" column. Marking
     *  unread again and reopening does ask again. */
    READER_SETTLE_ALREADY_READ,

    /** Every other way a message becomes read: a swipe, unfolding a conversation, a multi-select,
     *  "mark all read", a notification action, archive/delete, a Sieve `addflag`, or a `$seen` from
     *  another device. Nobody read anything here. */
    OTHER,
}

/**
 * What would be answered, captured whole at the moment the question is raised: read again when the
 */
data class ReadReceiptRequest(
    val receiptTo: List<String>,
    val originalSubject: String?,
    val originalMessageId: String?,
    val deliveredTo: String?,
)

/**
 * What [email] would have answered, had it been read by a person, at [deliveredTo], about
 */
fun readReceiptRequest(
    email: Email,
    deliveredTo: String?,
    outgoingSubject: String?,
): ReadReceiptRequest = ReadReceiptRequest(
    receiptTo = ReadReceiptHeader.recipients(email.dispositionNotificationTo),
    originalSubject = outgoingSubject,
    // The first id only: `Message-ID` is one field, the list shape is JMAP's, and RFC 8098's
    // `Original-Message-ID` names one message.
    originalMessageId = email.messageId.firstOrNull(),
    deliveredTo = deliveredTo,
)

/**
 * What the cover subject becomes after `openMessage` has answered: the [held] one, or the [opened]
 */
internal fun coverSubjectAfterOpen(held: String?, opened: Email, crypto: MessageCrypto?): String? =
    if (crypto is MessageCrypto.Decrypted) held else opened.subject ?: held

/**
 * The question to put to the reader, or null when there is none — the whole of the policy in one
 */
fun offeredReadReceipt(
    setting: ReadReceiptSetting,
    marking: ReadMarking,
    request: ReadReceiptRequest?,
    answered: Boolean,
): ReadReceiptRequest? {
    if (setting != ReadReceiptSetting.ON) return null
    if (marking != ReadMarking.READER_SETTLE_UNREAD) return null
    if (answered) return null
    if (request == null || request.receiptTo.isEmpty()) return null
    return request
}

/**
 * Whether the reader has already answered on this message. Its own type, not "the offer is null":
 */
enum class ReadReceiptAnswer {
    /** Nothing said yet: the question may be put, and put again. */
    PENDING,

    /** Said no. Nothing left, and nothing may raise it again on this message. */
    DECLINED,

    /** Said yes: a receipt was queued (or the attempt failed, which is not a second chance here). */
    ACCEPTED,
    ;

    /** What [offeredReadReceipt] is handed: the reader has had their say. */
    val answered: Boolean get() = this != PENDING
}

/** Lifecycle of the receipt on the open message. Session-only, like [UnsubscribeState]: remembering it
 * would be a column in the message table. There is no `Sent` — a receipt has one road, the durable
 *  outbox — and a state nothing can produce is a branch no test could reach. */
sealed interface ReadReceiptState {
    /** Nothing done: the question stands, if there is one. */
    data object Idle : ReadReceiptState

    /** The reader said yes and the queueing call is in flight (a database write, so: briefly). */
    data object Sending : ReadReceiptState

    /** In the outbox; delivery and retry are the outbox's business from here. [preview] is what
     * `sendReadReceipt` HANDED BACK, never rebuilt here: two sites deriving the same wording drift,
     *  and the banner would name one text while another leaves. */
    data class Queued(val preview: ReadReceiptPreview) : ReadReceiptState

    /** Nothing was queued: the send threw, or it found nothing to answer after all. */
    data object Failed : ReadReceiptState
}

/** What the reader is told after a send attempt. A null [preview] means nothing was queued, and the
 *  honest word is [ReadReceiptState.Failed] — not Idle, which would put the button back as if nothing
 *  had been pressed, nor Queued, which would name a row that does not exist. */
fun readReceiptSendOutcome(preview: ReadReceiptPreview?): ReadReceiptState =
    if (preview == null) ReadReceiptState.Failed else ReadReceiptState.Queued(preview)

/**
 * How TALL the strip is allowed to be, which outranks everything else about it. The measured header
 */
enum class ReadReceiptStripShape {
    /** The line is held to ONE line, so the row is exactly the button's height — the same height in
     *  every state wearing this shape, at every font scale. */
    BUTTON_ROW,

    /** The one shape allowed to grow: the line may wrap, because a failure has to say what happened,
     * and it is only reached AFTER a deliberate gesture. Not "no button": the button is drawn in
     *  every state, and here it is live again. */
    SENTENCE,
}

/** What the read-receipt strip draws in a given state, on the pattern of [UnsubscribeStripBody].
 * None of these strings leaves the device: the mail itself is fixed English built by
 *  `readReceiptPreview`, held out of `strings.xml` by `ReadReceiptMailTest`. */
enum class ReadReceiptStripBody(
    @StringRes val line: Int,
    /** The BUTTON's own label, which changes with the state — not a constant "Send" left greyed under
     *  a line saying the answer is already queued. And the button never leaves: replacing it with a
     *  bare [String] once spent is the measured height defect above. */
    @StringRes val button: Int,
    /** Whether [line] carries a `%1$s` — see [ReadReceiptStrip.named] for what fills it. */
    val names: Boolean,
    val shape: ReadReceiptStripShape,
) {
    /** The question: who asked to be told, a live button to answer, and a gesture to refuse. The only
     *  state that sends anything from a standing question, and it sends nothing on its own. */
    ASK(
        line = R.string.message_read_receipt_ask,
        button = R.string.message_read_receipt_send,
        names = true,
        shape = ReadReceiptStripShape.BUTTON_ROW,
    ),

    /** The reader said yes and the outbox row is being written (a database write, so: briefly). */
    SENDING(
        line = R.string.message_read_receipt_sending,
        button = R.string.message_read_receipt_send_sending,
        names = false,
        shape = ReadReceiptStripShape.BUTTON_ROW,
    ),

    /** In the outbox, and the furthest this app can honestly say — [ReadReceiptState] has no `Sent` and
     *  must not grow one. The button says what became of the gesture. */
    QUEUED(
        line = R.string.message_read_receipt_queued,
        button = R.string.message_read_receipt_send_queued,
        names = true,
        shape = ReadReceiptStripShape.BUTTON_ROW,
    ),

    /**
     * Nothing was queued. The button comes back: a refused enqueue or a dead identity is a retry,
     * not a dead end.
     */
    FAILED(
        line = R.string.message_read_receipt_failed,
        button = R.string.message_read_receipt_send,
        names = false,
        shape = ReadReceiptStripShape.SENTENCE,
    ),
    ;

    /** Whether the button may fire — `enabled`, and the same question `sendReadReceipt` re-runs before
     *  anything leaves. [FAILED] acts because nothing left the device. */
    val acts: Boolean get() = this == ASK || this == FAILED

    /** Whether the refusal is on offer: only while the question stands untouched, since once a receipt
     * is queued there is nothing left to refuse and nothing this app can recall. Deliberately not
     *  extended to [FAILED], which would teach `declineReadReceipt` a second job. */
    val declines: Boolean get() = this == ASK
}

/** Everything the strip draws, or null when there is no strip at all. [named] fills the line's `%1$s`
 *  and is CARRIED, not looked up: asking, who the receipt would go to; queued, the subject of the row
 * `sendReadReceipt` actually put in the outbox, handed back and never rebuilt here. */
data class ReadReceiptStrip(val body: ReadReceiptStripBody, val named: String)

/** See [ReadReceiptStrip] — null means the header draws nothing at all, not an empty row, and is the
 * answer for very nearly every message. This function re-derives none of it: [offeredReadReceipt]
 *  took the decision, and asking it twice is how two guards end up disagreeing. */
fun readReceiptStrip(offer: ReadReceiptRequest?, state: ReadReceiptState): ReadReceiptStrip? =
    when (state) {
        ReadReceiptState.Idle -> offer?.let {
            ReadReceiptStrip(ReadReceiptStripBody.ASK, it.receiptTo.joinToString(", "))
        }
        ReadReceiptState.Sending -> ReadReceiptStrip(ReadReceiptStripBody.SENDING, "")
        is ReadReceiptState.Queued -> ReadReceiptStrip(ReadReceiptStripBody.QUEUED, state.preview.subject)
        ReadReceiptState.Failed -> ReadReceiptStrip(ReadReceiptStripBody.FAILED, "")
    }
