package app.sterna.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector
import app.sterna.R
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.richBodyFrom
import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlEscapeMultiline
import app.sterna.core.data.text.htmlToText
import app.sterna.core.data.text.toHtml
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.send.SendOutbox
import app.sterna.util.isValidEmail
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Heuristic "forgot the attachment?" check on subject + body, in the app's languages: lower-cased,
 *  matched loosely (stems cover inflections), kept conservative to avoid false alarms. */
internal val ATTACHMENT_HINTS = listOf(
    // en
    "attach",
    // fr (stems cover plurals/inflections via substring match)
    "pièce jointe", "pièces jointes", "ci-joint",
    // de
    "anhang", "angehängt", "anbei", "beigefügt",
    // es
    "adjunt", // adjunto/adjunta/adjuntar
    // it
    "allegat", // allegato/allegata/allegati
    // pt
    "anexo", "anexa", "em anexo",
    // nl
    "bijlage", "bijgevoegd",
    // ru
    "вложени", "прикреп",
    // pl
    "załącznik", "załączeniu", "załączam",
)

internal fun mentionsAttachment(text: String): Boolean {
    val haystack = text.lowercase()
    return ATTACHMENT_HINTS.any { haystack.contains(it) }
}

/** The prebuilt "forwarded message" blocks: the original is carried to send time rather than
 *  flattened into the editable body, so the recipient keeps its formatting. */
internal data class ForwardedBlocks(val text: String, val html: String)

private const val FORWARD_HEADER = "---------- Forwarded message ----------"

/** The four field labels of the forwarded-message header, translated. The dashed line and the field
 *  order are a de-facto standard other clients recognise and are deliberately NOT translated (D7). */
internal data class ForwardLabels(
    val from: String = "From",
    val subject: String = "Subject",
    val date: String = "Date",
    val to: String = "To",
)

/** Build the text + HTML "forwarded message" blocks. [originalHtml], when non-null, is the original's
 *  HTML, verbatim except for [cleanForwardedHtml]'s rewrites; with none, the plain text is escaped. */
internal fun buildForwardedBlocks(
    from: String,
    subject: String,
    date: String,
    to: String,
    originalText: String,
    originalHtml: String?,
    /** Content-IDs whose inline image is actually carried by the forward; their `<img cid:>` is kept. */
    carriedCids: Set<String> = emptySet(),
    labels: ForwardLabels = ForwardLabels(),
): ForwardedBlocks {
    val text = buildString {
        append(FORWARD_HEADER).append('\n')
        append(labels.from).append(": ").append(from).append('\n')
        append(labels.subject).append(": ").append(subject).append('\n')
        append(labels.date).append(": ").append(date).append('\n')
        append(labels.to).append(": ").append(to).append("\n\n")
        append(originalText)
    }
    val html = buildString {
        append(FORWARD_HEADER).append("<br>")
        append(htmlEscape(labels.from)).append(": ").append(htmlEscape(from)).append("<br>")
        append(htmlEscape(labels.subject)).append(": ").append(htmlEscape(subject)).append("<br>")
        append(htmlEscape(labels.date)).append(": ").append(htmlEscape(date)).append("<br>")
        append(htmlEscape(labels.to)).append(": ").append(htmlEscape(to)).append("<br><br>")
        append(if (originalHtml != null) cleanForwardedHtml(originalHtml, carriedCids) else htmlEscapeMultiline(originalText))
    }
    return ForwardedBlocks(text, html)
}

/**
 * Sanitise an original HTML body for a forward: drop script/style/head, keep an inline image whose
 * Content-ID is in [carriedCids] (it is being re-attached) and turn any other `cid:` image into
 * "[image]" so it does not render broken. Everything else is kept verbatim.
 */
internal fun cleanForwardedHtml(html: String, carriedCids: Set<String> = emptySet()): String {
    val normalized = carriedCids.mapNotNull { it.trim().trim('<', '>').takeIf(String::isNotBlank) }.toSet()
    val noScripts = html.replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), "")
    return Regex("(?i)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']?\\s*cid:[^>]*>").replace(noScripts) { match ->
        val cid = imgTagCid(match.value)
        if (cid != null && cid in normalized) match.value else "[image]"
    }
}

/** The Content-ID referenced by an `<img src="cid:...">` tag (angle brackets stripped), or null. */
internal fun imgTagCid(imgTag: String): String? =
    Regex("(?i)\\bsrc\\s*=\\s*[\"']?\\s*cid:([^\"'>\\s]+)").find(imgTag)
        ?.groupValues?.get(1)?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }

/** The message's body as plain text: its text/plain part, else its HTML converted, else the one-line
 *  preview. Used for quoting a reply and for reopening a draft in the editor (#63). */
internal fun originalPlainText(o: Email): String {
    val (raw, isHtml) = bodySource(o)
    return if (isHtml) htmlToText(raw) else raw
}

/**
 * The body to work from, and whether it is HTML. HTML-only mail makes the server synthesise
 * `textBody` = the HTML part, so the "text" body can actually be HTML.
 *
 * `internal` since #149: the reader's plain-text mode needs exactly this decision, and a second copy
 * would drift into showing escaped markup under a "plain text" label.
 */
internal fun bodySource(o: Email): Pair<String, Boolean> {
    val textPart = o.textBody.firstOrNull()
    val raw = textPart?.partId?.let { o.bodyValues[it]?.value }
    if (!raw.isNullOrBlank()) return raw to textPart?.type.equals("text/html", ignoreCase = true)
    o.htmlContent()?.takeIf { it.isNotBlank() }?.let { return it to true }
    return o.preview.orEmpty() to false
}

/**
 * The original as it should be QUOTED in a reply: an HTML original keeps its depth ([htmlToQuotedText]),
 * a plain-text one carries its own markers, and either way it is cut at the sender's signature
 * delimiter (D4) — strictly, so "----------" never truncates. Only the QUOTE is treated this way:
 * reopening a draft and forwarding keep the whole body.
 */
internal fun quotedOriginalText(o: Email): String {
    val (raw, isHtml) = bodySource(o)
    return cutAtSignatureDelimiter(if (isHtml) htmlToQuotedText(raw) else raw)
}

/**
 * HTML→plain text for QUOTING, keeping the depth the original carried, or the history flattens to one
 * level and a three-message exchange is indistinguishable from a fresh reply (B1). One regex pass over
 * the open/close tags, deliberately NOT an HTML parser: mail HTML is hostile, and unbalanced tags only
 * cost a level of indentation.
 */
internal fun htmlToQuotedText(html: String): String {
    val out = StringBuilder()
    var depth = 0
    var last = 0

    fun emit(segment: String, at: Int) {
        val text = htmlToText(segment)
        if (text.isBlank()) return
        if (out.isNotEmpty()) out.append('\n')
        val marker = ">".repeat(at)
        out.append(text.lineSequence().joinToString("\n") { if (at == 0) it else "$marker $it" })
    }

    for (tag in BLOCKQUOTE_TAG.findAll(html)) {
        emit(html.substring(last, tag.range.first), depth)
        depth = if (tag.value.startsWith("</")) (depth - 1).coerceAtLeast(0) else depth + 1
        last = tag.range.last + 1
    }
    emit(html.substring(last), depth)
    return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
}

