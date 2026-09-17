package app.sterna.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.theme.LocalMailListPalette
import app.sterna.ui.theme.MailListDimens
import app.sterna.ui.theme.MailListPalette
import app.sterna.ui.theme.mailListTextColor
import app.sterna.ui.rememberMotionEnabled
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.jmap.model.Email
import app.sterna.util.MailDates
import kotlinx.coroutines.delay

/**
 * The background a message row is painted with — three states, in this order.
 *
 * Selection comes first and beats everything: the row's own colour is the ONLY sign that a row is
 * selected, and selection is what arms the destructive actions. Then [current], the row the reading
 * pane is showing (#103). Anything else — read or unread alike — is [card], the palette's CARD
 * colour (#472): black in the dark scheme, white in light. Read state is carried by the TEXT
 * ([mailListTextColor]), never by the row, so a filled background stays reserved for real
 * selection, which must remain visually distinct from a plain card.
 *
 * [flash] tints whichever base was retained rather than branching, so it cannot erase the selected
 * or current state while it plays.
 */
internal fun rowBackground(
    scheme: ColorScheme,
    selected: Boolean,
    current: Boolean,
    flash: Float,
    card: Color,
): Color {
    val base = when {
        selected -> scheme.secondaryContainer
        current -> scheme.primaryContainer
        else -> card
    }
    return if (flash > 0f) lerp(base, scheme.primary, 0.14f * flash) else base
}

/**
 * The fill behind the small rounded chips a row carries. Decided once per row, because the chips
 * are painted ON the row and have to know what they sit on.
 *
 * Chips step away from their row, not toward any one direction: [ColorScheme.surfaceVariant] reads
 * clearly against a plain [ColorScheme.surface] row in both themes, and against the selection or
 * current fill it is told apart by hue. There is no longer a third case to answer — an unread row
 * wears no background of its own (see [rowBackground]), so the chip answer no longer branches on
 * read state.
 */
internal fun chipFill(scheme: ColorScheme): Color = scheme.surfaceVariant

