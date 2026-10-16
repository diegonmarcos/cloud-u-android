package org.fossify.phone.sms

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitChip
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.phone.R
import java.text.DateFormat
import java.util.Date

/** Test tags of the SMS screens: the Compose UI tests and the screens share them. */
object SmsTags {
    const val LIST = "sms:list"
    const val EMPTY = "sms:empty"
    const val FAB = "sms:fab"
    const val BANNER = "sms"
    const val ARCHIVE_TOGGLE = "sms:archive-toggle"
    fun row(threadId: Long) = "sms:row:$threadId"
    const val CONV_LIST = "conv:list"
    const val CONV_INPUT = "conv:input"
    const val CONV_SEND = "conv:send"
    const val CONV_SIM = "conv:sim"
    const val CONV_ATTACH = "conv:attach"
    const val CONV_COUNTER = "conv:counter"
    fun bubble(id: Long) = "conv:bubble:$id"
    fun retry(id: Long) = "conv:retry:$id"
    const val NEW_TO = "new:to"
    const val NEW_INPUT = "new:input"
    const val NEW_SEND = "new:send"
    fun suggestion(i: Int) = "new:suggestion:$i"
    fun chip(i: Int) = "new:chip:$i"
}

/** A short time for a list row: the clock today, the date otherwise. */
fun smsListTime(date: Long, now: Long = System.currentTimeMillis()): String {
    val day = 24L * 60 * 60 * 1000
    val sameDay = date / day == now / day
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(date)) else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(date))
}

/** The first letter of a name or the digit of a number, for an avatar without a photo. */
fun avatarLetter(title: String): String = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

@Composable
fun rememberUriBitmap(uri: String?, maxPx: Int): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) { decodeUri(context, uri, maxPx) }
    }
    return state.value
}

private fun decodeUri(context: Context, uri: String, maxPx: Int): ImageBitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    resolver.openInputStream(Uri.parse(uri))?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
} catch (e: Exception) {
    null
}

@Composable
fun Avatar(title: String, photoUri: String?, size: Int = 44) {
    val p = LocalKitPalette.current
    val photo = rememberUriBitmap(photoUri, size * 3)
    Box(Modifier.size(size.dp).clip(CircleShape).background(p.surfaceSelected), contentAlignment = Alignment.Center) {
        if (photo != null) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(avatarLetter(title), color = p.textPrimary, fontSize = (size / 2.4f).sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** The banner for "we are not the default SMS app / have no grant": read-only until the owner fixes it. */
@Composable
fun SmsAccessBanner(isDefault: Boolean, canRead: Boolean, onGrant: () -> Unit, onMakeDefault: () -> Unit, modifier: Modifier = Modifier) {
    when {
        !canRead -> KitStatusBanner(
            stringResource(R.string.sms_banner_permission), KitState.WARN, modifier.padding(12.dp), SmsTags.BANNER,
            KitAction(stringResource(R.string.sms_grant), "sms:grant", onClick = onGrant),
        )

        !isDefault -> KitStatusBanner(
            stringResource(R.string.sms_banner_default), KitState.WARN, modifier.padding(12.dp), SmsTags.BANNER,
            KitAction(stringResource(R.string.sms_make_default), "sms:make-default", onClick = onMakeDefault),
        )
    }
}

// ---- conversation list ----------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmsListScreen(
    conversations: List<Conversation>,
    loaded: Boolean,
    isDefault: Boolean,
    canRead: Boolean,
    showArchived: Boolean,
    photoOf: (String) -> String?,
    onOpen: (Conversation) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onToggleArchive: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onNewMessage: () -> Unit,
    onToggleArchiveView: () -> Unit,
    onGrant: () -> Unit,
    onMakeDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SmsAccessBanner(isDefault, canRead, onGrant, onMakeDefault)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (showArchived) R.string.sms_archived else R.string.sms_title),
                    color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleArchiveView, modifier = Modifier.testTag(SmsTags.ARCHIVE_TOGGLE)) {
                    Text(stringResource(if (showArchived) R.string.sms_inbox else R.string.sms_archived), color = p.accent)
                }
            }
            if (loaded && conversations.isEmpty()) {
                KitEmptyState(null, stringResource(if (showArchived) R.string.sms_empty_archive else R.string.sms_empty), Modifier.testTag(SmsTags.EMPTY))
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag(SmsTags.LIST), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(conversations, key = { it.threadId }) { c ->
                        Box {
                            ConversationRow(
                                c, photoOf,
                                Modifier.combinedClickable(onClick = { onOpen(c) }, onLongClick = { menuFor = c.threadId }).testTag(SmsTags.row(c.threadId)),
                            )
                            DropdownMenu(expanded = menuFor == c.threadId, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text(stringResource(if (c.pinned) R.string.sms_unpin else R.string.sms_pin)) }, onClick = { menuFor = null; onTogglePin(c) })
                                DropdownMenuItem(text = { Text(stringResource(if (c.archived) R.string.sms_unarchive else R.string.sms_archive)) }, onClick = { menuFor = null; onToggleArchive(c) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.sms_delete)) }, onClick = { menuFor = null; deleting = c })
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onNewMessage, containerColor = p.accent, contentColor = p.tileInk,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp).testTag(SmsTags.FAB),
        ) { Text("+", fontSize = 26.sp) }
    }
    deleting?.let { c ->
        KitConfirmDialog(
            stringResource(R.string.sms_delete), stringResource(R.string.sms_delete_confirm),
            stringResource(R.string.sms_delete), stringResource(android.R.string.cancel),
            onConfirm = { deleting = null; onDelete(c) }, onDismiss = { deleting = null },
        )
    }
}