/**
 * The blockquote open/close tags, and the ONE place quoting depth is read from. `internal` since the
 * reader's quote fold needs exactly this notion of "level": a second copy would let the composer's ">"
 * markers and the fold button disagree about the first level of a reply chain.
 */
internal val BLOCKQUOTE_TAG = Regex("(?i)<blockquote\\b[^>]*>|</blockquote\\s*>")

/**
 * Add one quoting level to [line]: an already-quoted line gets a tighter ">" (so a reply to a reply
 * reads ">>", not "> >"), a fresh line the usual "> ".
 */
internal fun deepenQuote(line: String): String = if (line.startsWith(">")) ">$line" else "> $line"

/** [text] up to (excluding) the first line that is exactly "-- " or "--", trailing blanks trimmed. */
internal fun cutAtSignatureDelimiter(text: String): String {
    val lines = text.split("\n")
    val at = lines.indexOfFirst { it == SIGNATURE_DELIMITER || it == "--" || it == "-- \r" || it == "--\r" }
    if (at < 0) return text
    return lines.take(at).joinToString("\n").trimEnd()
}

/** The field a freshly opened composer puts the keyboard in. */
internal enum class ComposeFocus { RECIPIENTS, SUBJECT, BODY }

/**
 * Which field opens focused. The single rule, in one place — the composable only obeys it.
 *
 * A reopened draft and a reply already have their recipients, so they open on the body (#63);
 * everything else opened from inside the app opens on the recipients. A composer opened from OUTSIDE
 * with fields filled in is the exception (#83): the focus goes to the first field the link left empty.
 */
internal fun initialComposeFocus(
    isDraft: Boolean,
    isReply: Boolean,
    linkTo: String?,
    linkSubject: String?,
): ComposeFocus = when {
    isDraft || isReply -> ComposeFocus.BODY
    linkTo.isNullOrBlank() -> ComposeFocus.RECIPIENTS
    linkSubject.isNullOrBlank() -> ComposeFocus.SUBJECT
    else -> ComposeFocus.BODY
}

/**
 * Whether a composer opened with [restore] is really holding a queued outbox row (#96). [restore] is
 * the navigation argument and survives the app being killed; [restored] is the in-memory draft and
 * does NOT, so after a process death it is null while the argument still says `true` — which is what
 * titled an empty composer "Edit". A draft with no `editingOutboxId` is an undone send.
 */
internal fun holdsQueuedOutboxRow(restore: Boolean, restored: SendOutbox.ComposeDraft?): Boolean =
    restore && restored?.editingOutboxId != null

/**
 * Which queued row a composer opened with `restore=true` has to TAKE BACK itself — `null` for none.
 *
 * The two facts never both hold: [restored] present means `OutboxViewModel.edit` has already taken the
 * row EDITING, so the hand-over WINS; [outboxId] is the route's copy, the one thing that survives a
 * process death, and is read only when the hand-over is missing. Pure, and executed by
 * `ComposeTitleTest`: inverted, a live reopen takes its row a second time with nothing to say so.
 */
internal fun outboxRowToResume(restored: SendOutbox.ComposeDraft?, outboxId: Long?): Long? =
    if (restored == null) outboxId else null

/**
 * Whether a draft saved on the SERVER survives leaving this composer — what the dialog may not
 * announce as destroyed (#35). [draftId] is the navigation argument, preferred to whether the draft
 * could be READ: an offline reopen whose fetch failed still has its draft in Drafts.
 *
 * [restoredDraftId] is the hole it closes: a send started from a draft keeps that draft until
 * DELIVERY, so an undone send reopens with no draftId and a rule reading the route alone said
 * "Discard message?" over a draft that exists. Passed only when [restore] is set.
 */
internal fun savedDraftBehindScreen(draftId: String?, restoredDraftId: String?): Boolean =
    draftId != null || restoredDraftId != null

/** What the composer's top bar calls this message. Mapped to a string by the screen. */
internal enum class ComposeTitle { NEW, REPLY, FORWARD, DRAFT, OUTBOX_EDIT }

/**
 * What the composer is titled — the whole rule, out of the composable so it can be tested (#96).
 *
 * The order matters: a reopened draft says "Draft"; a forward carries the SAME [replyTo] as a reply,
 * so it must be caught before it; then a reply; then a queued message out of the Outbox, "Edit".
 *
 * [restore] and [editingOutbox] are BOTH needed (#96, defect 2): the argument survives the app being
 * killed while the in-memory draft does not, so `restore=true` alone titled an EMPTY composer "Edit".
 * An undone send deliberately says "New mail": undoing dropped its queued row.
 */
internal fun composeTitle(
    draftId: String?,
    mode: String?,
    replyTo: String?,
    restore: Boolean,
    editingOutbox: Boolean,
): ComposeTitle = when {
    draftId != null -> ComposeTitle.DRAFT
    // Both forwards (inline and as attachment): the top bar says the word the subject carries.
    composeOpening(mode).subjectPrefix == "Fwd:" -> ComposeTitle.FORWARD
    replyTo != null -> ComposeTitle.REPLY
    restore && editingOutbox -> ComposeTitle.OUTBOX_EDIT
    else -> ComposeTitle.NEW
}

/**
 * Which wording the "you are leaving without saving" dialog uses. [fromOutbox] is what the title and
 * the discard button key on — those two read the same whether or not a draft is on offer.
 */
internal enum class DiscardWording(val keepsMessage: Boolean, val fromOutbox: Boolean) {
    PLAIN(keepsMessage = false, fromOutbox = false),
    ENCRYPTED(keepsMessage = false, fromOutbox = false),

    /** A draft already saved on the server is behind this screen: leaving drops the edits only. */
    DRAFT(keepsMessage = true, fromOutbox = false),

    /** That same draft, with the padlock closed by hand: leaving still drops the edits only, and
     *  the body still has to explain why this one cannot be re-saved as a draft. */
    ENCRYPTED_DRAFT(keepsMessage = true, fromOutbox = false),

    /** Out of the outbox, with "Save draft" offered beside "Discard changes". */
    OUTBOX(keepsMessage = true, fromOutbox = true),

    /** Out of the outbox while encrypting, so only "Discard changes" and "Cancel" are offered. */
    OUTBOX_ENCRYPTED(keepsMessage = true, fromOutbox = true),
}

/**
 * What the leave dialog must say, which is not always "this message will be lost" (#70).
 * A message reopened from the Outbox never left it — it only sat in `EDITING` — so leaving hands it
 * straight back and only the EDITS are dropped; the wording may not promise DELIVERY, though, a send
 * whose auto-retry is spent parking back at FAILED. [mayKeepDraft] is [draftSaveAllowed], and where
 * the message GOES outranks why it cannot become a draft, so [editingOutbox] is tested first — but the
 * outbox case still splits on it, "Save draft" CONSUMING the queued row.
 * [editingDraft] is the second thing that survives this screen (#35, #127), and
 * [DiscardWording.keepsMessage] carries the remaining fact, so the title and the button key on it.
 */
