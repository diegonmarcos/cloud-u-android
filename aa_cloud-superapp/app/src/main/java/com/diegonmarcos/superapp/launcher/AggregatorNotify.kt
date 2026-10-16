package com.diegonmarcos.superapp.launcher

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.apps.AppIcon
import com.diegonmarcos.superapp.apps.InboxClasses
import com.diegonmarcos.superapp.apps.PhoneTaxonomy
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The notification half of the aggregator page, in Compose. The shade is edge-to-edge: group headers and rows supply
 * their own structure. A collapsible group is a header whose chevron flips its rows (the fold lives on
 * AggregatorState.collapsedGroups so a toggle tap does not spring it open again), a row's read state is a swipe, and
 * the Archive is ONE collapsed section at the bottom of the page collecting every box the user filed away, whichever
 * card it came from.
 */

private const val TAG = "AggregatorStack"

/** Re-asks the stores a pull or a toggle changed, and drives the ntfy polls of every panel that has channels. */
@Composable
internal fun AggregatorEffects(state: AggregatorState) {
    LaunchedEffect(state.epoch) { state.housekeeping() }
    for ((i, panel) in state.panels.withIndex()) {
        if (!state.pollsNtfy(panel)) continue
        // Keyed on the taxonomy rows too: narrowing a row can bring channels into view that nothing has polled yet.
        LaunchedEffect(i, state.pollEpoch, state.toolsMode, state.servicesMode) { state.pollPanel(i, panel) }
    }
}

// ── kind=notification_center ─────────────────────────────────────────

@Composable
internal fun NotificationCenterBody(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    when (panel.stream) {
        "phone" -> PhoneCenter(state, onOpen)
        "cloud" -> CloudCenter(state, panel, onOpen)
        // C3 Obsv's stream: the ntfy CHANNELS and nothing else — `cloud` minus the in-app feed.
        "channels" -> NtfyGroups(state, panel, onOpen)
        // A card that declares no stream cannot guess one, and it must not look like "you have no notifications".
        else -> {
            LaunchedEffect(panel) {
                android.util.Log.w(TAG, "notification panel '${panel.title}' declares no `stream`: add " +
                    "\"stream\": \"phone\", \"cloud\" or \"channels\" in build.json")
            }
            AggStateLine("no stream declared", Tone.UNKNOWN)
        }
    }
}

@Composable
private fun NotificationAccessOff(extra: String? = null) {
    val ctx = LocalContext.current
    AggStateLine("unavailable · permission not granted", Tone.UNKNOWN)
    if (extra != null) AggCaption(extra)
    else TextButton(modifier = Modifier.testTag(TAG_GRANT), onClick = {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }) { Text("Grant Notification Access", color = LocalKitPalette.current.accent) }
}

/** Phone stream: one group per POSTING APP, keyed on `packageName` and labelled with `appLabel`. There is no package
 *  list here and there must never be one — the key rides on the notification itself. */
@Composable
private fun PhoneCenter(state: AggregatorState, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    if (!granted) {
        // A MISSING CAPABILITY, not an empty inbox: grey, with the way to fix it attached.
        NotificationAccessOff(); return
    }
    val total = remember(state.epoch) { PhoneNotificationStore.all(ctx).size }
    if (total == 0) {
        // Granted and empty is a REAL state and a different one: amber, so it cannot be mistaken for the grey case.
        AggStateLine("silent · nothing captured", Tone.WARN); return
    }
    val views by remember { derivedStateOf { state.phoneViews() } }
    if (views.isEmpty()) AggCaption(AggregatorModel.filteredAwayNote(total)) else GroupList(state, views, onOpen)
}

/** Cloud stream, grouped by PUBLISHER: the ntfy TOPIC for a fleet message, the `source` field for an in-app one. */
@Composable
private fun CloudCenter(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    AggShadeLabel("IN-APP FEED")
    val total = remember(state.epoch) { NotificationStore.all(ctx).size }
    val local by remember { derivedStateOf { state.localFeedViews() } }
    if (local.isEmpty()) {
        if (total == 0) AggStateLine("silent · no in-app events", Tone.WARN)
        else AggCaption(AggregatorModel.filteredAwayNote(total))
    } else GroupList(state, local, onOpen)
    AggShadeLabel("CHANNELS")
    NtfyGroups(state, panel, onOpen)
}

