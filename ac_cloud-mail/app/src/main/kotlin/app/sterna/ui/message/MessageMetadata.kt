package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox

/**
 * What the sender surface may say about a message, decided as data so it can be executed by a test
 * rather than looked at on a screen.
 *
 * Everything here answers ONE question — "is this message what it claims to be?" — from the JMAP
 * model and nothing else. Nothing is invented, nothing is guessed, and a field the message does not
 * carry produces NO ROW. An empty "DKIM:" line is worse than no line: it reads as a verdict.
 */

/** One labelled line of the metadata block. [value] is never blank — see [messageMetadata]. */
class MetadataRow(val label: MetadataLabel, val value: String)

/**
 * Which line, as an enum rather than a string, so the caller resolves the localised label and this
 * file stays testable without a Context.
 */
enum class MetadataLabel {
    /** RFC 8621 `receivedAt` — when the SERVER took delivery. The one timestamp not written by the
     *  sender, and therefore the only one worth calling true. */
    RECEIVED_AT,

    /** The `Date:` header — when the sender SAYS it was written. Shown only when it disagrees with
     *  [RECEIVED_AT] by more than [CLOCK_SKEW_MS]: a message dated three days before it arrived is
     *  either a queue that stalled or a forgery, and either way the reader should see both numbers. */
    SENT_AT,

    /** `Reply-To` — where an answer actually goes. A reply-to that is not the sender is normal on a
     *  list and is the whole trick of a display-name forgery, so it is never folded into "From". */
    REPLY_TO,

    /** `Return-Path` — the ENVELOPE sender, written by the receiving server, not by the composer of
     *  the message. Where a bounce goes, and the classic disagreement with a forged `From`. */
    RETURN_PATH,

    /** `Authentication-Results` — the receiving server's own SPF / DKIM / DMARC verdicts. THE line
     *  this whole block exists for. Shown verbatim: summarising it into a tick would be this app
     *  passing judgement on a judgement it did not make. */
    AUTHENTICATION,

    /** `List-Id` — which mailing list this came through, when it came through one. */
    LIST_ID,

    /** `Message-ID` — the message's own identity, and what a bug report has to quote. */
    MESSAGE_ID,
}

/**
 * The header names read for the routing/identity rows, lowercased for matching.
 *
 * These are NOT requested by the reader's normal body fetch. They are read off the SAME on-demand
 * `headers` fetch the "View headers" action already makes (RFC 8621 §4.1.3), so this feature adds
 * no property to the fetch every message pays for, and no new way for a server to reject one.
 */
private const val H_DATE = "date"
private const val H_RETURN_PATH = "return-path"
private const val H_AUTH_RESULTS = "authentication-results"
private const val H_LIST_ID = "list-id"

/**
 * How far the sender's `Date:` may sit from the server's `receivedAt` before both are worth
 * showing. Five minutes: clocks drift and mail queues, and a row that appears on half of all normal
 * mail teaches the reader to ignore the block it lives in.
 */
const val CLOCK_SKEW_MS = 5 * 60 * 1000L

/**
 * The metadata rows for [email], given [headers] if the on-demand header fetch has answered.
 *
 * [sentAtMillis] and [receivedAtMillis] are parsed by the caller (the date parsers live in the app's
 * own util and need no Context), so this stays a pure decision over values.
 *
 * A row is emitted only when the message HAS the field. That is the rule, and it is why this
 * returns a list rather than a fixed record: the reader must not be shown an empty DKIM line, an
 * empty Return-Path or a "Sent: —", each of which reads as a finding rather than as an absence.
 */