internal fun discardWording(
    editingOutbox: Boolean,
    mayKeepDraft: Boolean,
    editingDraft: Boolean,
): DiscardWording = when {
    editingOutbox && mayKeepDraft -> DiscardWording.OUTBOX
    editingOutbox -> DiscardWording.OUTBOX_ENCRYPTED
    !mayKeepDraft && editingDraft -> DiscardWording.ENCRYPTED_DRAFT
    !mayKeepDraft -> DiscardWording.ENCRYPTED
    editingDraft -> DiscardWording.DRAFT
    else -> DiscardWording.PLAIN
}

/**
 * A button the leave dialog offers, in the order they are laid out. A list rather than a shape in the
 * composable, because both things it decides are guarantees somebody has gone looking for: [CANCEL] is
 * #35/#127 (the reporter's screenshot showed no way back to the message), and [SAVE_DRAFT] is 1.4.3's
 * plaintext guarantee, which used to be an `if` around a branch nothing could run.
 */
internal enum class DiscardChoice { CANCEL, DISCARD, SAVE_DRAFT }

/** One answer of the leave dialog: the button, and whether it may be tapped at all. */
internal data class DiscardAnswer(val choice: DiscardChoice, val enabled: Boolean)

/**
 * Cancel first, the confirming answer last — the order `SaveChangesDialog` already uses.
 *
 * The two conditions are the toolbar's two, and not the same question (#35): [mayKeepDraft] is what the
 * toolbar HIDES its Save icon on, so the button is absent and the body owes the reason; [hasContent] is
 * what it GREYS it on, and empty the save is not a save — `saveDraft` DELETES the draft the composer
 * was opened on. Greyed rather than dropped, or the OUTBOX body is false and the toolbar disagrees.
 */
internal fun discardChoices(mayKeepDraft: Boolean, hasContent: Boolean): List<DiscardAnswer> =
    listOfNotNull(
        DiscardAnswer(DiscardChoice.CANCEL, enabled = true),
        DiscardAnswer(DiscardChoice.DISCARD, enabled = true),
        DiscardAnswer(DiscardChoice.SAVE_DRAFT, enabled = hasContent).takeIf { mayKeepDraft },
    )

/**
 * Whether the composer offers to delete the draft it is editing (#127, #95). Four terms, each a way of
 * not lying about what the button would do: [draftId], so a new mail has nothing to delete; not
 * [restore], a message out of the send queue expecting the outbox's own gesture; [draftInHand], so an
 * offline reopen with nothing behind it offers no button that would silently do nothing; and
 * [localDraftHeld], the lease on a draft no server has seen (#95).
 *
 * Not `draftOpenRoute(draftId) == LOCAL`: on a row the upload worker consumed, the route still
 * answers LOCAL while the ViewModel holds nothing. The LEASE is the truth.
 */
internal fun draftDeleteOffered(
    restore: Boolean,
    draftId: String?,
    draftInHand: Boolean,
    localDraftHeld: Boolean,
): Boolean = !restore && draftId != null && (draftInHand || localDraftHeld)

/**
 * Where the caret starts in a prefilled body, or null when [focus] is not the body. [bodyLength] is
 * the whole prefilled body, signature included; [linkBodyLength] how much a `mailto:` link supplied.
 *
 * The caret goes where the writing continues: after a reopened draft's last character, after the text
 * a link supplied (#83, which is also just above the signature), and at the top for everything else —
 * a reply sits above the quote, and a link with no body opens on the signature block alone.
 */
internal fun initialBodyCaret(
    bodyLength: Int,
    focus: ComposeFocus,
    isDraft: Boolean,
    linkBodyLength: Int = 0,
): Int? = when {
    focus != ComposeFocus.BODY -> null
    isDraft -> bodyLength
    linkBodyLength > 0 -> linkBodyLength.coerceAtMost(bodyLength)
    else -> 0
}

/**
 * Where a tap on a header row puts the caret, or null to leave it where it is. [tapX] and [textStartX]
 * are in the row's own coordinates.
 *
 * A tap BEFORE the text means "put the cursor at the very beginning", which otherwise takes
 * pixel-perfect aim just left of the first glyph (#26). A tap on the text never reaches this decision;
 * a tap past the end, an empty field and a field not laid out force nothing.
 */
internal fun headerTapCaret(tapX: Float, textStartX: Float, textLength: Int): Int? = when {
    textLength <= 0 -> null
    !tapX.isFinite() || !textStartX.isFinite() -> null
    tapX < textStartX -> 0
    else -> null
}

/** Split a recipient string into trimmed, non-empty address tokens. */
internal fun recipientTokens(value: String): List<String> =
    value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * A recipient field is a single comma-joined string, and that string is the only state there is:
 * everything before the last separator is a committed address (a chip), the trailing token is what is
 * still being typed. [splitRecipients] reads a field that way, [joinRecipients] writes it back.
 */
internal fun splitRecipients(value: String): Pair<List<String>, String> {
    val cut = value.lastIndexOfAny(charArrayOf(',', ';'))
    val chips = recipientTokens(if (cut >= 0) value.substring(0, cut) else "")
    val input = (if (cut >= 0) value.substring(cut + 1) else value).trimStart()
    return chips to input
}

/** Put a recipient field back together from its committed addresses and its still-typed token. */
internal fun joinRecipients(chips: List<String>, input: String): String =
    chips.joinToString("") { "$it, " } + input

/**
 * The recipient field after a tap on the chip at [index] (#94): that address becomes the field's
 * trailing token again, so a typo is fixed by tapping the address rather than retyping it. What was
 * already being typed is committed as a chip of its own, at the end, which is where it already sat in
 * the string; an [index] that names no chip leaves the field untouched.
 *
 * The model's own limit: a display name holding a comma is already two tokens before any tap.
 */
internal fun recipientsWithChipEdited(value: String, index: Int): String {
    val (chips, input) = splitRecipients(value)
    if (index !in chips.indices) return value
    val kept = chips.filterIndexed { i, _ -> i != index }
    val pending = input.trim()
    return joinRecipients(if (pending.isEmpty()) kept else kept + pending, chips[index])
}

/**
 * Compose's initial fields when reopening a saved draft (#63): every addressing field as typed-out
 * addresses, the subject verbatim, and the body flattened for the editor. [cached] is the row this app
 * kept for the same draft — see [draftRecipientField] for what it is allowed to do, which is very
 * little.
 */