/**
 * One group per ntfy topic in this card's declared scopes, each with a live verdict: "N · 5m ago" (green), "silent 7d"
 * (amber: ntfy answers 200 for a topic nobody has published to, so a dead publisher looks like a quiet one) and the
 * poll's failure sentence (grey, never red and never absent: a failed fetch is a claim about THIS PHONE'S network).
 * The order is decided ONCE from the last measurement, so the poll landing never re-sorts boxes under the thumb.
 */
@Composable
internal fun NtfyGroups(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val topics = state.ntfyTopics(panel)
    if (topics.isEmpty()) {
        LaunchedEffect(panel) {
            android.util.Log.w(TAG, "ntfy panel '${panel.title}' matched no channel: no build.json::ui.ntfy topic " +
                "falls in scopes (" + panel.scopes.joinToString(", ").ifBlank { "all" } + ")")
        }
        AggStateLine("no channels in scope", Tone.UNKNOWN); return
    }
    val order = remember(state.epoch, state.sortMode, topics) {
        AggregatorModel.orderTopics(topics, state.sortMode, state.ntfyCache.toMap())
    }
    GroupList(state, state.ntfyViews(panel, order), onOpen)
}

// ── groups ───────────────────────────────────────────────────────────

/** Draw [views] as a per-app list. Archived boxes are not drawn here: they live in the page's Archive. */
@Composable
internal fun GroupList(state: AggregatorState, views: List<GroupView>, onOpen: (String) -> Unit) {
    for (v in views) {
        if (state.isArchived(v.group.key)) continue
        androidx.compose.runtime.key(v.group.key) { GroupBlock(state, v, archived = false, onOpen) }
    }
}

/**
 * One app / publisher drawn as a notification-shade GROUP: a header bar — icon, name, count chip — with its
 * notifications folded under it. Tapping the header COLLAPSES the group (launching moved onto the icon: a header that
 * both folded and launched could only do one of the two).
 */
@Composable
internal fun GroupBlock(state: AggregatorState, view: GroupView, archived: Boolean, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val g = view.group
    val hostView = LocalView.current
    val open = state.collapsedGroups[g.key] != true
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(140), label = "chevron")
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(AggTags.group(g.key))) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.textPrimary.copy(alpha = 0.12f))
                .clickable { if (open) state.collapsedGroups[g.key] = true else state.collapsedGroups.remove(g.key) }
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag(AggTags.groupHeader(g.key)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupAvatar(g, onOpen)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(g.label, color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (g.sub.isNotBlank() && !g.sub.equals(g.label, ignoreCase = true)) {
                    Text(g.sub, color = p.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
            // The count chip: ONE text holding the whole verdict, because an async ntfy group repaints it with a
            // failure sentence that has no count in it at all.
            Text(view.chip.text, color = toneColor(p, view.chip.tone), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(p.textPrimary.copy(alpha = 0.13f))
                    .padding(horizontal = 8.dp, vertical = 3.dp).testTag(AggTags.groupChip(g.key)))
            // Clear all — the group-level twin of a swipe, one decision about the app rather than about any one row.
            Icon(
                painterResource(R.drawable.ic_check_all), stringResource(R.string.notif_mark_group_read),
                tint = p.textPrimary,
                modifier = Modifier.padding(start = 8.dp).size(18.dp).alpha(0.55f)
                    .clickable { Haptics.tap(hostView); state.markGroupRead(g) }.testTag(AggTags.groupCheck(g.key)),
            )
            ArchivePill(state, g.key, archived)
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = p.textPrimary,
                modifier = Modifier.padding(start = 6.dp).size(16.dp).alpha(0.5f).rotate(turn))
        }
        if (open) Column(Modifier.fillMaxWidth()) {
            for (r in g.rows) androidx.compose.runtime.key(r.id.ifBlank { "${r.ts}/${r.title}" }) {
                NotifRowItem(state, r, g.launchPackage)
            }
        }
    }
}

/**
 * The Archive control on an app box's header. A WORD, not a glyph ("file this away" has no icon in this app's set, and
 * a borrowed folder would be one more symbol next to a tick that already means something else), and "Restore" once
 * the box is in the Archive. It MOVES THE BOX immediately — writing the preference alone would read as a dead button.
 */
