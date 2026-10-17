package com.diegonmarcos.superapp.launcher

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.boards.Boards
import com.diegonmarcos.superapp.boards.BoardsSource
import com.diegonmarcos.superapp.boards.InboxGroup
import com.diegonmarcos.superapp.boards.InboxItem
import com.diegonmarcos.superapp.boards.PlacesDay
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.StepsBoard
import com.diegonmarcos.superapp.boards.TaskProject
import com.diegonmarcos.superapp.boards.ThreadIntents
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitStatTile
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Boards panels (build.json::sections[boards]): kinds board_inbox, board_steps, board_places and
 * board_task_projects. Each reads its owner app through [Boards.source] off the main thread and draws the dense
 * ui-kit rows; a source that cannot answer draws its reason ([BoardUnavailable]) and never a placeholder number.
 */
internal object BoardTags {
    const val INBOX = "board:inbox"
    const val STEPS = "board:steps"
    const val PLACES = "board:places"
    const val PROJECTS = "board:projects"
    const val LOADING = "board:loading"
    fun unavailable(board: String) = "board:unavailable:$board"
    fun group(pkg: String) = "board:inbox:group:$pkg"
    fun project(id: String) = "board:project:$id"
}

/** One read per (board, refresh epoch), on IO. Null while loading. */
@Composable
private fun <T> rememberBoardRead(epoch: Int, key: String, read: (BoardsSource) -> T): T? {
    val ctx = LocalContext.current
    val v by produceState<T?>(null, epoch, key) {
        value = withContext(Dispatchers.IO) { read(Boards.source(ctx)) }
    }
    return v
}

@Composable
private fun BoardLoading() {
    AggCaption("Reading…", Modifier.testTag(BoardTags.LOADING))
}

@Composable
private fun BoardUnavailable(board: String, r: Read.Unavailable, fix: KitAction? = null) {
    val state = when (r.why) {
        Why.NO_PERMISSION, Why.NOT_INSTALLED, Why.OUTDATED -> KitState.WARN
        Why.FAILED -> KitState.BAD
    }
    KitStatusBanner(r.detail, state, Modifier.padding(vertical = KitDensity.small / 2), tag = BoardTags.unavailable(board), action = fix)
}

private fun installFix(onOpen: (String) -> Unit, why: Why, target: String, app: String): KitAction? = when (why) {
    Why.NOT_INSTALLED, Why.OUTDATED -> KitAction("Open $app", "fix:$target") { onOpen(target) }
    else -> null
}

// ── Inboxes ──────────────────────────────────────────────────────────

@Composable
internal fun BoardInboxBody(epoch: Int, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val reads = rememberBoardRead(epoch, "inbox") { s -> s.fleetMail() to s.phoneNotifications() }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.INBOX)) {
        if (reads == null) { BoardLoading(); return@Column }
        val (mail, phone) = reads
        if (mail is Read.Unavailable) BoardUnavailable("mail", mail, installFix(onOpen, mail.why, "extapp:cloud-mail", "Cloud Mail"))
        if (phone is Read.Unavailable) BoardUnavailable("phone", phone, if (phone.why == Why.NO_PERMISSION)
            KitAction("Grant Notification Access", "fix:notification_access") {
                runCatching {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } else null)
        val groups = Boards.group((mail as? Read.Ok)?.value.orEmpty(), (phone as? Read.Ok)?.value.orEmpty())
        if (groups.isEmpty()) {
            if (mail is Read.Ok && phone is Read.Ok) AggStateLine("Nothing unread or recent in any inbox", Tone.OK)
            return@Column
        }
        val now = System.currentTimeMillis()
        var lastFleet: Boolean? = null
        for (g in groups) {
            if (g.fleet != lastFleet) { AggShadeLabel(if (g.fleet) "FLEET" else "PHONE APPS"); lastFleet = g.fleet }
            InboxGroupCard(g, now, onOpen)
        }
    }
}

@Composable
private fun InboxGroupCard(g: InboxGroup, now: Long, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    var expanded by remember(g.pkg) { mutableStateOf(false) }
    // Through the launcher's one icon cache, off the main thread (each PackageManager icon is a Knox binder call).
    val icon by produceState<ImageBitmap?>(null, g.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppIconCache.load(ctx, g.pkg)?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()
        }
    }
    val shown = if (expanded) g.items else g.items.take(3)
    Column(Modifier.fillMaxWidth().padding(vertical = KitDensity.small / 2).clip(RoundedCornerShape(KitDensity.corner))
        .background(p.surface).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV)
        .testTag(BoardTags.group(g.pkg))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(icon, g.appLabel)
            Text(g.appLabel, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = KitDensity.small))
            Text(if (g.unread > 0) "${g.unread} unread" else "${g.items.size}", color = p.textSecondary, fontSize = KitDensity.caption)
        }
        for (item in shown) InboxRow(item, now, onOpen)
        if (g.items.size > 3) Text(if (expanded) "Show less" else AggregatorModel.moreLabel(g.items.size - 3),
            color = p.accent, fontSize = KitDensity.caption,
            modifier = Modifier.clickable { expanded = !expanded }.padding(top = KitDensity.small / 2))
    }
}

