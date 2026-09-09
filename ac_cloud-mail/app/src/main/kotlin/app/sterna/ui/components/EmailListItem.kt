package app.sterna.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
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
 * The background a message row is painted with — one place, three states, in this order.
 *
 * Selection comes first and beats everything: the row's own colour is the ONLY sign that a row is
 * selected, and selection is what arms the destructive actions. Then [current], the row the reading
 * pane is showing (#103), ahead of the unread tint since the open message is marked read a moment
 * later anyway. Then an unread row's [ColorScheme.surfaceContainerHighest] — bold text alone was
 * too faint in the dark scheme (#141) — against a read row's plain surface.
 *
 * [unreadTint] is the reader's answer to that last state; off, this gives back exactly what it gave
 * before #141. It governs the BACKGROUND only, so a reader who finds it loud still sees which mail
 * is unread. [flash] tints whichever base was retained rather than branching, so it cannot erase
 * the selected or unread state while it plays.
 */
internal fun rowBackground(
    scheme: ColorScheme,
    selected: Boolean,
    current: Boolean,
    unread: Boolean,
    unreadTint: Boolean,
    flash: Float,
): Color {
    val base = when {
        selected -> scheme.secondaryContainer
        current -> scheme.primaryContainer
        unread && unreadTint -> scheme.surfaceContainerHighest
        else -> scheme.surface
    }
    return if (flash > 0f) lerp(base, scheme.primary, 0.14f * flash) else base
}

/**
 * The fill behind the small rounded chips a row carries. Decided once per row, because the chips
 * are painted ON the row and have to know what they sit on.
 *
 * Until [rowBackground] grew a state (#141) every chip was surfaceVariant over plain surface. An
 * unread row now carries surfaceContainerHighest, and in the light scheme those two roles are one
 * step out of 255 per channel apart: the chips did not fade, they disappeared and left their labels
 * floating. surfaceContainerLowest is what an unread row's chips take instead — opaque, in every
 * Material You scheme, clear of the unread background in both themes, and carrying the chips' own
 * onSurfaceVariant ink at 6.5:1 in light.
 *
 * The rule is "a chip steps away from its row", not "a chip is darker": tying it to a fixed
 * direction is what made the light theme swallow them. [unreadTint] has to be the same answer
 * [rowBackground] got, or a pale chip lands on a pale row.
 *
 * Two rows this does NOT rescue, both as surface left them before #141 and both filed rather than
 * fixed: a SELECTED row, where a surfaceVariant chip reads by hue and vanishes under a monochrome
 * palette; and the RETURN FLASH, which passes within ~5/255 of surfaceVariant at its peak. [flash]
 * is deliberately not a parameter — a chip changing colour mid-animation is a bigger change than
 * the defect.
 */
internal fun chipFill(
    scheme: ColorScheme,
    selected: Boolean,
    unread: Boolean,
    unreadTint: Boolean,
): Color = if (unread && unreadTint && !selected) scheme.surfaceContainerLowest else scheme.surfaceVariant

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
) {
    val senderName = email.from.firstOrNull()?.display() ?: stringResource(R.string.message_unknown_sender)
    val recipient = if (showRecipients) email.to.firstOrNull() else null
    val nameLine = if (recipient != null) {
        stringResource(R.string.list_to_recipients, email.to.joinToString { it.display() })
    } else {
        senderName
    }
    val density = LocalListDensity.current
    // Read once, here, and passed to both decisions below, so a row cannot answer "is this row
    // tinted?" twice and differently. Not a remember {}, which would freeze the row on its first
    // value and leave the list half-tinted after a change.
    val unreadTint = LocalUnreadTint.current
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
    val chipBackground = chipFill(
        scheme = MaterialTheme.colorScheme,
        selected = selected,
        unread = unread,
        unreadTint = unreadTint,
    )
    val rowColor = rowBackground(
        scheme = MaterialTheme.colorScheme,
        selected = selected,
        current = current,
        unread = unread,
        unreadTint = unreadTint,
        flash = highlight.value,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor)
            .combinedClickable(onClick = onClick, onLongClick = enterSelection)
            .padding(start = 16.dp, end = 4.dp, top = rowPadding, bottom = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The initials are the reader's to remove (#144). The Spacer is INSIDE the guard: left
        // outside, its 12 dp would add to the Row's own start padding and leave a dead band at the
        // start of every row. Read here rather than passed in, so all three call sites follow it.
        if (LocalListMonogram.current) {
            Monogram(
                seed = (recipient ?: email.from.firstOrNull())?.email ?: senderName,
                label = recipient?.display() ?: senderName,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nameLine,
                    style = MaterialTheme.typography.titleMedium,
                    // Unread is shown by weight (bold) rather than a status dot.
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = email.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_no_subject),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
        // A small paperclip flags rows carrying an attachment, left of the favourite star.
        if (email.hasAttachment) {
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
