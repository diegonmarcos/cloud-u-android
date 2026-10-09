@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.superapp.adbdebug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val C_OK = Color(0xFF2F855A)
private val C_WARN = Color(0xFFB7791F)
private val C_BAD = Color(0xFFC53030)
private val C_UNKNOWN = Color(0xFF718096)
private val C_ACCENT = Color(0xFF7C3AED)

private fun Dot.color(): Color = when (this) {
    Dot.OK -> C_OK
    Dot.WARN -> C_WARN
    Dot.BAD -> C_BAD
    Dot.UNKNOWN -> C_UNKNOWN
}

private fun ConnState.dot(): Dot = when (this) {
    ConnState.UP -> Dot.OK
    ConnState.DEGRADED, ConnState.CONNECTING -> Dot.WARN
    ConnState.DOWN -> Dot.BAD
}

private fun ConnState.title(): String = when (this) {
    ConnState.UP -> "Up"
    ConnState.DEGRADED -> "Degraded"
    ConnState.CONNECTING -> "Connecting"
    ConnState.DOWN -> "Down"
}

private fun stamp(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

private fun glyph(s: StepState): String = when (s) {
    StepState.PENDING -> "·"
    StepState.RUNNING -> "▶"
    StepState.DONE -> "✓"
    StepState.SKIPPED -> "–"
    StepState.WAITING -> "…"
    StepState.FAILED -> "✕"
}

private fun glyphColor(s: StepState): Color = when (s) {
    StepState.DONE -> C_OK
    StepState.FAILED -> C_BAD
    StepState.WAITING, StepState.RUNNING -> C_WARN
    else -> C_UNKNOWN
}

@Composable
private fun StateDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val fg = LocalContentColor.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.15f)))
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C_ACCENT)
        content()
    }
}

@Composable
private fun Dim(text: String, modifier: Modifier = Modifier, size: Int = 10) {
    Text(text, modifier, fontSize = size.sp, color = LocalContentColor.current.copy(alpha = 0.65f))
}

/**
 * THE ADB Shell page: one dense control for the privileged channel, the same composable in every app
 * that links this module (the SuperApp's Configs > Network > ADB Shell, Cloud Store's Settings, Cloud
 * Account's Setup, and the full-screen [AdbShellActivity] every status chip opens). Sections, in order:
 * status table, connect, active connection, logs, declared needs, setup checklist. Every rule behind
 * it ([ChannelState], [ConnectPlan], [ChannelActions], [ChannelLog], [DeclaredNeeds], [SetupChecklist])
 * is pure and tested; this file only draws them. No minimum heights anywhere.
 *
 * It does not scroll by itself: the host does (a nested vertical scroll cannot be measured).
 */