@Composable
private fun InboxRow(item: InboxItem, now: Long, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().clickable { openItem(item, onOpen) }.padding(vertical = KitDensity.small / 2),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(KitDensity.dot / 1.5f).clip(CircleShape)
            .background(if (item.unread) p.accent else p.surface))
        Column(Modifier.weight(1f).padding(start = KitDensity.small)) {
            Text(item.sender.ifBlank { item.appLabel }, color = p.textPrimary, fontSize = KitDensity.body,
                fontWeight = if (item.unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.preview.isNotBlank()) Text(item.preview, color = p.textSecondary, fontSize = KitDensity.caption,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(if (item.ts > 0) AggregatorModel.ago(now - item.ts) else "", color = p.textSecondary,
            fontSize = KitDensity.caption, modifier = Modifier.padding(start = KitDensity.small))
    }
}

/** The thread when the posting app left one, else its route (extapp:/app:). */
private fun openItem(item: InboxItem, onOpen: (String) -> Unit) {
    val pi = ThreadIntents.get(item.threadKey)
    if (pi != null && runCatching { pi.send() }.isSuccess) return
    onOpen(item.target)
}

@Composable
private fun AppGlyph(icon: ImageBitmap?, label: String) {
    val p = LocalKitPalette.current
    if (icon != null) Image(icon, contentDescription = label, modifier = Modifier.size(KitDensity.glyph * 0.8f).clip(RoundedCornerShape(KitDensity.small)))
    else Box(Modifier.size(KitDensity.glyph * 0.8f).clip(RoundedCornerShape(KitDensity.small)).background(p.surfaceSelected),
        contentAlignment = Alignment.Center) { Text(label.take(1).uppercase(), color = p.textPrimary, fontSize = KitDensity.body) }
}


// ── My-PM: My-health (steps) ────────────────────────────────────────

@Composable
internal fun BoardStepsBody(epoch: Int, onOpen: (String) -> Unit) {
    val r = rememberBoardRead(epoch, "steps") { it.steps(7) }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.STEPS)) {
        when (r) {
            null -> BoardLoading()
            is Read.Unavailable -> BoardUnavailable("steps", r, installFix(onOpen, r.why, "extapp:cloud-nav", "Cloud Nav"))
            is Read.Ok -> StepsContent(r.value, onOpen)
        }
    }
}

@Composable
private fun StepsContent(b: StepsBoard, onOpen: (String) -> Unit) {
    if (!b.ready) {
        // cloud-nav answered but cannot count (Health Connect permission, tracker off): its own words, then what it has.
        KitStatusBanner(b.statusLine.ifBlank { "Cloud Nav cannot read steps (${b.status})" }, KitState.WARN,
            tag = BoardTags.unavailable("steps-source"),
            action = KitAction("Open Cloud Nav", "fix:extapp:cloud-nav") { onOpen("extapp:cloud-nav") })
    }
    val week = b.days.takeLast(7)
    Row(Modifier.fillMaxWidth().padding(vertical = KitDensity.small / 2), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitStatTile("%,d".format(b.today), "Steps today", if (b.ready) KitState.OK else KitState.IDLE, Modifier.weight(1f), tag = "steps-today",
            onClick = { onOpen("extapp:cloud-nav") })
        KitStatTile("%,d".format(if (week.isEmpty()) 0 else week.sumOf { it.steps } / week.size), "7-day average",
            if (b.ready) KitState.OK else KitState.IDLE, Modifier.weight(1f), tag = "steps-avg")
    }
    if (week.isEmpty()) { AggCaption("No step history recorded yet"); return }
    StepsChart(week)
    AggCaption(when (b.source) {
        "HEALTH_CONNECT" -> "Source: Health Connect via Cloud Nav"
        else -> "Source: this phone's step counter via Cloud Nav's tracker"
    } + (b.hint?.let { " · $it" } ?: ""))
}