@Composable
private fun ArchivePill(state: AggregatorState, key: String, archived: Boolean) {
    val p = LocalKitPalette.current
    val hostView = LocalView.current
    Text(
        if (archived) AggregatorModel.ARCHIVE_RESTORE_LABEL else AggregatorModel.ARCHIVE_LABEL,
        color = p.textPrimary.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(9.dp)).background(p.textPrimary.copy(alpha = 0.13f))
            .clickable { Haptics.tap(hostView); state.setArchived(key, !state.isArchived(key)) }
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .semantics { contentDescription = if (archived) "Restore this app out of the Archive" else "Archive this app" }
            .testTag(AggTags.groupArchive(key)),
    )
}

private val MONOGRAM_HUES = longArrayOf(0xFF6D5AE6, 0xFF1F8A70, 0xFFB5556D, 0xFF2C6FB5, 0xFF9A6A2F, 0xFF4E7A2A)

/**
 * The app's own launcher icon when there is a package to ask for one, and a coloured monogram when there is not. An ntfy
 * topic has no launcher icon, and a shade with a blank column where every icon should be reads as broken rather than as
 * cloud. #498-ANR: the icon is never loaded on the main thread — a cold getApplicationIcon loads the ENTIRE foreign
 * APK's resources; a miss shows the monogram immediately and ONE background load swaps the icon in.
 */
@Composable
private fun GroupAvatar(g: NotifGroup, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val pkg = g.launchPackage
    var icon by remember(pkg) { mutableStateOf(if (pkg.isBlank()) null else AppIconCache.cached(pkg)) }
    if (icon == null && pkg.isNotBlank() && !AppIconCache.knownMissing(pkg)) {
        LaunchedEffect(pkg) { icon = withContext(Dispatchers.IO) { AppIconCache.load(ctx, pkg) } }
    }
    val tap = if (g.launchPackage.isNotBlank() || g.url.isNotBlank()) Modifier.clickable {
        // A group that names a package IS that app, so launching it stays a direct intent. A group that only carries a
        // URL is a published ntfy channel — plain web content this app renders itself, so it goes through the
        // dispatcher and keeps the user (and the Back gesture) inside the notification stack they tapped from.
        if (g.launchPackage.isNotBlank()) {
            runCatching { ctx.packageManager.getLaunchIntentForPackage(g.launchPackage)?.let { ctx.startActivity(it) } }
        } else onOpen(g.url)
    } else Modifier
    val shown = icon
    if (shown != null) AppIcon(shown, 30.dp, tap)
    else Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(Color(MONOGRAM_HUES[AggregatorModel.monogramHue(g.key, MONOGRAM_HUES.size)])).then(tap),
        contentAlignment = Alignment.Center,
    ) { Text(g.label.trim().take(1).uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

// ── rows ─────────────────────────────────────────────────────────────

/**
 * One notification as a shade ROW: a status stripe, then title and relative time on one line with the snippet under it.
 * Dense on purpose — no card, no margin, neighbours parted by a hairline. Unread is said three times over: the stripe
 * lights up, the title goes bold, and the row takes a faint tint.
 *
 * READ IS A SWIPE. Dragging the row sideways past a threshold flips it, either way: the row slides out and comes back
 * wearing the other state so the gesture is visibly answered. Vertical scrolling is preserved because
 * detectHorizontalDragGestures claims a drag only once it is clearly sideways past the touch slop.
 */
@Composable
private fun NotifRowItem(state: AggregatorState, r: NotifRow, launchPackage: String) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val unread = state.isUnread(r)
    val offset = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var width by remember { mutableStateOf(0) }
    val openApp: (() -> Unit)? = if (launchPackage.isBlank()) null else ({
        runCatching { ctx.packageManager.getLaunchIntentForPackage(launchPackage)?.let { ctx.startActivity(it) } }
        Unit
    })
    val swipe = if (r.id.isBlank()) Modifier else Modifier.pointerInput(r.id, state.showMode) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val far = abs(total) > max(64.dp.toPx(), width * 0.22f)
                scope.launch {
                    if (!far) { settle(offset, fade); return@launch }
                    // Slide out towards where it was thrown, persist the flipped state, then bring it back from the
                    // opposite edge already repainted. Under Show=Unread a row that just became read has nowhere to
                    // return TO, so it stays gone rather than reappearing in a list that excludes it.
                    val makeRead = state.isUnread(r)
                    val out = (if (total > 0) 1f else -1f) * width.coerceAtLeast(1)
                    offset.animateTo(out, tween(150)); fade.snapTo(0f)
                    state.markRead(r.id, makeRead)
                    if (state.showMode == "unread" && makeRead) return@launch
                    offset.snapTo(-out); fade.snapTo(1f)
                    offset.animateTo(0f, tween(170))
                }
            },
            onDragCancel = { scope.launch { settle(offset, fade) } },
            onHorizontalDrag = { change, dx ->
                change.consume()
                total += dx
                scope.launch {
                    offset.snapTo(total)
                    fade.snapTo(1f - (abs(total) / (width.coerceAtLeast(1) * 2f)).coerceIn(0f, 0.55f))
                }
            },
        )
    }
    val tap = if (openApp != null) Modifier.clickable { openApp() } else Modifier
    val titleColor = if (unread) p.textPrimary else p.textPrimary.copy(alpha = 0.73f)
    Column(
        Modifier.fillMaxWidth().onSizeChanged { width = it.width }
            .graphicsLayer { translationX = offset.value; alpha = fade.value }
            .then(swipe).then(tap).testTag(AggTags.row(r.id.ifBlank { r.title })),
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(when {
                r.severity == "error" -> p.bad.copy(alpha = 0.2f)
                r.severity == "warn" -> p.warn.copy(alpha = 0.2f)
                unread -> p.textPrimary.copy(alpha = 0.08f)
                else -> Color.Transparent
            }).padding(top = 7.dp, end = 10.dp, bottom = 7.dp),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(when {
                r.severity == "error" -> p.bad
                r.severity == "warn" -> p.warn
                unread -> p.accent
                else -> Color.Transparent
            }))
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, color = titleColor, fontSize = 13.sp,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(AggregatorModel.ago(System.currentTimeMillis() - r.ts), color = p.textSecondary, fontSize = 10.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
                if (r.text.isNotBlank()) Text(r.text, color = p.textPrimary.copy(alpha = if (unread) 0.67f else 0.47f),
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.textPrimary.copy(alpha = 0.07f)))
    }
}