internal fun draftFieldsOf(o: Email, cached: Email?): DraftFields {
    val to = draftRecipientField(o.to, cached?.to)
    val cc = draftRecipientField(o.cc, cached?.cc)
    val bcc = draftRecipientField(o.bcc, cached?.bcc)
    // The genuine `text/html` part FIRST, and NOT through [bodySource] (#131): that one takes
    // `textBody[0]` whenever it is non-blank, and a draft this app saved has BOTH parts, the text one
    // holding the same letters with the styling stripped — so a styled draft would come back plain,
    // every time, in silence.
    val rich = richBodyFrom(o.htmlContent()) { originalPlainText(o) }
    return DraftFields(
        to = to,
        cc = cc,
        bcc = bcc,
        subject = o.subject.orEmpty(),
        body = rich.text,
        bodyRanges = rich.ranges,
        bodyBlocks = rich.blocks,
        bodyLinks = rich.links,
        expand = cc.isNotBlank() || bcc.isNotBlank(),
    )
}

/**
 * One addressing field of a reopened draft, comma-joined: for each stored recipient, the address
 * whenever there is one, and only otherwise the display name (#96).
 * The address has to win because this string is what the send path parses and mails, so reopening
 * `Bob <bob@example.com>` as "Bob" would address the message to `Bob`; the name fallback is for the
 * recipient a server hands back with an empty address.
 *
 * [cachedAddresses] is used in ONE case, the server having given nothing. Deliberately not "take the
 * cache whenever it holds more" — another client may have deliberately REMOVED a recipient.
 */
private fun draftRecipientField(
    addresses: List<EmailAddress>,
    cachedAddresses: List<EmailAddress>?,
): String {
    val fromServer = recipientFieldOf(addresses)
    return if (fromServer.isNotBlank()) fromServer else recipientFieldOf(cachedAddresses.orEmpty())
}

/** [addresses] as the composer's comma-joined field: the address if there is one, else the name. */
private fun recipientFieldOf(addresses: List<EmailAddress>): String =
    addresses
        .map { it.email.ifBlank { it.name.orEmpty() } }
        .filter { it.isNotBlank() }
        .joinToString(", ")

/**
 * Split an addressing field into its trimmed, non-empty tokens. A field of blanks or stray separators
 * yields nothing — the one place the app decides what counts as "an address was typed here", so the
 * send path, the draft save and the #69 content rule cannot disagree.
 */