@Composable
fun ConversationRow(c: Conversation, photoOf: (String) -> String?, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    // The contact photo is a ContentResolver query: off the main thread, and only for a one-to-one thread.
    val photoUri by produceState<String?>(null, c.addresses) {
        value = if (c.isGroup) null else withContext(Dispatchers.IO) { photoOf(c.addresses.firstOrNull().orEmpty()) }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.title, photoUri)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                c.title, color = p.textPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (c.isUnread) FontWeight.Bold else FontWeight.Normal,
            )
            val snippet = if (c.draft != null) stringResource(R.string.sms_draft) + ": " + c.snippet else c.snippet
            Text(
                snippet, color = if (c.hasFailed) p.bad else p.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (c.isUnread) FontWeight.Medium else FontWeight.Normal,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(smsListTime(c.date), color = p.textSecondary, fontSize = 12.sp)
            if (c.isUnread) {
                Box(Modifier.padding(top = 4.dp).clip(CircleShape).background(p.accent).widthIn(min = 20.dp).heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
                    Text(c.unreadCount.toString(), color = p.tileInk, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp), textAlign = TextAlign.Center)
                }
            } else if (c.pinned) {
                Text("•", color = p.accent, fontSize = 18.sp)
            }
        }
    }
}

// ---- conversation -----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    title: String,
    messages: List<SmsMessage>,
    isDefault: Boolean,
    canRead: Boolean,
    sims: List<SimInfo>,
    selectedSim: SimInfo?,
    draft: String,
    pendingImage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onPickSim: (SimInfo) -> Unit,
    onRetry: (SmsMessage) -> Unit,
    onDelete: (SmsMessage) -> Unit,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onMakeDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = p.textPrimary, fontSize = 26.sp) }
            Text(title, color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        SmsAccessBanner(isDefault, canRead, onGrant, onMakeDefault)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(SmsTags.CONV_LIST), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(messages, key = { _, m -> (if (m.isMms) -m.id else m.id) }) { index, m ->
                val dayStart = index == 0 || !sameDay(messages[index - 1].date, m.date)
                if (dayStart) {
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.date)), color = p.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                Box {
                    MessageBubble(m, onRetry, Modifier.combinedClickable(onClick = {}, onLongClick = { menuFor = if (m.isMms) -m.id else m.id }))
                    DropdownMenu(expanded = menuFor == (if (m.isMms) -m.id else m.id), onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sms_delete)) }, onClick = { menuFor = null; onDelete(m) })
                    }
                }
            }
        }
        if (isDefault) {
            Composer(
                draft, pendingImage, sims, selectedSim, onDraftChange, onSend, onAttach, onClearAttachment, onPickSim, SmsTags.CONV_INPUT, SmsTags.CONV_SEND,
            )
        } else {
            Text(stringResource(R.string.sms_cannot_send), color = p.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val day = 24L * 60 * 60 * 1000
    return a / day == b / day
}

@Composable
fun MessageBubble(m: SmsMessage, onRetry: (SmsMessage) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val outgoing = m.isOutgoing
    val state = m.deliveryState()
    Column(modifier.fillMaxWidth().testTag(SmsTags.bubble(if (m.isMms) -m.id else m.id)), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(if (outgoing) p.accent else p.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            m.attachments.filter { it.isImage }.forEach { a ->
                val bitmap = rememberUriBitmap(a.uri, 480)
                if (bitmap != null) {
                    Image(bitmap, contentDescription = stringResource(R.string.sms_attachment), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.size(4.dp))
                }
            }
            if (m.attachments.any { !it.isImage }) {
                Text(stringResource(R.string.sms_attachment), color = if (outgoing) p.tileInk else p.textSecondary, fontSize = 13.sp)
            }
            if (m.body.isNotEmpty()) Text(m.body, color = if (outgoing) p.tileInk else p.textPrimary, fontSize = 16.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.date))
            val status = when (state) {
                DeliveryState.Sending -> stringResource(R.string.sms_status_sending)
                DeliveryState.Sent -> stringResource(R.string.sms_status_sent)
                DeliveryState.Delivered -> stringResource(R.string.sms_status_delivered)
                DeliveryState.Failed -> stringResource(R.string.sms_status_failed)
                DeliveryState.Draft -> stringResource(R.string.sms_draft)
                DeliveryState.Received -> ""
            }
            Text(if (status.isEmpty()) time else "$time · $status", color = if (state == DeliveryState.Failed) p.bad else p.textSecondary, fontSize = 11.sp)
            if (m.canRetry && !m.isMms) {
                TextButton(onClick = { onRetry(m) }, modifier = Modifier.testTag(SmsTags.retry(m.id))) { Text(stringResource(R.string.sms_retry), color = p.accent, fontSize = 12.sp) }
            }
        }
    }
}