private suspend fun settle(offset: Animatable<Float, AnimationVector1D>, fade: Animatable<Float, AnimationVector1D>) {
    fade.snapTo(1f)
    offset.animateTo(0f, tween(140))
}

// ── Archive ──────────────────────────────────────────────────────────

/**
 * The page's Archive section: one collapsed line over the boxes the user filed away, remembered per page like every
 * other choice on it. Hidden entirely while it holds nothing — a permanent "Archive 0" line on a page nobody has ever
 * archived from is chrome, and a control that opens an empty list teaches the user to stop reading that part.
 */
@Composable
internal fun ArchiveSection(state: AggregatorState, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val hostView = LocalView.current
    val archived by remember {
        derivedStateOf {
            state.panels.withIndex().flatMap { (i, panel) ->
                state.allViews(panel).filter { state.isArchived(it.group.key) }.map { i to it }
            }
        }
    }
    if (archived.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("Archive  ${if (state.archiveOpen) "▾" else "▸"}  ${archived.size}", color = p.textPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().alpha(0.55f)
                .clickable { Haptics.tap(hostView); state.toggleArchiveOpen() }
                .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 6.dp).testTag(AggTags.ARCHIVE_HEADER))
        if (state.archiveOpen) Column(Modifier.fillMaxWidth().testTag(AggTags.ARCHIVE_BODY)) {
            for ((i, v) in archived) androidx.compose.runtime.key("$i/${v.group.key}") {
                GroupBlock(state, v, archived = true, onOpen)
            }
        }
    }
}

/** The pull-down outcome line: ONE line, replaced by each refresh rather than appended (a note per pull would push the
 *  notifications the pull fetched off the top of the page). */
@Composable
internal fun RefreshNote(state: AggregatorState) {
    val (text, tone) = state.refreshNote ?: return
    Text(text, color = toneColor(LocalKitPalette.current, tone), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp).testTag(AggTags.REFRESH_NOTE))
}

// ── Inbox cards: the summary, then what actually arrived ─────────────

/** The value a kind shows when nothing has been asked on its behalf — never "0", which is a measurement. */
internal const val NO_SOURCE = "no source"

/**
 * Between a card's summary and its list: how the notifications on this page divide by KIND. The kinds are the CENTRAL
 * classification's own folders (ui.phone_folders), and a kind with no source says "no source", never "0": a confident
 * zero beside a kind that was never queried is indistinguishable from a quiet inbox.
 */