@Composable
fun AdbShellPage(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = ChannelLog.shared

    var mode by remember { mutableStateOf(ChannelModePrefs.current(ctx)) }
    var via by remember { mutableStateOf(LaunchViaPrefs.current(ctx)) }
    var raw by remember { mutableStateOf<ChannelFacts?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<StepRun>>(emptyList()) }
    var outcome by remember { mutableStateOf<ConnectOutcome?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var testOut by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var logTick by remember { mutableStateOf(0) }
    val lastProbe = remember { arrayOf("") }

    DisposableEffect(Unit) {
        val l: () -> Unit = { logTick++ }
        log.addListener(l)
        onDispose { log.removeListener(l) }
    }
    LaunchedEffect(tick, mode) {
        val f = withContext(Dispatchers.IO) { runCatching { ChannelReader.read(ctx) }.getOrNull() }
        if (f != null) {
            raw = f
            val s = "route=${ChannelState.route(f.copy(mode = mode)) ?: "none"} state=${ChannelState.connState(f.copy(mode = mode))} " +
                "adb=${f.adbConnected} server=${f.serverRunning} shizuku=${f.shizuku}"
            if (s != lastProbe[0]) { lastProbe[0] = s; log.add("probe", s) }
        } else log.add("probe", "could not read the layers")
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(6_000)
            if (!connecting && busy == null) tick++
        }
    }

    val facts = raw?.copy(mode = mode, connecting = connecting)
    val conn = facts?.let { ChannelState.connState(it) }

    fun connect() {
        if (connecting) return
        connecting = true; outcome = null; steps = emptyList(); note = ""
        scope.launch(Dispatchers.IO) {
            val out = ConnectPlan.run(mode, via, DeviceConnectExecutor(ctx), log, ChannelModePrefs.ownsServer()) { steps = it }
            outcome = out
            connecting = false
            tick++
        }
    }

    fun runChannelAction(action: ChannelAction, work: (Context) -> String) {
        if (busy != null || connecting) return
        busy = action.label
        scope.launch(Dispatchers.IO) {
            val r = runCatching { work(ctx) }.getOrElse { log.add("action", "${action.label} failed: ${it.message}"); it.message ?: "failed" }
            if (action == ChannelAction.TEST) { testOut = r; note = "" } else note = r
            busy = null
            tick++
        }
    }

    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── header ───────────────────────────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ADB Shell", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            StateDot((conn?.dot() ?: Dot.UNKNOWN).color())
            Text(conn?.title() ?: "Reading", fontSize = 12.sp, modifier = Modifier.weight(1f))
            DenseButton("Refresh", onClick = { tick++ })
        }

        // ── 1  status table ──────────────────────────────────────────────────────────────────────
        Section("1  STATUS") {
            val rows = facts?.let { ChannelState.rows(it) }
            if (rows == null) Dim("Reading the layers...", size = 11)
            else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(14.dp))
                    Dim("Layer", Modifier.width(98.dp), 9)
                    Dim("State", Modifier.weight(1f), 9)
                    Dim("Probed", Modifier.width(48.dp), 9)
                }
                for (r in rows) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    StateDot(r.dot.color(), Modifier.padding(top = 4.dp, end = 6.dp))
                    Text(r.label, Modifier.width(98.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(r.value, Modifier.weight(1f), fontSize = 11.sp)
                    Text(stamp(r.probedAt), Modifier.width(48.dp), fontSize = 9.sp, textAlign = TextAlign.End,
                        color = LocalContentColor.current.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── 2  connect ───────────────────────────────────────────────────────────────────────────
        Section("2  CONNECT") {
            ChannelModeBar { m -> mode = m; log.add("mode", "mode set to ${m.title()}"); tick++ }
            if (mode == ChannelMode.LOCAL_SERVER) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dim("Launch the server via", size = 11)
                for (v in LaunchVia.values()) DenseButton(v.title, primary = v == via, onClick = {
                    via = v; LaunchViaPrefs.set(ctx, v); log.add("mode", "server launcher set to ${v.title}"); tick++
                })
            }
            val owns = ChannelModePrefs.ownsServer()
            for ((i, m) in ConnectPlan.methods(mode, via, owns).withIndex())
                Text("${i + 1}. ${m.title} - ${m.detail}", fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DenseButton(if (connecting) "Connecting..." else "Connect", primary = true, enabled = !connecting && busy == null, onClick = { connect() })
                Dim("runs: " + ConnectPlan.steps(mode, via, owns).joinToString(" > ") { it.label }, Modifier.weight(1f))
            }
            for (s in steps) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(glyph(s.state), Modifier.width(12.dp), fontSize = 11.sp, color = glyphColor(s.state), fontFamily = FontFamily.Monospace)
                Text(s.id.label, Modifier.weight(1f), fontSize = 11.sp)
                if (s.detail.isNotBlank()) Dim(s.detail.take(60), Modifier.weight(1f))
            }
            outcome?.let { o ->
                when (o.status) {
                    ConnectStatus.SUCCESS -> Text("Connected: every step passed.", fontSize = 11.sp, color = C_OK)
                    ConnectStatus.WAITING_FOR_USER -> Text(o.explanation.orEmpty(), fontSize = 11.sp, color = C_WARN)
                    ConnectStatus.FAILED -> Text(o.explanation.orEmpty(), fontSize = 11.sp, color = C_BAD)
                }
            }
        }

        // ── 3  active connection ─────────────────────────────────────────────────────────────────
        Section("3  ACTIVE CONNECTION") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StateDot((conn?.dot() ?: Dot.UNKNOWN).color())
                Text((conn?.title() ?: "Reading") + (facts?.let { f -> ChannelState.route(f)?.let { " via $it" } } ?: ""), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            val enab = facts?.let { ChannelActions.enablement(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (a in ChannelAction.values()) {
                    val on = enab?.get(a)?.enabled == true && busy == null && !connecting
                    DenseButton(a.label, enabled = on, onClick = {
                        when (a) {
                            ChannelAction.DISCONNECT -> runChannelAction(a) { ChannelOps.disconnect(it) }
                            ChannelAction.RECONNECT -> runChannelAction(a) { ChannelOps.reconnect(it) }
                            ChannelAction.RESTART_SERVER -> runChannelAction(a) { ChannelOps.startServer(it, restart = true) }
                            ChannelAction.STOP_SERVER -> runChannelAction(a) { ChannelOps.stopServer(it) }
                            ChannelAction.TEST -> runChannelAction(a) { ChannelOps.test(it) }
                        }
                    })
                }
            }
            val why = enab?.entries?.filter { !it.value.enabled }?.joinToString("  ·  ") { "${it.key.label}: ${it.value.why}" }
            if (!why.isNullOrBlank()) Dim(why)
            busy?.let { Dim("$it...", size = 11) }
            if (note.isNotBlank()) Text(note, fontSize = 11.sp)
            if (testOut.isNotBlank()) SelectionContainer { Text(testOut, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        }

        // ── 4  logs ──────────────────────────────────────────────────────────────────────────────
        Section("4  LOGS") {
            val entries = remember(logTick) { log.entries() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DenseButton("Copy", enabled = entries.isNotEmpty(), onClick = {
                    runCatching {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("ADB Shell log", log.asText()))
                    }
                })
                DenseButton("Clear", enabled = entries.isNotEmpty(), onClick = { log.clear() })
                Dim("${entries.size} events in memory; tokens and codes are never kept.", Modifier.weight(1f))
            }
            val scroll = rememberScrollState()
            LaunchedEffect(logTick) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.fillMaxWidth().height(150.dp).border(1.dp, LocalContentColor.current.copy(alpha = 0.2f)).padding(4.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    if (entries.isEmpty()) Dim("No events yet.")
                    for (e in entries) Text("${stamp(e.at)} [${e.tag}] ${e.text}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── 5  declared needs ────────────────────────────────────────────────────────────────────
        Section("5  DECLARED NEEDS") {
            val label = remember { runCatching { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }.getOrDefault(ctx.packageName) }
            val needs = remember { DeclaredNeeds.current() }
            Text(label + " · " + if (ChannelModePrefs.ownsServer()) "own local server 127.0.0.1:${AdbShellBootstrap.port()} (${AdbShellBootstrap.niceName()})"
                else "no local server of its own", fontSize = 11.sp, fontWeight = FontWeight.Medium)
            if (needs.isEmpty()) Dim("This app declares no needs in build.json::privileged_channel.")
            for (n in needs) Column {
                Text("• ${n.label} - ${n.why}", fontSize = 11.sp)
                if (n.commands.isNotEmpty()) Dim(n.commands.joinToString(", "), Modifier.padding(start = 10.dp))
            }
        }

        // ── 6  setup ─────────────────────────────────────────────────────────────────────────────
        Section("6  SETUP") {
            val items = facts?.let { SetupChecklist.items(it, via) }
            if (items == null) Dim("Reading the device...", size = 11)
            else {
                Dim(SetupChecklist.summary(items) + " for ${mode.title()} mode", size = 11)
                for (item in items) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (item.status) { SetupStatus.OK -> "✓"; SetupStatus.TODO -> "✕"; SetupStatus.UNKNOWN -> "?" }, Modifier.width(12.dp), fontSize = 12.sp,
                        color = when (item.status) { SetupStatus.OK -> C_OK; SetupStatus.TODO -> C_BAD; SetupStatus.UNKNOWN -> C_UNKNOWN })
                    Column(Modifier.weight(1f)) {
                        Text(item.label, fontSize = 11.sp)
                        Dim(item.detail)
                    }
                    item.action?.let { a ->
                        DenseButton(item.actionLabel, onClick = {
                            scope.launch(Dispatchers.IO) { note = ChannelOps.setup(ctx, a) }
                        })
                    }
                }
            }
        }
    }
}

/**
 * The page as a whole screen: themed, on a surface, scrolling. [dark] null follows the system. Used by
 * [AdbShellActivity] and by hosts that have no scroll or theme of their own.
 */
@Composable
fun AdbShellScreen(dark: Boolean? = null) {
    val isDark = dark ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { AdbShellPage() }
        }
    }
}

/**
 * The compact status chip for Compose hosts: a dot and the one-line state, tapping opens the page.
 * Every place that used to carry its own channel controls shows this instead (View hosts have the
 * same text from [ChannelState.chip] on their own button).
 */
@Composable
fun AdbShellChip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var facts by remember { mutableStateOf<ChannelFacts?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            facts = withContext(Dispatchers.IO) { runCatching { ChannelReader.read(ctx) }.getOrNull() }
            delay(10_000)
        }
    }
    val conn = facts?.let { ChannelState.connState(it) }
    Row(modifier.clickable { AdbShellLink.open(ctx) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateDot((conn?.dot() ?: Dot.UNKNOWN).color())
        Text(ChannelState.chip(facts) + "  ›", fontSize = 11.sp)
    }
}
