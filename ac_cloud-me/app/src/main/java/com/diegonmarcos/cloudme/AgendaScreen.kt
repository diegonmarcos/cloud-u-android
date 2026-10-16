package com.diegonmarcos.cloudme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Which of the two Agenda tabs a page is: an event and a task are the same row with a different date field. */
enum class AgendaMode { EVENTS, TODOS }

/** What the Agenda tab has to say: a notice (why there is nothing to draw) or the rows themselves. */
private sealed interface AgendaState {
    object Loading : AgendaState
    class Notice(val title: String, val body: String) : AgendaState
    class Events(val events: JSONArray) : AgendaState
    class Todos(val todos: JSONArray) : AgendaState
}

/**
 * Agenda — the one section that reads live data with no other app in front of
 * it. Both tabs are this screen in two modes, because an event and a task
 * are the same row with a different date field and the two lists would
 * otherwise be one composable copied twice.
 *
 * Both come from the cal ENGINE (Cloud-Lib-Cal.apk, reached through
 * [CalEngineClient]; this app no longer compiles libs:cal): events from its
 * cache of the ICS subscriptions, tasks from its CalDAV VTODO mirror — the
 * same engine and the same data Cloud Tasks shows. The engine answers from
 * its cache, never the network, but the bind itself waits on the main thread,
 * so the rows are fetched on a worker and drawn when they arrive.
 *
 * Nothing on screen is invented: with no account connected both lists are
 * empty and say so, and an engine that is missing or too old for this build
 * is a line naming the Store, never an empty list.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a composable; the
 * engine is asked from a LaunchedEffect and the answer is state.
 */
@Composable
fun AgendaScreen(mode: AgendaMode, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val engine = remember { CalEngineClient(ctx) }
    var state by remember(mode) { mutableStateOf<AgendaState>(AgendaState.Loading) }
    // Bumped by the first-open fetch, which re-reads the cache it just filled.
    var reload by remember(mode) { mutableIntStateOf(0) }

    LaunchedEffect(mode, reload) {
        val loaded = withContext(Dispatchers.IO) { load(ctx, engine, mode) }
        state = loaded
        if (loaded is AgendaState.Events && loaded.events.length() == 0 && syncStarted.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) { runCatching { engine.sync() } }
            reload++
        }
    }

    Column(
        modifier.fillMaxSize().background(colorResource(R.color.me_bg)).verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
    ) {
        when (val s = state) {
            is AgendaState.Loading -> Unit
            is AgendaState.Notice -> EmptyState(s.title, s.body)
            is AgendaState.Events -> Events(s.events)
            is AgendaState.Todos -> Todos(s.todos)
        }
    }
}

// ── the engine ───────────────────────────────────────────────────

/** Ask the engine (on a worker), or say why there is nothing to draw. */
private fun load(ctx: Context, engine: CalEngineClient, mode: AgendaMode): AgendaState {
    val todos = mode == AgendaMode.TODOS
    val why = engine.check()
    val rows = if (why is CalEngineClient.Check.Ready) runCatching {
        if (todos) engine.todos()
        else System.currentTimeMillis().let { now -> engine.events(now, now + HORIZON_DAYS * 24L * 60L * 60L * 1000L) }
    } else null
    val result = rows?.getOrNull()
    return when {
        why is CalEngineClient.Check.NotInstalled -> AgendaState.Notice(
            ctx.getString(R.string.cal_engine_missing_title), ctx.getString(R.string.cal_engine_missing, why.pkg))
        why is CalEngineClient.Check.TooOld -> AgendaState.Notice(
            ctx.getString(R.string.cal_engine_old_title), ctx.getString(R.string.cal_engine_old, why.pkg, why.found, why.needed))
        result != null && todos -> AgendaState.Todos(result)
        result != null -> AgendaState.Events(result)
        else -> AgendaState.Notice(
            ctx.getString(R.string.cal_engine_failed_title), rows?.exceptionOrNull()?.message.orEmpty())
    }
}

// ── events ───────────────────────────────────────────────────────