@Composable
private fun KindSummary(state: AggregatorState) {
    val ctx = LocalContext.current
    AggShadeLabel("BY KIND")
    if (PhoneTaxonomy.folders.isEmpty()) {
        AggStateLine("$NO_SOURCE · classification unavailable", Tone.UNKNOWN)
        AggCaption("ui.phone_folders did not reach this build, so there are no kinds to divide the feed into. " +
            "Nothing here is empty; it is unasked.")
        return
    }
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    val counted = remember(state.epoch, granted) {
        if (!granted) emptyMap<String, Int>() else {
            val feed = PhoneNotificationStore.all(ctx)
            PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
            feed.groupingBy { PhoneTaxonomy.folderIdOf(it.packageName, it.appLabel, ctx) }.eachCount()
        }
    }
    // The kinds this page is ABOUT stay listed even at zero; every other kind only when the feed carries it.
    val declared = state.panels.mapNotNull { pp -> AggregatorModel.inboxApp(pp)?.let { AggregatorModel.folderOfApp(ctx, it) } }
        .filter { it.isNotEmpty() }
    val ids = PhoneTaxonomy.folders.map { it.id }
        .filter { AggregatorModel.isSectionFolder(it) && (counted[it] != null || it in declared) }
    for (id in ids) {
        AggKindRow(AggregatorModel.kindLabel(id), if (granted) (counted[id] ?: 0).toString() else NO_SOURCE, granted)
    }
    // Notifications the classification could not place: the size of the gap in ui.phone_folders, reported rather than
    // folded into the counts above.
    val unplaced = counted.filterKeys { it.isNotEmpty() && !AggregatorModel.isSectionFolder(it) }.values.sum()
    if (unplaced > 0) AggKindRow("Unclassified", unplaced.toString(), true)
    if (!granted) {
        AggCaption("Notification Access is off, so no kind above has been queried. Those are not zeroes — grant " +
            "access and they become counts.")
    } else if (ids.isEmpty() && unplaced == 0) {
        AggStateLine("silent · the phone feed is empty", Tone.WARN)
    }
    AggCaption("Counted from the phone notification listener. The cloud stream is not counted here and cannot be: " +
        "ui.ntfy.taxon classifies a publisher by SECTION prefix, never by folder, so a cloud message has no kind to be " +
        "counted under. Notify ▸ Inboxes is where that stream is shown.")
}

/** An inbox card keeps its summary and draws its app's own notification boxes underneath: counts say how much is in
 *  there, the boxes say what arrived, and the two questions are asked in the same place. */
@Composable
internal fun InboxNotifications(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    KindSummary(state)
    val app = AggregatorModel.inboxApp(panel)
    if (app == null) {
        // Nothing is drawn, on purpose: a card whose inbox declares no app is a gap in build.json, and the person
        // holding the phone cannot close it. Only the inbox kinds are logged — `stats` is a generic dashboard card.
        if (panel.kind in AggregatorModel.INBOX_KINDS) LaunchedEffect(panel) {
            android.util.Log.w(TAG, "inbox panel '${panel.title}' (kind=${panel.kind}) declares no extapp: target, " +
                "so no notifications can be shown under it")
        }
        return
    }
    val folderId = remember(panel, state.panels) { AggregatorModel.inboxFolderId(ctx, panel, state.panels) }
    AggShadeLabel(if (folderId.isEmpty()) "NOTIFICATIONS" else "NOTIFICATIONS · ${AggregatorModel.kindLabel(folderId)}")
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    if (!granted) {
        // Drawing an empty list here would be a failure reporting success.
        NotificationAccessOff("Notification Access is off, so nothing this app posts is captured at all. This is " +
            "empty because we cannot read it, not because the inbox is quiet.")
        return
    }
    val empty = remember(state.epoch, folderId) {
        val feed = PhoneNotificationStore.all(ctx)
        PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
        AggregatorModel.inboxEntries(ctx, feed, app, folderId).isEmpty()
    }
    if (empty) {
        // Granted and empty is a real, different state: amber, naming the KIND when this card speaks for one.
        val subject = if (folderId.isEmpty()) app.label else AggregatorModel.kindLabel(folderId)
        AggStateLine("silent · nothing captured from $subject", Tone.WARN); return
    }
    // Grouped by package, not folded into one box: a hub and its forks are separate apps on the device. No pruneRead
    // here, on purpose — this is ONE app's slice of the phone namespace and pruning against a partial view is what makes
    // read rows silently reappear.
    val views by remember(panel) { derivedStateOf { state.inboxViews(panel) } }
    GroupList(state, views, onOpen)
}