/**
 * One row in a message list: monogram, sender, subject, preview, time + state.
 *
 * [originLabel] is the one chip that says WHERE this row comes from: the owning ACCOUNT in the
 * unified inbox and in search, the row's own FOLDER in the unread view. One slot, and the two
 * never coexist. Null draws no chip at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmailListItem(
    email: Email,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    originLabel: String? = null,
    originColor: Color? = null,
    onToggleFavourite: (() -> Unit)? = null,
    selected: Boolean = false,
    // The message open in the reading pane beside the list, on a wide window (#103).
    current: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // In conversation view: the thread's unread state and how many of its messages are in the
    // viewed folder(s) — the pill shows this in-view count.
    unread: Boolean = !email.isSeen,
    threadCount: Int = 1,
    // Whether the row is a conversation at all (2+ cached messages account-wide). Keeps the pill
    // visible even when only one of the thread's messages sits in this view.
    threadExpandable: Boolean = threadCount > 1,
    // Conversation expand affordance: with a handler given, the count badge becomes a tappable
    // chevron pill. [expanded] drives its direction and the accessibility state.
    onToggleExpand: (() -> Unit)? = null,
    expanded: Boolean = false,
    // Brief emphasis flash when returning to the list from this message.
    highlighted: Boolean = false,
    onHighlightShown: () -> Unit = {},
    // In Sent/Drafts the sender is yourself, so the row shows who the mail went TO instead (#59).
    // Falls back to the sender while a cached row carries no recipients.
    showRecipients: Boolean = false,
    // Whether to mark the row "(Draft)". The `$draft` keyword is in memory only, so it is gone
    // after process death and never set at all on IMAP; the caller holding the folder-role map
    // answers with the message's own folder too. The default is the keyword alone.
    showDraftBadge: Boolean = email.isDraft,
    // Whether to mark the row as a draft this phone is still holding for the server (#95).
    showNotUploadedBadge: Boolean = false,
    // Tapping an attachment chip. NULL means draw no chips at all -- which is what every list that
    // cannot download (search results, whose rows come from the FTS table and carry no parts) passes,
    // rather than drawing chips that would do nothing when touched.
    onOpenAttachment: ((EmailBodyPart) -> Unit)? = null,
    // The attachment currently downloading anywhere in the list, as [attachmentKey] spells it. Only
    // the chip whose key matches shows the spinner: the thing the user touched is the thing that has
    // to answer. A KEY and not the part itself, because the part is re-decoded from the cache on
    // every paging refresh -- an identity comparison would stop matching mid-download, and equality
    // would spin the same-numbered part on a neighbouring row.
    openingAttachmentKey: String? = null,
) {
    val senderName = email.from.firstOrNull()?.display() ?: stringResource(R.string.message_unknown_sender)
    val recipient = if (showRecipients) email.to.firstOrNull() else null
    val nameLine = if (recipient != null) {
        stringResource(R.string.list_to_recipients, email.to.joinToString { it.display() })
    } else {
        senderName
    }
    val density = LocalListDensity.current
    // The card look (#472): the active palette from the theme, and the ONE text-ink decision for
    // this row. Read here so the card fill and every text line below answer from the same palette.
    val mailPalette = LocalMailListPalette.current
    val listTextColor = mailListTextColor(unread, mailPalette)
    val rowPadding = when (density) {
        ListDensity.COMPACT -> 6.dp
        ListDensity.NORMAL -> 10.dp
        ListDensity.SPACED -> 16.dp
    }
    val previewLines = LocalPreviewLines.current.lines
    // Today → the time, this year → day and month, any other year → a short numeric date, so a
    // 2019 row and a 2026 one cannot read the same. Numeric on that last step to keep the stamp
    // narrow: it shares this line with the sender's name.
    val receivedLabel = remember(email.receivedAt) { MailDates.formatListDate(email.receivedAt) }
    val motionOn = rememberMotionEnabled()
    // Return-from-message emphasis: a soft accent tint that rises then fades, so the eye lands on
    // the row just left. Skipped, and consumed at once, under reduced motion.
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        if (motionOn) {
            // Small lead-in so the flash lands just after the list is back, rather than at the
            // start of the return transition, where it read as a touch by mistake.
            delay(100)
            highlight.animateTo(1f, tween(160))
            highlight.animateTo(0f, tween(840, easing = FastOutSlowInEasing))
        }
        onHighlightShown()
    }
    // Entering multi-select ticks, the way K-9 and every list built on the old View system do:
    // there View.performLongClick() fires the haptic itself, while Compose's combinedClickable does
    // not (foundation 1.7), so a long-press dropped the reader into selection mode with no
    // confirmation at all. Kept here rather than at the call sites so the top-level row and the
    // conversation child cannot drift apart.
    // NOTE when Compose is next bumped: foundation 1.8 added `hapticFeedbackEnabled = true` to
    // combinedClickable, which performs this tick itself. Drop this wrapper then, or it buzzes twice.
    val haptics = LocalHapticFeedback.current
    val enterSelection = onLongClick?.let { enter ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            enter()
        }
    }
    // The row's file parts, computed ONCE: both the chips and the paperclip below turn on it, and a
    // row that answered "do I have files?" twice could answer differently and draw both.
    // `fileAttachmentParts()` is the SAME classifier the reader uses on the same parts, so a message
    // cannot show two chips here and three rows when opened.
    val attachmentParts = remember(email.id, email.attachments) {
        if (onOpenAttachment == null) emptyList() else email.fileAttachmentParts()
    }
    val chipBackground = chipFill(MaterialTheme.colorScheme)
    val rowColor = rowBackground(
        scheme = MaterialTheme.colorScheme,
        selected = selected,
        current = current,
        flash = highlight.value,
        card = mailPalette.card,
    )
    // The card: rounded, lifted off the pane by the declared gutters, and painted with exactly the
    // one background the state machine decided. The gutters are OUTSIDE the clip so the pane shows
    // through around the card, which is what makes a row read as its own rectangle — the hairline
    // of #464 read as one flat sheet, and this is the shape that does not (#472).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MailListDimens.gutterH, vertical = MailListDimens.gutterV)
            .clip(MailListDimens.shape)
            .background(rowColor)
            .combinedClickable(onClick = onClick, onLongClick = enterSelection)
            .padding(start = 16.dp, end = 4.dp, top = rowPadding, bottom = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The sender's avatar is on the reader's switch (#144): off, no avatar and no spacing. The
        // Spacer is INSIDE the guard: left outside, its 12 dp would add to the Row's own start
        // padding and leave a dead band at the start of every row. What the guard draws is the full
        // avatar chain — device photo, then the sender domain's logo, then a monogram (task #464) —
        // read here rather than passed in, so all three call sites follow it.
        if (LocalListMonogram.current) {
            ContactAvatar(
                email = (recipient ?: email.from.firstOrNull())?.email ?: senderName,
                name = recipient?.display() ?: senderName,
                photoUri = null,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nameLine,
                    style = MaterialTheme.typography.titleMedium,
                    // Unread is told by the TEXT, as Gmail does: WHITE (palette) AND bold against
                    // a read row's light-grey regular ink (#472). The row behind both is the same
                    // card, so it is the dimmer read text that makes unread stand out.
                    color = listTextColor,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // A trashed draft shows its recipient like a sent mail (#69), so mark it "(Draft)"
                // wherever it surfaces to keep the two apart.
                if (showDraftBadge) {
                    Spacer(Modifier.width(6.dp))
                    DraftLabel(fill = chipBackground)
                }
                // A second chip, right of "(Draft)": the phone is still holding this one for the
                // server (#95). Two chips rather than one label, so an uploaded draft and a held
                // one read the same up to the extra word.
                if (showNotUploadedBadge) {
                    Spacer(Modifier.width(6.dp))
                    NotUploadedLabel(fill = chipBackground)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = receivedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    // Same read/unread text treatment as the sender and subject: the stamp is
                    // bright on an unread row and dimmed on a read one.
                    color = listTextColor,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = email.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_no_subject),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    // Unread subject stays WHITE and bold; a read one dims to light grey and
                    // relaxes — the same text treatment as the sender line above (#472).
                    color = listTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Conversation pill — only when 2+ of the thread's messages are in this view.
                // Gated on both [threadExpandable] and [threadCount] > 1: without the count guard
                // a thread whose other messages sit in Trash would show a bogus "(1)" pill (#75).
                // Tappable when a handler is given, otherwise a static count badge.
                if (threadExpandable && threadCount > 1) {
                    Spacer(Modifier.width(6.dp))
                    ThreadPill(
                        count = threadCount,
                        expanded = expanded,
                        onToggleExpand = onToggleExpand,
                        fill = chipBackground,
                    )
                }
            }
            if (previewLines > 0) {
                email.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        // The preview is message text, so it wears the read/unread ink with the
                        // sender and subject — a read row is grey all the way down (#472).
                        color = listTextColor,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The attachment chips, LAST inside the weighted column and emitted only when there is
            // something to draw. That placement is the whole answer to "how does row height vary":
            // this Row has no fixed or minimum height anywhere, so its height is its content, and a
            // message with no attachments emits NOTHING here and is exactly as tall as it was. A list
            // where every row grew because some rows have chips would show the reader fewer messages
            // per screen and buy them nothing.
            if (onOpenAttachment != null && attachmentParts.isNotEmpty()) {
                AttachmentChips(
                    email = email,
                    parts = attachmentParts,
                    fill = chipBackground,
                    openingKey = openingAttachmentKey,
                    onOpen = onOpenAttachment,
                )
            }
            originLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Spacer(Modifier.size(4.dp))
                // Tint the chip with the account's accent when there is one; a folder has none.
                val chipColor = originColor ?: MaterialTheme.colorScheme.primary
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = chipColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (originColor != null) originColor.copy(alpha = 0.16f)
                            else chipBackground,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // A small paperclip flags rows carrying an attachment, left of the favourite star. Where the
        // chips are drawn it is redundant -- the files are named right there -- so it is not also
        // shown. It still appears for a row that has the flag but no parts: a message cached before
        // schema v28, or a search hit, where "there is something in here" is all that is known.
        if (email.hasAttachment && attachmentParts.isEmpty()) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = stringResource(R.string.a11y_has_attachment),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (onToggleFavourite != null) {
            // The ★/☆ glyph is meaningless to a screen reader, so its semantics are replaced with
            // a state-aware label and a button role.
            val favLabel = stringResource(
                if (email.isFlagged) R.string.a11y_unfavourite else R.string.a11y_favourite,
            )
            // Micro-pop: favouriting springs the star and pops it to coral — the one place vivid
            // coral is earned.
            val pop = remember { Animatable(1f) }
            var firstPass by remember { mutableStateOf(true) }
            LaunchedEffect(email.isFlagged) {
                if (firstPass) { firstPass = false; return@LaunchedEffect }
                if (email.isFlagged && motionOn) {
                    pop.snapTo(1.4f)
                    pop.animateTo(
                        1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    )
                }
            }
            Text(
                text = if (email.isFlagged) "★" else "☆",
                style = MaterialTheme.typography.titleLarge,
                color = if (email.isFlagged) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavourite)
                    .padding(8.dp)
                    .scale(pop.value)
                    .clearAndSetSemantics {
                        contentDescription = favLabel
                        role = Role.Button
                    },
            )
        }
    }
}

/**
 * The conversation pill on a collapsed thread row: a message [count] and a chevron that unfolds the
 * thread inline. Tappable when [onToggleExpand] is given, a plain count badge otherwise; screen
 * readers get a labelled button with an expanded/collapsed state, so the affordance is never
 * conveyed by the glyph alone. [fill] is handed down rather than read from the theme, the pill's
 * fill depending on the row it sits on (see [chipFill]).
 */