internal fun parseAddrs(s: String): List<String> =
    s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The recipients the encryption state is derived from (#35): the addressing tokens that already ARE
 * addresses, deduplicated. A token still being typed has no key by construction, so counting it would
 * have the lock announce "not encrypted" about an unfinished address. Judged by [isValidEmail], the
 * rule the send gate and the per-chip warning also use.
 */
internal fun encryptionRecipients(to: String, cc: String, bcc: String): List<String> =
    (parseAddrs(to) + parseAddrs(cc) + parseAddrs(bcc)).filter(::isValidEmail).distinct()

/**
 * Whether the encryption state has to be recomputed: [previous] is the set it was last computed for.
 *
 * The whole anti-flicker rule (#35), and deliberately NOT a timer, which would tie a security
 * indicator to typing speed: the condition is that the set of real addresses changed. A completed
 * address with no key still flips the state at once. Order is irrelevant.
 */
internal fun recipientKeysStale(previous: List<String>?, current: List<String>): Boolean =
    previous == null || previous.toSet() != current.toSet()

/**
 * The single #69 rule for "this draft is worth saving": a real recipient, a non-blank subject, a
 * non-blank body, or an attachment.
 *
 * A typed address IS content — that is the #69 bug: a compose holding only a recipient was judged
 * empty, so "Save draft" closed the screen and persisted nothing. Shared by the save gate and the
 * toolbar, so the greyed-out icon and the actual decision cannot drift apart.
 */
internal fun draftHasContent(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    hasAttachment: Boolean,
): Boolean =
    parseAddrs(to).isNotEmpty() || parseAddrs(cc).isNotEmpty() || parseAddrs(bcc).isNotEmpty() ||
        subject.isNotBlank() || body.isNotBlank() || hasAttachment

/**
 * The single rule for "may this message be kept as a draft at all?" (#35): everything but an encrypted
 * one, since a draft is stored on the server as it stands in the composer.
 *
 * Asked through [PgpMode.encrypts], never `== PgpMode.ENCRYPT`: both encrypting modes leak the same
 * plaintext. [PgpMode.SIGN] is NOT caught — a signed message is stored in the clear by design. It
 * depends on the current mode and nothing else, so a hand-closed lock and the account default answer
 * identically.
 */
internal fun draftSaveAllowed(pgpMode: PgpMode): Boolean = !pgpMode.encrypts

/**
 * The single rule for "may this message be SCHEDULED?" — stricter than [draftSaveAllowed] on two
 * counts, each of which would send silently short of what the composer showed (A2/A3): a headless
 * worker can neither sign nor encrypt, so ANY [PgpMode] but OFF is refused, and the scheduled table
 * carries no attachments, so a staged one refuses scheduling rather than firing the message stripped.
 */
internal fun scheduleSendAllowed(pgpMode: PgpMode, hasAttachment: Boolean): Boolean =
    pgpMode == PgpMode.OFF && !hasAttachment

/** The quick "send later" choices, in the order the composer's menu draws them. */
internal enum class SchedulePreset { IN_1_HOUR, THIS_EVENING, TOMORROW_MORNING, TOMORROW_EVENING }

/**
 * The presets still offerable at [nowMillis], read in [zone], in menu order — those STRICTLY in the
 * future. Pure, so the 6 PM boundary can be played without moving the bench's clock.
 *
 * A lapsed candidate is DROPPED, never folded to the next day: folding corrected the instant and
 * left the label, so past 6 PM "This evening, 6 PM" scheduled TOMORROW and duplicated the entry below.
 */
internal fun schedulePresetsAt(nowMillis: Long, zone: ZoneId): List<Pair<SchedulePreset, Long>> {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    fun at(day: ZonedDateTime, hour: Int) = day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    return listOf(
        SchedulePreset.IN_1_HOUR to now.plusHours(1),
        SchedulePreset.THIS_EVENING to at(now, 18),
        SchedulePreset.TOMORROW_MORNING to at(now.plusDays(1), 8),
        SchedulePreset.TOMORROW_EVENING to at(now.plusDays(1), 18),
    ).filter { (_, time) -> time.isAfter(now) }
        .map { (preset, time) -> preset to time.toInstant().toEpochMilli() }
}

/**
 * What a tap on [preset] at [nowMillis] is worth, given [drawnMillis] — the instant that entry was
 * DRAWN for — or null when the tap must be REFUSED.
 * The same danger as [pickedScheduleMillis], one tap earlier: the menu can stand open for minutes,
 * and a lapsed instant becomes a delay coerced to zero, the message going out AT ONCE.
 *
 * ONE preset is asked again and THREE are not: [SchedulePreset.IN_1_HOUR] is RELATIVE, while the
 * three others are ABSOLUTE of the day they were DRAWN — re-reading them moves them a whole day at
 * midnight, and their refusal is read on [drawnMillis].
 */
internal fun presetMillisAtTap(
    preset: SchedulePreset,
    drawnMillis: Long,
    nowMillis: Long,
    zone: ZoneId,
): Long? = when (preset) {
    SchedulePreset.IN_1_HOUR ->
        schedulePresetsAt(nowMillis, zone).firstOrNull { it.first == preset }?.second
    // Named one by one rather than an `else`: a fifth preset must not be swallowed into "carry what
    // was drawn" in silence — the compiler has to ask which of the two kinds it is.
    SchedulePreset.THIS_EVENING, SchedulePreset.TOMORROW_MORNING, SchedulePreset.TOMORROW_EVENING ->
        drawnMillis.takeIf { it > nowMillis }
}

/**
 * The instant a hand-picked day + time is worth in [zone], or null when it is not STRICTLY after
 * [nowMillis] (#161).
 * A lapsed instant must never reach the schedule: `enqueue` coerces a negative delay to zero, and
 * nothing else looks at whether the instant is ahead. The screen calls this TWICE, the minute being
 * able to turn between `enabled` and the tap.
 *
 * [dayUtcMidnightMillis] is UTC midnight, never local, so the CIVIL day is read off it in UTC and
 * rebuilt in [zone]; comparing that millis to a local instant is wrong by a day on either side.
 */
internal fun pickedScheduleMillis(
    dayUtcMidnightMillis: Long,
    hour: Int,
    minute: Int,
    zone: ZoneId,
    nowMillis: Long,
): Long? {
    val day = Instant.ofEpochMilli(dayUtcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val picked = ZonedDateTime.of(day, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    return picked.takeIf { it > nowMillis }
}

/**
 * The wall time the "pick date and time" clock opens on, once a day has been chosen (#161): an hour
 * ahead, or the last minute the chosen day still has.
 * Material's default of midnight is what this avoids, and the naive fix reopened the wound at the
 * other end of the day: pasting the hour and minute of `now + 1 h` onto the chosen day throws the roll
 * over to tomorrow away, so at 23:20 the clock opened on 00:20 TODAY and OK was grey from the first
 * frame — with no wording anywhere, the greyed button IS the message.
 * Hence the one invariant, asked of [pickedScheduleMillis] ITSELF, a rule of our own having looked
 * equivalent and not been. 23:59, not "now + 1 minute", for the fallback.
 */
internal fun scheduleClockOpensAt(dayUtcMidnightMillis: Long, nowMillis: Long, zone: ZoneId): LocalTime {
    val ahead = Instant.ofEpochMilli(nowMillis).atZone(zone).plusHours(1)
    val anHourAhead = LocalTime.of(ahead.hour, ahead.minute)
    val offerable = pickedScheduleMillis(dayUtcMidnightMillis, anHourAhead.hour, anHourAhead.minute, zone, nowMillis)
    return if (offerable != null) anHourAhead else LocalTime.of(23, 59)
}

/**
 * Whether BOTH "pick date and time" pickers must show Material's KEYBOARD entry instead of their dial
 * and their grid (#161). One rule for the two dialogs, because one shape of window breaks both.
 * The comparison is not ours and must not be replaced by a dp constant: it is character for
 * character `getDefaultTimePickerLayoutType`'s. A scroll does not save it — the clock's is VERTICAL
 * while the cut is sideways.
 * Measured on `emu` at 914×411 dp: the dial's right half off screen, and the month grid CLIPPED at
 * August the 29th. A `verticalScroll` around the grid would measure a `LazyVerticalGrid` in infinite
 * height, which THROWS, hence the shell's own guard.
 */
internal fun schedulePickerAsInput(screenHeightDp: Int, screenWidthDp: Int): Boolean =
    screenHeightDp < screenWidthDp

/**
 * Whether a day the calendar offers may be chosen at all: today and after, read in [zone] (#161). The
 * FIRST of the two locks in front of a send into the past; [pickedScheduleMillis] alone covers "today,
 * at an hour already gone".
 *
 * Lower bound only: no horizon exists anywhere in the scheduled-send code, and inventing one here
 * would be a limit to carry forever. Same UTC-midnight trap, and it bites hardest here — both sides
 * are reduced to a CIVIL day, or the rule would refuse TODAY in Los Angeles after 5 PM.
 */
internal fun scheduleDaySelectable(dayUtcMidnightMillis: Long, nowMillis: Long, zone: ZoneId): Boolean {
    val day = Instant.ofEpochMilli(dayUtcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return !day.isBefore(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate())
}

/**
 * The FIRST year the "pick date and time" calendar offers in its year list, read in [zone] (#161).
 *
 * A DISPLAY bound, not a lock — that stays [scheduleDaySelectable]'s job. What it closes is the year
 * list opening on 1900, since `DatePickerDefaults.YearRange` decides which years are DRAWN.
 * Lower bound only. Read in [zone] and nowhere else: on 31 December at 23:00 west of UTC the UTC
 * year has already turned, and the list would start on a year that is over.
 */
internal fun scheduleFirstYear(nowMillis: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(nowMillis).atZone(zone).year

/**
 * The lock toggle's cycle: off → sign → encrypt → off. Pure, so the hand-set path can be tested
 * against [draftSaveAllowed] (#35). THREE STOPS, and [PgpMode.ENCRYPT_UNSIGNED] is not one — a short
 * tap must not walk anyone into sending unsigned mail; that mode is reached only from the long-press
 * menu, and has a branch here only so a tap can get OUT of it.
 */
internal fun nextPgpMode(current: PgpMode): PgpMode = when (current) {
    PgpMode.OFF -> PgpMode.SIGN
    PgpMode.SIGN -> PgpMode.ENCRYPT
    PgpMode.ENCRYPT -> PgpMode.OFF
    PgpMode.ENCRYPT_UNSIGNED -> PgpMode.OFF
}

/**
 * The face each mode wears, in the top bar AND in the long-press menu — one function, so the icon the
 * menu offers is the icon the bar will show.
 *
 * [PgpMode.ENCRYPT_UNSIGNED] does NOT wear `Icons.Outlined.Lock`: measured, the outlined padlock
 * differed from `Icons.Filled.LockOpen` by 82 rasterised pixels out of 9216, so it reads as "off".
 * It wears a KEY instead — the message is just as encrypted, and the missing signature is told by
 * the absence of the padlock's pen. [PgpModeIconFacesTest] keeps the four faces pairwise distinct.
 */
internal fun pgpModeIcon(mode: PgpMode): ImageVector = when (mode) {
    PgpMode.OFF -> Icons.Filled.LockOpen
    PgpMode.SIGN -> Icons.Filled.Draw
    PgpMode.ENCRYPT -> Icons.Filled.Lock
    PgpMode.ENCRYPT_UNSIGNED -> Icons.Filled.VpnKey
}

/**
 * The two arguments of `PgpEngine.signAndEncrypt`, which are NOT the same thing and must never be
 * derived from one another: [signKeyId] is who signs (null = do not sign), [recipientKeyIds] who can
 * decrypt.
 *
 * The account's own key plays both roles — it signs, and it is a recipient, the encrypt-to-self that
 * keeps the Sent copy readable. Filtering "the sign key" out of the recipients would make every
 * unsigned encrypted message in Sent permanently unreadable, with no error anywhere.
 */
internal data class PgpEncryptArgs(val signKeyId: Long?, val recipientKeyIds: LongArray) {
    // Hand-written because a data class holding an array compares it by reference, which would make
    // every test that compares two PgpEncryptArgs pass without looking at a key id.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PgpEncryptArgs && signKeyId == other.signKeyId &&
                    recipientKeyIds.contentEquals(other.recipientKeyIds)
                )

    override fun hashCode(): Int = 31 * (signKeyId?.hashCode() ?: 0) + recipientKeyIds.contentHashCode()

    override fun toString(): String =
        "PgpEncryptArgs(signKeyId=$signKeyId, recipientKeyIds=${recipientKeyIds.toList()})"
}

/**
 * Who signs and who can read, for an encrypting [mode]. The only thing the unsigned mode changes in
 * the send path is the signer: [selfKeyId] is in the recipients in BOTH modes (the encrypt-to-self)
 * and is the signer only in [PgpMode.ENCRYPT].
 *
 * A non-encrypting [mode] is a programming error and says so: the send site reaches this only from
 * its encrypting branch, so a mode arriving here means the mode moved under the send, and the honest
 * answer is a crash, not an unsigned message.
 */
internal fun pgpEncryptArgs(mode: PgpMode, selfKeyId: Long, recipientKeys: List<Long>): PgpEncryptArgs {
    require(mode.encrypts) { "pgpEncryptArgs called with $mode, which does not encrypt" }
    return PgpEncryptArgs(
        signKeyId = selfKeyId.takeIf { mode == PgpMode.ENCRYPT },
        recipientKeyIds = (recipientKeys + selfKeyId).distinct().toLongArray(),
    )
}

/**
 * The subject to repeat INSIDE the entity about to be encrypted (RFC 9788 protected headers), or null.
 *
 * This is INTEGRITY, not confidentiality: the subject keeps travelling in the clear on the envelope,
 * and the sealed copy only lets a relay's rewrite be caught. Do not turn it into subject masking
 * (§ 5.2.2): the composer's note tells the user the subject is not encrypted.
 *
 * [mode] is asked through [encrypts], never `== PgpMode.ENCRYPT`, the unsigned mode needing the same
 * protection while a SIGN-only message must get NOTHING. A blank subject is skipped.
 */
internal fun protectedSubject(mode: PgpMode, subject: String): String? =
    subject.takeIf { mode.encrypts && it.isNotBlank() }

/** Why a message stopped being signed/encrypted, when the composer had to give the mode up. */
internal enum class PgpDropReason { NONE, NOT_CONFIGURED, NO_PROVIDER }

/** The mode the composer settles on after re-checking OpenPGP, and what it owes the user. */
internal data class PgpRefreshOutcome(val mode: PgpMode, val dropped: PgpDropReason)

/**
 * Re-arbitrate the lock after anything that can change what OpenPGP can do here (#35). [available] is
 * "this account has OpenPGP set up AND the provider answers"; [accountConfigured] only picks WHICH
 * explanation is owed. Three outcomes:
 *  - nothing can encrypt or sign → OFF, and [dropped] with a reason when something visible went away.
 *    This is the case that was silent: a message queued as ENCRYPT, reopened after OpenKeychain was
 *    uninstalled, came back with no padlock and no word;
 *  - encrypt-by-default with the lock untouched → ENCRYPT, from which [deriveAutoMode] backs off;
 *  - otherwise the mode stands: a hand-set lock is never quietly rewritten.
 */
internal fun pgpRefresh(
    current: PgpMode,
    available: Boolean,
    accountConfigured: Boolean,
    encryptByDefault: Boolean,
    userSet: Boolean,
): PgpRefreshOutcome = when {
    !available -> PgpRefreshOutcome(
        PgpMode.OFF,
        when {
            current == PgpMode.OFF -> PgpDropReason.NONE
            !accountConfigured -> PgpDropReason.NOT_CONFIGURED
            else -> PgpDropReason.NO_PROVIDER
        },
    )
    encryptByDefault && !userSet -> PgpRefreshOutcome(PgpMode.ENCRYPT, PgpDropReason.NONE)
    else -> PgpRefreshOutcome(current, PgpDropReason.NONE)
}

// --- Signature (pure text, living in the body — WYSIWYG) -----------------------------------------
// The signature is ordinary text in the editable body, inserted when compose opens, like K-9 and
// Thunderbird. It is NOT appended at send time: what the composer shows is what leaves.

/** The standard signature delimiter line (RFC 3676 §4.3): two hyphens and a space. */
internal const val SIGNATURE_DELIMITER = "-- "

/**
 * The block a [signature] occupies in a body: a blank line, the delimiter line, then the signature.
 * Empty for a blank signature, so every caller can concatenate unconditionally.
 *
 * [delimiter] is the "Separator line above the signature" setting (#90). It governs what is WRITTEN;
 * both shapes are still recognised when reading a body back (see [signatureBlockAt]).
 */
internal fun signatureBlock(signature: String, delimiter: Boolean): String = when {
    signature.isBlank() -> ""
    delimiter -> "\n\n$SIGNATURE_DELIMITER\n${signature.trim()}"
    else -> "\n\n${signature.trim()}"
}

/** The composer's initial body: the [quoted] original with the signature block above it, or below when
 *  [signatureBelowQuote] is set. A reply's quote starts with its own blank lines, so the caret sits at
 *  the top of an empty first line either way. */
internal fun bodyWithSignature(
    quoted: String,
    signature: String,
    signatureBelowQuote: Boolean = false,
    delimiter: Boolean,
): String {
    val block = signatureBlock(signature, delimiter)
    if (block.isEmpty()) return quoted
    return if (signatureBelowQuote) quoted + block else block + quoted
}

/**
 * [body] with [signature]'s block added where the prefill would have put it — used when the "From"
 * identity changes and the identity being left had NO signature (D5).
 *
 * With the signature above [quoted] (the default) the block goes immediately before it; below the
 * quote, or with no quote, at the very end. A quote the user has edited away is no longer found as the
 * tail, and the block then lands at the end rather than in an arbitrary spot.
 */
internal fun insertSignatureBlock(
    body: String,
    signature: String,
    quoted: String = "",
    signatureBelowQuote: Boolean = false,
    delimiter: Boolean,
): String {
    val block = signatureBlock(signature, delimiter)
    if (block.isEmpty()) return body
    if (signatureBelowQuote || quoted.isEmpty() || !body.endsWith(quoted)) return body + block
    return body.dropLast(quoted.length) + block + quoted
}

/**
 * [body] with the block of [oldSignature] swapped for [newSignature]'s — or null when the block is not
 * there verbatim, which means the user edited it and their text must be left alone (D5). A blank
 * [oldSignature] is [insertSignatureBlock]'s case. The old block is looked up in BOTH shapes; the new
 * one is written in the shape [delimiter] asks for.
 */
internal fun replaceSignatureBlock(
    body: String,
    oldSignature: String,
    newSignature: String,
    delimiter: Boolean,
): String? {
    val found = signatureBlockAt(body, oldSignature, delimiter) ?: return null
    return body.substring(0, found.start) +
        signatureBlock(newSignature, delimiter) +
        body.substring(found.end)
}

/** Where a signature block was found in a body, and in which shape it was written. */
private data class SignatureBlockMatch(val start: Int, val end: Int, val withDelimiter: Boolean)

/**
 * Where [signature]'s block sits in [body] verbatim, or null. The LAST occurrence wins, so a reply
 * quoting an older message with the same signature swaps the live block at the bottom, and the block
 * must END on a line boundary — text appended to its last line means the user edited it.
 *
 * BOTH shapes are recognised whatever the setting says (#90), or a draft written under the other one
 * would stop being found and both features built on this lookup would go quiet. The shape named by
 * [delimiter] is tried FIRST.
 */
private fun signatureBlockAt(body: String, signature: String, delimiter: Boolean): SignatureBlockMatch? {
    for (withDelimiter in listOf(delimiter, !delimiter)) {
        val block = signatureBlock(signature, withDelimiter)
        if (block.isEmpty()) return null
        val at = lastBlockIndex(body, block)
        if (at >= 0) return SignatureBlockMatch(at, at + block.length, withDelimiter)
    }
    return null
}

/** The last occurrence of [block] in [body] that ends on a line boundary, or -1. */
private fun lastBlockIndex(body: String, block: String): Int {
    var at = body.lastIndexOf(block)
    while (at >= 0) {
        val end = at + block.length
        if (end == body.length || body[end] == '\n') return at
        if (at == 0) return -1
        at = body.lastIndexOf(block, at - 1)
    }
    return -1
}

/**
 * The outgoing text/html alternative for [body]: its text escaped, its styling (#131) as tags, and —
 * when the identity has an imported HTML signature AND the plain block is still untouched — that block
 * replaced by the HTML version.
 *
 * Once the user edits the block, their text wins in both alternatives (WYSIWYG beats fidelity), and a
 * style range laid over even one character of it IS such an edit. The delimiter line is emitted only
 * when the block found in the BODY carries one, not when the setting says so: the two alternatives of
 * one message must say the same thing (#90).
 */
internal fun htmlBodyWithSignature(
    body: RichBody,
    signature: String,
    signatureHtml: String,
    delimiter: Boolean,
): String {
    if (signatureHtml.isBlank()) return toHtml(body)
    val found = signatureBlockAt(body.text, signature, delimiter) ?: return toHtml(body)
    if (!signatureBlockIsIntact(body, found.start, found.end)) return toHtml(body)
    val head = if (found.withDelimiter) "<br><br>$SIGNATURE_DELIMITER<br>" else "<br><br>"
    return toHtml(body, verbatim = Span(found.start, found.end) to head + signatureHtml.trim())
}

/**
 * Whether nothing the user laid over the body — no style range, and no LINK (#131) — reaches into
 * `[start, end)` of [body]; half-open on both sides.
 *
 * The links belong here for the ranges' reason, and their absence would be worse: the substitution
 * REPLACES those characters with the stored HTML, so a link on a word of the signature block would
 * simply not be in the message. The list BLOCKS are not counted, which is the lists' question.
 */
internal fun signatureBlockIsIntact(body: RichBody, start: Int, end: Int): Boolean =
    body.ranges.values.none { spans -> spans.any { it.start < end && it.end > start } } &&
        body.links.none { it.span.start < end && it.span.end > start }

// --- Reply / reply-all / forward header derivation (pure, so it works from a cached list row) ---
// These need only the original's headers, never its body, so a reply can be addressed correctly
// offline from the cached row of a mail that was never opened.

/**
 * Who an answer to [o] is addressed to, as a LIST: the original's `Reply-To` when it sets one — ALL of
 * its addresses, in order — and only otherwise its sender. Answering the From instead writes to the
 * address the sender deliberately set aside. Never both: the From is precisely what a `Reply-To`
 * displaces (K-9 and Thunderbird do the same), and one holding only blanks falls back to it.
 *
 * A list, not a joined string, because [replyAllRecipients] deduplicates entry by entry: a single
 * "a@x, b@x" entry would count as one address and slip past the dedup.
 */
internal fun replyRecipients(o: Email): List<String> {
    val named = if (o.replyTo.any { it.email.isNotBlank() }) {
        o.replyTo.map { it.email }
    } else {
        listOfNotNull(o.from.firstOrNull()?.email)
    }
    return named.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
}

/** The recipient field of a plain reply: [replyRecipients], joined for the To field. */
internal fun replyRecipient(o: Email): String =
    replyRecipients(o).joinToString(", ")

/**
 * The recipients of a reply-all: [replyRecipients] plus everyone on the original's To and Cc, minus
 * your OWN addresses and blanks/duplicates. [selves] is every address the account can send as, not
 * just the login: an account with aliases used to reply to its own other alias (B5).
 */
internal fun replyAllRecipients(o: Email, selves: Collection<String>): String {
    val mine = selves.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val all = (replyRecipients(o) + o.to.map { it.email } + o.cc.map { it.email })
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in mine }
        .distinctBy { it.lowercase() }
    return all.joinToString(", ")
}

/**
 * Which of your own addresses [o] was delivered to: the first address of its To, then its Cc, that
 * belongs to you. Returned as the original spells it, matched case-insensitively.
 *
 * Null when none of your addresses is named — a mailing list, or a Bcc delivery. Callers must treat
 * that as "unknown"; it is never a reason to guess.
 */
internal fun receivingAddress(o: Email, mine: Collection<String>): String? {
    val ours = mine.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (ours.isEmpty()) return null
    return (o.to + o.cc)
        .map { it.email.trim() }
        .firstOrNull { it.isNotEmpty() && it.lowercase() in ours }
}

/**
 * The "From" identity a reply/forward of [o] should open with: the one identity of [accountId] the
 * original was delivered to (#81). Without it, an account with several aliases always replied under
 * its default identity. Only that account's own sending options are considered, and null means
 * "nothing to preselect".
 */
internal fun receivingFromOption(
    options: List<FromOption>,
    accountId: String?,
    o: Email,
): FromOption? {
    val mine = options.filter { it.accountId == accountId }
    val received = receivingAddress(o, mine.map { it.identity.email })?.lowercase() ?: return null
    return mine.firstOrNull { it.identity.email.trim().lowercase() == received }
}

/** [subject] with [prefix] ("Re:"/"Fwd:") prepended, unless it already carries it (any case). */
internal fun withPrefix(subject: String?, prefix: String): String {
    val s = subject.orEmpty()
    return if (s.startsWith(prefix, ignoreCase = true)) s else "$prefix $s"
}

/** What a compose `mode` navigation argument opens. Parsed ONCE, here, so the three places in
 *  [ComposeViewModel.prepare] that branch on it cannot disagree: a mode known to one and not the
 *  others opened, in silence, a quoted reply carrying In-Reply-To. */
internal enum class ComposeOpening(
    /** Sets In-Reply-To / References from the original. */
    val threads: Boolean,
    /** "Re:" / "Fwd:" */
    val subjectPrefix: String,
    /** The body starts with the quoted original. */
    val quotes: Boolean,
    /** `buildForwarded`: the original travels inline, under the note, at send time. */
    val carriesOriginal: Boolean,
    /** The original's raw source is staged as a `message/rfc822` part (`.eml`). */
    val attachesSource: Boolean,
) {
    REPLY(threads = true, subjectPrefix = "Re:", quotes = true, carriesOriginal = false, attachesSource = false),
    REPLY_ALL(threads = true, subjectPrefix = "Re:", quotes = true, carriesOriginal = false, attachesSource = false),
    FORWARD(threads = false, subjectPrefix = "Fwd:", quotes = false, carriesOriginal = true, attachesSource = false),
    FORWARD_ATTACHMENT(threads = false, subjectPrefix = "Fwd:", quotes = false, carriesOriginal = false, attachesSource = true),
}

/** The one reader of the route's `mode`. Null and any unknown string open a reply, as the
 *  `else` of the old `when` did. */
internal fun composeOpening(mode: String?): ComposeOpening = when (mode) {
    "forward" -> ComposeOpening.FORWARD
    "forwardAttachment" -> ComposeOpening.FORWARD_ATTACHMENT
    "replyAll" -> ComposeOpening.REPLY_ALL
    else -> ComposeOpening.REPLY
}

/**
 * Which sentence a draft that could not be read shows. [offline] is the app's one offline verdict,
 * decided by the caller because only it holds both the throwable and the link state.
 *
 * Two sentences, because they ask for different things. Neither names a CAUSE — no server is even
 * contacted on the "no credentials" path, and a captive portal, a refused certificate and a 401 land
 * here identically. And neither promises a recovery that does not exist: nothing re-runs the fetch,
 * so both ask for the one thing that works. Neither says a word about saving.
 */
internal fun draftLoadNoticeFor(offline: Boolean): Int =
    if (offline) R.string.compose_draft_offline else R.string.compose_draft_load_failed

/** What a composer opened on a saved draft is allowed to put on screen. Three states and not a
 *  boolean, because the third one carries something: the sentence to show. */
internal sealed interface DraftReopenView {
    /** The draft is in hand, or this composer is not reopening one: the editor, exactly as always. */
    data object Editor : DraftReopenView

    /**
     * The fetch is still out. Nothing is editable and no action is offered: the fields would be
     * blank, and a save from them would add a second draft beside the one still being read.
     */
    data object Waiting : DraftReopenView

    /**
     * The draft could not be read at all. [notice] is the string id of what to say, and it is the
     * only thing this screen has left to offer besides the way out.
     */
    data class DeadEnd(val notice: Int) : DraftReopenView
}

/**
 * Which of the three a reopened draft's composer draws, from what the ViewModel publishes about the
 * fetch and from the one thing the SCREEN knows: [prefillApplied].
 * [prefillApplied] WINS OVER BOTH. It is the fact "this screen has already shown the draft", and what
 * a spinner or a dead end over it would hide is the reader's own typing: `body` and this flag survive
 * process death while the ViewModel does not, so after a kill the replayed fetch dead-ends over words
 * still in memory, and the X would offer a save that puts a SECOND draft on the server.
 * `carryDraftAttachments` runs after `_prefill` and before [loading] is lowered; what keeps that
 * window safe is a missing LICENCE, not a missing id. [loading] wins over [failureNotice].
 */
internal fun draftReopenView(
    loading: Boolean,
    failureNotice: Int?,
    prefillApplied: Boolean,
): DraftReopenView = when {
    prefillApplied -> DraftReopenView.Editor
    loading -> DraftReopenView.Waiting
    failureNotice != null -> DraftReopenView.DeadEnd(failureNotice)
    else -> DraftReopenView.Editor
}

/** Whether the reply's quoted original may be dropped into the body: only once the header prefill has
 *  been [applied], and only while the body still equals its [initialBody] baseline, so a late quote
 *  never clobbers text already typed. */
internal fun canApplyReplyQuote(applied: Boolean, bodyText: String, initialBody: String): Boolean =
    applied && bodyText == initialBody

/** The time zone the composer renders an OUTGOING date in — a reply's attribution line and a forward's
 *  Date header (#120). Nothing else changes zone through here: the reader and the list rows keep the
 *  device's clock, and the SMTP `Date:` header was already UTC. */
internal fun outgoingDateZone(
    quotedDatesUtc: Boolean,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ZoneId = if (quotedDatesUtc) ZoneOffset.UTC else deviceZone

/** [outgoingDateZone], resolved by SUSPENDING on the stored setting: the first emission is awaited,
 *  never substituted with a default. DataStore answers a beat late on a cold start, and "not answered
 *  yet" is not "off" — a reply prefilled in that beat would write the local time with the switch on. */
internal suspend fun resolveOutgoingDateZone(
    quotedDatesUtc: Flow<Boolean>,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ZoneId = outgoingDateZone(quotedDatesUtc.first(), deviceZone)

/** What a sweep of the composer's attachment list found: [kept] stays on screen, [gone] leaves it. */
internal data class StagedAttachmentSweep(
    val kept: List<EmailBodyPart>,
    val gone: List<EmailBodyPart>,
)

/**
 * Which of the composer's [attachments] still have bytes behind them, and which are now a chip and
 * nothing else. A picked file lives in `cacheDir/outgoing`, which Clear cache empties and Android
 * evicts under pressure — neither of which closes the composer, so the chip stayed drawn over a file
 * that was gone and the save was what found out.
 *
 * ONLY PATHS UNDER [outgoingRoot] ARE ASKED ABOUT, and that guard is the whole difficulty: a
 * `partId` exists only for a part staged on this phone. Anything this function is not sure about is
 * KEPT, and an inline part carrying a `cid` is kept whatever the filesystem says.
 */
internal fun sweepStagedAttachments(
    attachments: List<EmailBodyPart>,
    outgoingRoot: File,
    exists: (File) -> Boolean,
): StagedAttachmentSweep {
    // A separator on the end, so a sibling directory whose name merely starts the same
    // ("outgoing-old") is not read as being inside the staging root.
    val root = outgoingRoot.path.trimEnd(File.separatorChar) + File.separatorChar
    val (kept, gone) = attachments.partition { part ->
        // The body points at this one; the same two-legged test the repository writes its own
        // inline parts with (MailRepository.imapDraftAttachments, OutboxEdit.take).
        if (part.disposition.equals("inline", ignoreCase = true) && !part.cid.isNullOrBlank()) {
            return@partition true
        }
        val staged = part.partId?.takeIf { it.startsWith(root) } ?: return@partition true
        exists(File(staged))
    }
    return StagedAttachmentSweep(kept = kept, gone = gone)
}