@Composable
private fun Events(events: JSONArray) {
    if (events.length() == 0) {
        EmptyState("No events yet",
            "Agenda renders from the calendar engine's cache, never from the network, so the " +
            "tab opens instantly. On a fresh install that cache is empty until the engine has " +
            "fetched its subscriptions once — that first fetch is running now if this is the " +
            "first time you have opened the tab.")
        return
    }

    var lastDay = ""
    for (i in 0 until events.length()) {
        val e = events.optJSONObject(i) ?: continue
        val start = e.optLong("start")
        // One heading per day rather than a date on every row: a day with
        // four events reads as a block, which is what an agenda is for.
        val day = DAY_FMT.format(Date(start))
        if (day != lastDay) { Heading(day); lastDay = day }
        AgendaRow(
            title = e.optString("title").ifBlank { "(untitled)" },
            detail = e.optString("location"),
            trailing = if (e.optBoolean("allDay")) "all day" else TIME_FMT.format(Date(start)),
            accent = Color(0xFF7E57C2),
        )
    }
}

// ── todos ────────────────────────────────────────────────────────

@Composable
private fun Todos(todos: JSONArray) {
    // COMPLETED and CANCELLED are history; an open list that shows them is
    // a list you stop reading.
    val open = (0 until todos.length()).mapNotNull { todos.optJSONObject(it) }
        .filter { it.optString("status") != "COMPLETED" && it.optString("status") != "CANCELLED" }
        .sortedWith(compareBy({ due(it) ?: Long.MAX_VALUE }, { it.optString("summary") }))

    if (open.isEmpty()) {
        EmptyState("No open tasks",
            "Tasks are the CalDAV VTODOs the calendar engine mirrors locally. Buro paperwork and health " +
            "follow-ups both land here as long as they are filed in the same account — the " +
            "split the user asked for is a category on the task, not a second list.")
        return
    }

    val overdue = open.filter { (due(it) ?: Long.MAX_VALUE) < System.currentTimeMillis() }
    if (overdue.isNotEmpty()) {
        Heading("Overdue")
        overdue.forEach { TodoRow(it, Color(0xFFEF5350)) }
    }
    val rest = open - overdue.toSet()
    if (rest.isNotEmpty()) {
        Heading("Open")
        rest.forEach { TodoRow(it, Color(0xFF26A69A)) }
    }
}

/** The engine's task row (CalEngine.todoJson): `due` is epoch millis as a string, "" when unset. */
private fun due(t: JSONObject): Long? = t.optString("due").toLongOrNull()

@Composable
private fun TodoRow(t: JSONObject, accent: Color) = AgendaRow(
    title = t.optString("summary").ifBlank { "(untitled)" },
    detail = t.optString("description").lineSequence().firstOrNull().orEmpty(),
    trailing = due(t)?.let { DAY_FMT.format(Date(it)) } ?: "",
    accent = accent,
    progress = t.optInt("percentComplete", -1).takeIf { it >= 0 },
)

// ── composables ──────────────────────────────────────────────────

@Composable
private fun AgendaRow(title: String, detail: String, trailing: String, accent: Color, progress: Int? = null) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(colorResource(R.color.me_surface))
            .border(1.dp, colorResource(R.color.me_outline), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 10.dp).size(10.dp).clip(CircleShape).background(accent))
            Text(title, color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (trailing.isNotBlank()) Text(trailing, color = colorResource(R.color.me_text_dim), fontSize = 12.sp)
        }
        if (detail.isNotBlank()) {
            Text(detail, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        }
        if (progress != null && progress > 0) {
            Text("$progress% done", color = accent, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text, color = colorResource(R.color.me_text), fontSize = 16.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
)

@Composable
private fun EmptyState(title: String, body: String) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(colorResource(R.color.me_surface))
            .border(1.dp, colorResource(R.color.me_outline), shape).padding(14.dp),
    ) {
        Text(title, color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(body, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

private const val HORIZON_DAYS = 30

/** Process-wide: the cache is shared by both tabs and by every instance, so the first-open
 *  fetch belongs to the process, not to a composition that is recreated on every rotation.
 *  Guarded rather than checked-then-set: two tab switches in quick succession would
 *  otherwise both see an empty cache and start their own fetch of the same feeds. */
private val syncStarted = AtomicBoolean(false)

private val DAY_FMT = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
