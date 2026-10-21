package com.diegonmarcos.superapp.launcher

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.notificationcenter.ReplyActions
import com.diegonmarcos.superapp.ui.Haptics

/**
 * #1068 — THE list of what every notification box can do, declared once. A redesign (the One UI restyles of Notify)
 * once dropped the per-box Mark read / Archive pills into a long-press menu and nobody noticed; the guard
 * (NotifActionsGuardTest) reads these lists and fails when a box, on Notify or on Boards ▸ Inbox, stops drawing one.
 */
internal object NotifActions {
    const val MARK_READ = "mark_read"
    const val ARCHIVE = "archive"
    const val OPEN = "open"
    const val REPLY = "reply"

    /** Drawn on EVERY box (one app / publisher), as a One UI action-pill row under its notifications. */
    val REQUIRED_PER_BOX = listOf(MARK_READ, ARCHIVE, OPEN, REPLY)

    /** Gestures on every notification row inside a box (kept in NotifRowItem; the long-press sheet repeats the taps). */
    val REQUIRED_PER_ROW = listOf("swipe_right_mark_read", "swipe_left_archive", "long_press_sheet", "tap_open")

    /** Page-level: the centred pill row under the cards, and the way back out of the Archive. */
    val REQUIRED_PER_PAGE = listOf("mark_all_read", "archive_all", "restore")

    fun tag(action: String, key: String) = "agg:box-action:$action:$key"
}

/**
 * The action row of one box: Mark read, Archive, Open, Reply as One UI action pills (36dp, 18dp radius, 14sp). Reply
 * sends inline through the notification's own reply action when the app offered one ([replyKey] = its sbn key) and
 * otherwise opens the thread, since a pill that does nothing is the failure this row exists to prevent.
 */
@Composable
internal fun NotifActionRow(
    key: String,
    onRead: () -> Unit,
    onArchive: () -> Unit,
    onOpen: (() -> Unit)?,
    replyKey: String?,
    unread: Boolean = true,
) {
    val ctx = LocalContext.current
    val hostView = LocalView.current
    val canReply = ReplyActions.has(replyKey)
    var replying by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(start = NotifCard.pad, end = NotifCard.pad, top = 4.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill(if (unread) AggregatorModel.MARK_READ_LABEL else "Read", NotifActions.tag(NotifActions.MARK_READ, key),
                "Mark every notification of this app read", Modifier.weight(1f)) { Haptics.tap(hostView); onRead() }
            ActionPill(AggregatorModel.ARCHIVE_LABEL, NotifActions.tag(NotifActions.ARCHIVE, key),
                "Archive this app's notifications", Modifier.weight(1f)) { Haptics.tap(hostView); onArchive() }
            ActionPill("Open", NotifActions.tag(NotifActions.OPEN, key), "Open the app", Modifier.weight(1f),
                enabled = onOpen != null) { Haptics.tap(hostView); onOpen?.invoke() }
            ActionPill("Reply", NotifActions.tag(NotifActions.REPLY, key),
                if (canReply) "Reply from here" else "Reply (opens the conversation)", Modifier.weight(1f),
                enabled = canReply || onOpen != null) {
                Haptics.tap(hostView)
                if (canReply) replying = !replying else onOpen?.invoke()
            }
        }
        if (replying && canReply) Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(NotifCard.actionShape).background(NotifCard.pill)
                .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 9.dp)) {
                if (draft.isEmpty()) Text("Reply", color = NotifCard.meta, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font)
                BasicTextField(draft, { draft = it }, singleLine = true, cursorBrush = SolidColor(NotifCard.title),
                    textStyle = TextStyle(color = NotifCard.title, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font),
                    modifier = Modifier.fillMaxWidth().testTag(NotifActions.tag("reply_field", key)))
            }
            Text("Send", color = NotifCard.title, fontSize = NotifCard.bodySp, fontWeight = FontWeight.Medium,
                fontFamily = NotifCard.font,
                modifier = Modifier.clip(NotifCard.actionShape).clickable(enabled = draft.isNotBlank()) {
                    val ok = ReplyActions.send(ctx, replyKey, draft.trim())
                    Toast.makeText(ctx, if (ok) "Reply sent" else "Reply failed", Toast.LENGTH_SHORT).show()
                    if (ok) { draft = ""; replying = false }
                }.padding(horizontal = 16.dp, vertical = 9.dp).testTag(NotifActions.tag("reply_send", key)))
        }
    }
}

@Composable
private fun ActionPill(label: String, tag: String, description: String, modifier: Modifier, enabled: Boolean = true,
                       onClick: () -> Unit) {
    Box(
        modifier.height(NotifCard.actionHeight).clip(NotifCard.actionShape).background(NotifCard.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) NotifCard.title else NotifCard.meta, fontSize = NotifCard.actionSp,
            fontWeight = FontWeight.Medium, fontFamily = NotifCard.font, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * One UI's row gestures for a list that is not [NotifRowItem] (Boards ▸ Inbox): a swipe right past 22 % of the row calls
 * [onRight] (toggle read) and springs back; a swipe left calls [onLeft] (archive) after sliding the row out. Vertical
 * scrolling is untouched: detectHorizontalDragGestures claims a drag only once it is clearly sideways.
 */
internal fun Modifier.rowSwipe(key: Any, onRight: () -> Unit, onLeft: () -> Unit): Modifier = composed {
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var width by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    this.onSizeChanged { width = it.width }
        .graphicsLayer { translationX = offset.value; alpha = 1f - (kotlin.math.abs(offset.value) / (width.coerceAtLeast(1) * 2f)).coerceIn(0f, 0.55f) }
        .pointerInput(key) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    val far = kotlin.math.abs(total) > kotlin.math.max(64.dp.toPx(), width * 0.22f)
                    scope.launch {
                        if (far && total > 0) onRight()
                        if (far && total < 0) {
                            offset.animateTo(-width.toFloat(), androidx.compose.animation.core.tween(160)); onLeft()
                        } else offset.animateTo(0f, androidx.compose.animation.core.tween(140))
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f) } },
                onHorizontalDrag = { change, dx -> change.consume(); total += dx; scope.launch { offset.snapTo(total) } },
            )
        }
}