// ── kind=class_inbox: one card per CHANNEL CLASS ─────────────────────

@Composable
internal fun ClassInboxBody(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val name = panelTitle(ctx, panel)
    val cls = InboxClasses.byId(panel.classId)
    if (cls == null) {
        // The panel names a class ui.inbox_classes does not declare: "we cannot tell", not "nothing arrived".
        LaunchedEffect(panel) {
            android.util.Log.w(TAG, "class_inbox panel '${panel.title}' names class '${panel.classId}', which " +
                "build.json::ui.inbox_classes does not declare")
        }
        AggStateLine(stringResource(R.string.inbox_class_undeclared), Tone.UNKNOWN); return
    }
    // The catch-all is what stops this page losing a notification, so its absence is reported on every card.
    if (InboxClasses.catchAll == null) AggCaption(stringResource(R.string.inbox_class_no_catch_all))
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    if (!granted) {
        NotificationAccessOff("Notification Access is off, so nothing this class carries is captured at all. This is " +
            "empty because we cannot read it, not because the inbox is quiet.")
        return
    }
    val groups by remember(panel) { derivedStateOf { state.classGroups(panel) } }
    ClassSummary(cls, name, groups, state.ntfyTopics(panel).size)
    val note = remember(cls) { stringByName(ctx, cls.noteRes) }
    if (note.isNotBlank()) AggCaption(note)
    AggShadeLabel(stringResource(R.string.inbox_notifications_header, name))
    // A class whose material is the ntfy CHANNEL stream reuses the panel renderer the C3 Ntfy card already had; a class
    // may declare both, and RSS does: the same messages arrive by two roads and belong on one card.
    if (cls.source == AggregatorModel.C3NTFY_SOURCE) NtfyGroups(state, panel, onOpen)
    if (groups.isNotEmpty()) {
        GroupList(state, state.classViews(panel), onOpen)
    } else if (cls.source != AggregatorModel.C3NTFY_SOURCE) {
        // Granted and empty is a real, different state from unavailable, and the line names WHICH class came up empty.
        AggStateLine(stringResource(R.string.inbox_class_silent, name), Tone.WARN)
    }
}

/** The counted summary above a class card: counts from the same feed the list below is taken from — how many apps or
 *  channels the class holds, how many notifications, and how long ago the newest arrived. No model call. */
@Composable
private fun ClassSummary(cls: InboxClasses.InboxClass, name: String, groups: List<NotifGroup>, channels: Int) {
    AggShadeLabel(stringResource(R.string.inbox_summary_header, name))
    val notifications = groups.sumOf { it.rows.size }
    val newest = groups.maxOfOrNull { it.newest } ?: 0L
    // A c3ntfy class counts CHANNELS as well as apps, and the two are different things: channels are ntfy topics reached
    // over the cloud, apps are packages on this phone.
    if (cls.source == AggregatorModel.C3NTFY_SOURCE) {
        AggKindRow(stringResource(R.string.inbox_summary_channels), channels.toString(), true)
    }
    // Suppressed at zero ONLY for a channel class, where "0 apps" is noise beside the channel count.
    if (cls.source != AggregatorModel.C3NTFY_SOURCE || groups.isNotEmpty()) {
        AggKindRow(stringResource(R.string.inbox_summary_apps), groups.size.toString(), true)
    }
    AggKindRow(stringResource(R.string.inbox_summary_notifications), notifications.toString(), true)
    AggKindRow(stringResource(R.string.inbox_summary_latest),
        if (newest > 0L) AggregatorModel.ago(System.currentTimeMillis() - newest)
        else stringResource(R.string.inbox_summary_latest_none), newest > 0L)
    AggCaption(stringResource(R.string.inbox_summary_counted))
    // The channel boxes fetch their own messages after the card is drawn, so a zero there would be a measurement nobody took.
    if (cls.source == AggregatorModel.C3NTFY_SOURCE) AggCaption(stringResource(R.string.inbox_summary_channels_note))
}

internal const val TAG_GRANT = "agg:grant-access"