@Composable
private fun ThreadPill(
    count: Int,
    expanded: Boolean,
    onToggleExpand: (() -> Unit)?,
    fill: Color,
) {
    // Belt-and-suspenders: never render a "(1)" (or empty) pill — a conversation is 2+ messages.
    if (count <= 1) return
    val motionOn = rememberMotionEnabled()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motionOn) tween(180) else snap(),
        label = "threadChevron",
    )
    val pillLabel = stringResource(R.string.a11y_conversation_pill, count)
    val stateLabel = stringResource(
        if (expanded) R.string.a11y_conversation_expanded else R.string.a11y_conversation_collapsed,
    )
    val base = Modifier
        .clip(MaterialTheme.shapes.small)
        .background(fill)
    val pillModifier = if (onToggleExpand != null) {
        base
            .clickable(onClick = onToggleExpand)
            .semantics {
                contentDescription = pillLabel
                stateDescription = stateLabel
                role = Role.Button
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    } else {
        base.padding(horizontal = 6.dp, vertical = 1.dp)
    }
    Row(modifier = pillModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onToggleExpand != null) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }
    }
}

/**
 * A light "(Draft)" chip on a list row whose message is a draft (#69). Matches the static
 * [ThreadPill]'s muted weight so it reads as a marker, not an action, and carries no click or extra
 * semantics. [fill] is handed down for the reason given on [chipFill].
 */