@Composable
private fun StepsChart(days: List<com.diegonmarcos.superapp.boards.StepsDay>) {
    val p = LocalKitPalette.current
    val max = (days.maxOfOrNull { it.steps } ?: 0L).coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(96.dp).padding(top = KitDensity.small).testTag("steps-chart"),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        for (d in days) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val frac = d.steps.toFloat() / max
                Box(Modifier.width(14.dp).height((64 * frac).coerceAtLeast(2f).dp)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(p.accent))
                Text(d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), color = p.textSecondary,
                    fontSize = KitDensity.caption, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ── My-PM: Nav tracker (places) ─────────────────────────────────────

@Composable
internal fun BoardPlacesBody(epoch: Int, onOpen: (String) -> Unit) {
    val r = rememberBoardRead(epoch, "places") { it.places(7) }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.PLACES)) {
        when (r) {
            null -> BoardLoading()
            is Read.Unavailable -> BoardUnavailable("places", r, installFix(onOpen, r.why, "extapp:cloud-nav", "Cloud Nav"))
            is Read.Ok -> {
                val days = r.value.filter { it.visits.isNotEmpty() }
                if (days.isEmpty()) {
                    AggStateLine("No places recorded in the last 7 days — Cloud Nav's tracker has no location fixes", Tone.UNKNOWN)
                } else days.forEach { PlacesDayCard(it, onOpen) }
            }
        }
    }
}

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
private fun PlacesDayCard(d: PlacesDay, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = KitDensity.small / 2).clip(RoundedCornerShape(KitDensity.corner))
        .background(p.surface).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV)
        .testTag("places-day:${d.date}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.date.format(DAY_FMT), color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (d.steps > 0) Text("%,d steps".format(d.steps), color = p.textSecondary, fontSize = KitDensity.caption)
        }
        Row(Modifier.fillMaxWidth().padding(top = KitDensity.small / 2)) {
            Column(Modifier.weight(1f)) {
                for (v in d.visits) KitListRow(v.label, secondary = v.part + if (v.predicted) " · estimated" else "",
                    tag = "place:${d.date}:${v.part}", onClick = { onOpen("extapp:cloud-nav") })
            }
            MiniMap(d, Modifier.padding(start = KitDensity.small).size(88.dp))
        }
    }
}

/** The day's places as a small equirectangular sketch: dots in visit order joined by a line. Not a tile map — it shows
 *  shape and spread with no network fetch. */
@Composable
private fun MiniMap(d: PlacesDay, modifier: Modifier) {
    val p = LocalKitPalette.current
    val pts = d.visits.filter { !it.lat.isNaN() && !it.lon.isNaN() }
    Canvas(modifier.clip(RoundedCornerShape(KitDensity.small)).background(p.surfaceSelected).testTag("places-map:${d.date}")) {
        if (pts.isEmpty()) return@Canvas
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val span = maxOf(maxLat - minLat, (maxLon - minLon) * Math.cos(Math.toRadians((minLat + maxLat) / 2)), 1e-4)
        val pad = 10f
        fun at(lat: Double, lon: Double): Offset {
            val x = ((lon - minLon) * Math.cos(Math.toRadians((minLat + maxLat) / 2)) / span).toFloat()
            val y = ((maxLat - lat) / span).toFloat()
            return Offset(pad + x * (size.width - 2 * pad), pad + y * (size.height - 2 * pad))
        }
        val path = Path()
        pts.forEachIndexed { i, v -> val o = at(v.lat, v.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, p.textSecondary, style = Stroke(width = 2f))
        pts.forEach { v -> drawCircle(p.accent, radius = 5f, center = at(v.lat, v.lon)) }
    }
}

// ── Tasks: cloud-tasks projects ─────────────────────────────────────

@Composable
internal fun BoardTaskProjectsBody(epoch: Int, onOpen: (String) -> Unit) {
    val r = rememberBoardRead(epoch, "projects") { it.taskProjects() }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.PROJECTS)) {
        when (r) {
            null -> BoardLoading()
            is Read.Unavailable -> BoardUnavailable("projects", r, installFix(onOpen, r.why, "extapp:cloud-tasks", "Cloud Tasks"))
            is Read.Ok -> if (r.value.isEmpty()) {
                AggStateLine("Cloud Tasks declares no projects yet", Tone.UNKNOWN)
            } else r.value.forEach { ProjectRow(it, onOpen) }
        }
    }
}

@Composable
private fun ProjectRow(t: TaskProject, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val next = t.nextDueName?.let { n -> "Next: $n" + (t.nextDue?.let { " · $it" } ?: "") } ?: "Nothing due"
    KitListRow(t.name, secondary = "${t.open} open · ${t.done} done · $next",
        leading = t.name.take(1).uppercase(), tag = BoardTags.project(t.id),
        onClick = { onOpen("extapp:cloud-tasks#project:${t.id}") },
        trailing = { Text("${t.open}", color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold) })
}