/** The text box with the SIM chip, the attach button, the part counter and send. Shared by the conversation and the new-message screen. */
@Composable
fun Composer(
    draft: String,
    pendingImage: String?,
    sims: List<SimInfo>,
    selectedSim: SimInfo?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onPickSim: (SimInfo) -> Unit,
    inputTag: String,
    sendTag: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    var simMenu by remember { mutableStateOf(false) }
    val segments = remember(draft) { SmsSegmenter.split(draft) }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (pendingImage != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                val bitmap = rememberUriBitmap(pendingImage, 160)
                if (bitmap != null) Image(bitmap, contentDescription = stringResource(R.string.sms_attachment), contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                TextButton(onClick = onClearAttachment) { Text(stringResource(R.string.sms_remove_attachment), color = p.accent) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAttach, modifier = Modifier.testTag(SmsTags.CONV_ATTACH)) { Text("+", color = p.accent, fontSize = 22.sp) }
            TextField(
                value = draft, onValueChange = onDraftChange, modifier = Modifier.weight(1f).testTag(inputTag), maxLines = 5,
                placeholder = { Text(stringResource(R.string.sms_type_message), color = p.textSecondary) },
            )
            if (sims.size > 1) {
                Box {
                    TextButton(onClick = { simMenu = true }, modifier = Modifier.testTag(SmsTags.CONV_SIM)) {
                        Text(selectedSim?.let { stringResource(R.string.sms_sim_slot, it.slotIndex + 1) } ?: stringResource(R.string.sms_sim_pick), color = p.accent, fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
                        sims.forEach { sim ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sms_sim_slot, sim.slotIndex + 1) + " · " + sim.displayName) },
                                onClick = { simMenu = false; onPickSim(sim) },
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onSend, enabled = draft.isNotBlank() || pendingImage != null, modifier = Modifier.testTag(sendTag)) {
                Text(stringResource(R.string.sms_send), color = p.accent)
            }
        }
        if (segments.count > 1 || (segments.count == 1 && segments.remainingInLastPart <= 20)) {
            Text(
                stringResource(R.string.sms_segment_counter, segments.remainingInLastPart, segments.count),
                color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp).testTag(SmsTags.CONV_COUNTER),
            )
        }
    }
}

// ---- new message ------------------------------------------------------------------------------

@Composable
fun NewMessageScreen(
    recipients: List<String>,
    query: String,
    suggestions: List<Recipient>,
    draft: String,
    pendingImage: String?,
    isDefault: Boolean,
    canRead: Boolean,
    sims: List<SimInfo>,
    selectedSim: SimInfo?,
    onQueryChange: (String) -> Unit,
    onAddRecipient: (String) -> Unit,
    onRemoveRecipient: (Int) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onPickSim: (SimInfo) -> Unit,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onMakeDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = p.textPrimary, fontSize = 26.sp) }
            Text(stringResource(R.string.sms_new_message), color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        SmsAccessBanner(isDefault, canRead, onGrant, onMakeDefault)
        if (recipients.isNotEmpty()) {
            LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(recipients) { i, r -> KitChip(r, onRemove = { onRemoveRecipient(i) }, tag = SmsTags.chip(i)) }
            }
        }
        TextField(
            value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(SmsTags.NEW_TO), singleLine = true,
            placeholder = { Text(stringResource(R.string.sms_to), color = p.textSecondary) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(suggestions) { i, r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onAddRecipient(r.number) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag(SmsTags.suggestion(i)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(r.name, r.photoUri, 40)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(r.name, color = p.textPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (r.name != r.number) Text(r.number, color = p.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
        if (isDefault) {
            Composer(draft, pendingImage, sims, selectedSim, onDraftChange, onSend, onAttach, onClearAttachment, onPickSim, SmsTags.NEW_INPUT, SmsTags.NEW_SEND)
        }
    }
}

/** Which SIM to send on, asked when two are active and nothing decides (the owner has not picked, no thread history, no default). */
@Composable
fun SimPickerDialog(sims: List<SimInfo>, onPick: (SimInfo) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sms_sim_pick)) },
        text = {
            Column {
                sims.forEach { sim ->
                    TextButton(onClick = { onPick(sim) }, modifier = Modifier.fillMaxWidth().testTag("sim:" + sim.subscriptionId)) {
                        Text(stringResource(R.string.sms_sim_slot, sim.slotIndex + 1) + " · " + sim.displayName)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