fun messageMetadata(
    email: Email,
    headers: List<EmailHeader>,
    receivedAtMillis: Long?,
    sentAtMillis: Long?,
    formatTime: (Long) -> String,
): List<MetadataRow> {
    val rows = mutableListOf<MetadataRow>()
    fun add(label: MetadataLabel, value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { rows += MetadataRow(label, it) }
    }
    add(MetadataLabel.RECEIVED_AT, receivedAtMillis?.let(formatTime))
    // Only when the two disagree beyond ordinary skew. Shown side by side and never instead of the
    // received time: the sender's clock is the sender's claim.
    if (sentAtMillis != null && receivedAtMillis != null &&
        kotlin.math.abs(sentAtMillis - receivedAtMillis) > CLOCK_SKEW_MS
    ) {
        add(MetadataLabel.SENT_AT, formatTime(sentAtMillis))
    }
    // Only when it is not simply the sender again — a Reply-To equal to From says nothing.
    val from = email.from.map { it.email.lowercase() }.toSet()
    email.replyTo.filter { it.email.lowercase() !in from }
        .takeIf { it.isNotEmpty() }
        ?.let { add(MetadataLabel.REPLY_TO, it.joinToString(", ") { a -> formatAddress(a) }) }
    add(MetadataLabel.RETURN_PATH, headers.lastValueOf(H_RETURN_PATH))
    add(MetadataLabel.AUTHENTICATION, headers.lastValueOf(H_AUTH_RESULTS))
    add(MetadataLabel.LIST_ID, headers.lastValueOf(H_LIST_ID))
    add(MetadataLabel.MESSAGE_ID, email.messageId.firstOrNull()?.let { "<$it>" })
    return rows
}

/**
 * The LAST occurrence of [name].
 *
 * Not the first, and this matters for exactly the headers above. `Authentication-Results` and
 * `Received` are prepended by each hop, so the FIRST one in the list is the outermost — which on a
 * forwarded message is a relay the reader does not trust, while the last is the one their own
 * server wrote. Reading the first would show an attacker's own claim about their own message.
 */
private fun List<EmailHeader>.lastValueOf(name: String): String? =
    lastOrNull { it.name.trim().lowercase() == name }?.value

/** Whether the `Date:` header is worth parsing at all, i.e. whether the header fetch found one. */
fun sentAtHeader(headers: List<EmailHeader>): String? = headers.lastValueOf(H_DATE)

/**
 * `receivedAt` as epoch millis. JMAP states it as a UTC ISO-8601 instant (RFC 8621 §1.4), so this
 * is the strict parse and nothing else; a value that does not parse yields null, and the row is
 * then simply absent rather than shown as a wrong time.
 */
fun parseIsoMillis(iso: String?): Long? = iso?.let {
    runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
}

/**
 * The `Date:` header as epoch millis. RFC 5322 §3.3, which java.time spells RFC_1123 — and which
 * real mail spells loosely, so a failure to parse is expected and answered with null rather than
 * with an exception. The row it feeds is optional by design.
 */
fun parseHeaderDateMillis(value: String?): Long? = value?.trim()?.let {
    runCatching {
        java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    }.getOrNull()
}

/**
 * An address in the form a mail client pastes into a recipient field: `Display Name <a@b.example>`,
 * or the bare address when there is no name — RFC 5322 §3.4 `name-addr` / `addr-spec`.
 *
 * The display name is QUOTED when it holds any of the RFC's specials, because a name with a comma
 * in it pasted unquoted becomes two recipients, and the second is a bounce. Quotes and backslashes
 * inside the name are escaped for the same reason. Nothing is dropped: a name that needs quoting is
 * still the sender's name, and silently deleting the comma out of "Doe, Jane" gets the paste wrong
 * in a way nobody would notice.
 */
fun formatAddress(address: EmailAddress): String {
    val name = address.name?.trim().orEmpty()
    if (name.isEmpty()) return address.email
    val needsQuoting = name.any { it in SPECIALS }
    val rendered = if (needsQuoting) {
        "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } else {
        name
    }
    return "$rendered <${address.email}>"
}