/**
 * The chip next to "(Draft)" on a draft this phone holds and the server has not got yet (#95). Just
 * as informational: the row has no action of its own until the draft is uploaded.
 */
@Composable
private fun NotUploadedLabel(fill: Color) {
    Text(
        text = stringResource(R.string.local_draft_not_uploaded),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun DraftLabel(fill: Color) {
    Text(
        text = stringResource(R.string.draft_label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

    /** How many chips a row draws before it stops and says how many are left. Four files is already
     *  an unusual message; twenty is a row six lines tall in a list meant to be scanned. */
private const val MAX_ATTACHMENT_CHIPS = 3

    /** Longest filename a chip spells out before [attachmentChipLabel] shortens it. */
private const val MAX_CHIP_NAME = 22

/**
 * The attachment chips of one row: one per file, tappable, wrapping rather than truncating.
 *
 * WRAPPED, NOT SCROLLED, NOT CUT. A FlowRow is the same answer this app already gave for the
 * settings confirm slot -- "wrapped where the labels don't fit, never truncated". A horizontal
 * scroller inside a vertically scrolling list is the alternative and it is worse: the row would eat
 * drags meant for the list. Past [MAX_ATTACHMENT_CHIPS] the row stops and says "+N", which opens the
 * message, where all of them are listed with their sizes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentChips(
    email: Email,
    parts: List<EmailBodyPart>,
    fill: Color,
    openingKey: String?,
    onOpen: (EmailBodyPart) -> Unit,
) {
    Spacer(Modifier.size(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.take(MAX_ATTACHMENT_CHIPS).forEach { part ->
            AttachmentChip(
                part = part,
                fill = fill,
                busy = openingKey != null && openingKey == attachmentKey(email, part),
                onOpen = { onOpen(part) },
            )
        }
        val hidden = parts.size - MAX_ATTACHMENT_CHIPS
        if (hidden > 0) {
            Text(
                text = stringResource(R.string.list_more_attachments, hidden),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(fill)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    part: EmailBodyPart,
    fill: Color,
    busy: Boolean,
    onOpen: () -> Unit,
) {
    val fallback = stringResource(R.string.message_attachment_fallback)
    val name = part.name?.takeIf { it.isNotBlank() } ?: fallback
    // The screen reader is given the WHOLE name, never the shortened one: shortening is a fix for a
    // narrow screen, and a screen reader does not have one.
    val spoken = stringResource(R.string.a11y_open_attachment, name)
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The paperclip becomes the progress ring in place, so the chip that was touched is the
        // chip that answers, and the row does not reflow while it downloads.
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(12.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = attachmentChipLabel(name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * A filename shortened to fit a chip on a narrow phone -- from the MIDDLE, keeping both ends.
 *
 * The folder sidebar settled this argument for this module already (b5e47807e): when 28 names would
 * not fit, the answer was explicitly NOT to truncate them into unreadability -- the sheet was
 * widened and the label shrunk so the names stayed whole, and the rows that still wrapped were left
 * wrapping. A chip cannot be widened, so the same judgement has to be spent differently: keep what
 * carries the meaning and cut what does not.
 *
 * For a filename that is the two ENDS. "Quarterly_Financial_Report_Final_v3_SIGNED.pdf" tail-cut to
 * "Quarterly_Financial_Repo…" has lost the version, the status AND the fact that it is a PDF -- and
 * the extension is exactly what tells the reader what will open. Cut the middle and
 * "Quarterly_Fin…_SIGNED.pdf" still answers both questions a glance is asking.
 *
 * No maxLines ellipsis does this, which is why the label is shortened as a STRING before Compose
 * sees it. [maxLines] is still 1 on the Text as a backstop for a font scale this cannot predict.
 */
internal fun attachmentChipLabel(name: String, max: Int = MAX_CHIP_NAME): String {
    if (name.length <= max) return name
    // One character for the ellipsis, and the rest split with the TAIL favoured -- the extension and
    // whatever qualifier sits before it are the informative end.
    val tail = ((max - 1) * 2) / 3
    val head = max - 1 - tail
    return name.take(head) + "…" + name.takeLast(tail)
}

    /**
     * Names one attachment of one message, uniquely across the whole list.
     *
     * The message id is in the key because neither half of the part is enough on its own: a JMAP
     * blob id is unique only within its account (the same reason the `emails` table is keyed on
     * `(accountId, id)`), and an IMAP part id is a section NUMBER -- "2" on one message and "2" on
     * the next are different files with the same name for it. Keyed on the part alone, tapping a
     * file on one row would spin a chip on another.
     *
     * Shared by the row and by the view model that reports which download is running, so the two
     * cannot spell it differently -- which would show a spinner nowhere at all.
     */
internal fun attachmentKey(email: Email, part: EmailBodyPart): String =
    "${email.accountId}\u0000${email.id}\u0000${part.blobId ?: part.partId}"