/**
 * RFC 5322 §3.2.3 specials, plus the tab.
 *
 * The SPACE is deliberately not here. A display-name is a `phrase`, i.e. several atoms separated by
 * spaces, so "Ada Lovelace" is legal unquoted and quoting it would put quotation marks around every
 * ordinary human name that gets copied. A tab is folding whitespace and does not survive a paste
 * unquoted, so it stays.
 */
private const val SPECIALS = "()<>[]:;@\\,.\"\t"

// ---- tags: the two kinds, and they are not the same kind ------------------------------------
//
// PART THREE AND PART FIVE AGREE HERE, and this comment is where they agree.
//
// A JMAP message carries two independent multi-valued things, and the reader shows both because
// calling either one "the tags" would be a lie about which server state a tap changes:
//
//   mailboxIds  RFC 8621 §4.1.1. The SET of mailboxes the message belongs to, all at once. On this
//               fleet's server every drawer category is a mailbox, so this is what a "label" is.
//               A message is in Inbox AND Work AND Receipts; there is no single-folder slot, which
//               is why "move" is not an operation here and ADD / REMOVE are.
//   keywords    RFC 8621 §4.1.1 as well, but per-message FLAGS. `$seen`, `$flagged`, `$draft`,
//               `$answered`, `$forwarded` are protocol and belong to the star, the unread state and
//               the composer. Anything WITHOUT the leading `$` is a name the user or their
//               server-side filters chose, and that is the second kind of tag.

/** The `$`-prefixed keywords the protocol owns; never shown as a tag, never editable as one. */
private val SYSTEM_KEYWORD_PREFIX = "$"

/** What kind of thing a tag on screen is — and therefore which server state removing it edits. */
enum class TagKind {
    /** A mailbox this message belongs to: `mailboxIds/<id>`. Removing it can hide the message from
     *  a folder the user browses, so the surface confirms first. */
    MAILBOX,

    /** A user keyword: `keywords/<name>`. It marks the message, it never files it, so removing one
     *  hides nothing and needs no confirmation. */
    KEYWORD,
}

/** One tag as the reader draws it. [id] is the mailbox id or the keyword name — what a patch names. */
class MessageTag(val kind: TagKind, val id: String, val label: String)

/**
 * Every tag on this message, mailboxes first, each kind alphabetical.
 *
 * [currentMailboxId] — the folder the listing filed the message under — is EXCLUDED, and only that
 * one. The chip row sits under a sender in a folder the user opened it from; repeating that folder
 * back to them is noise, while every OTHER mailbox is the news the row exists to carry. Mailboxes
 * the account no longer has are dropped rather than shown as a raw id: an id is not a name, and a
 * tag nobody can read is not a tag.
 *
 * System keywords are dropped: `$seen` is the unread state, `$flagged` is the star beside this very
 * row, and showing them here would offer two controls for one piece of state.
 */
fun messageTags(
    mailboxIds: Set<String>,
    mailboxes: List<Mailbox>,
    keywords: Map<String, Boolean>,
    currentMailboxId: String?,
    nameOf: (Mailbox) -> String,
): List<MessageTag> {
    val byId = mailboxes.associateBy { it.id }
    val folders = mailboxIds
        .filter { it != currentMailboxId }
        .mapNotNull { id -> byId[id]?.let { MessageTag(TagKind.MAILBOX, id, nameOf(it)) } }
        .sortedBy { it.label.lowercase() }
    val flags = keywords.filterValues { it }.keys
        .filterNot { it.startsWith(SYSTEM_KEYWORD_PREFIX) }
        .sortedBy { it.lowercase() }
        .map { MessageTag(TagKind.KEYWORD, it, it) }
    return folders + flags
}

/** Whether removing [tag] is worth a confirmation: taking a message out of a mailbox can hide it
 *  from every folder the user browses, while clearing a keyword changes nothing about where it is. */
fun removalNeedsConfirming(tag: MessageTag): Boolean = tag.kind == TagKind.MAILBOX
