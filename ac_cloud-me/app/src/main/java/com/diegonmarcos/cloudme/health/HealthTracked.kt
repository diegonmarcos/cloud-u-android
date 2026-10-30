package com.diegonmarcos.cloudme.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.health.HealthConnectGateway
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val histDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val histHour = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/**
 * Health > Tracked: today's cards like Google Fit and Samsung Health (steps, active minutes, calories, distance, heart
 * rate, sleep, weight, water; data/health/health_tracked.json). A card opens its detail: Day / Week / Month chart,
 * history and a manual entry written to Health Connect. Every number is Health Connect's; nothing is estimated.
 */
@Composable
fun TrackedPage(@Suppress("UNUSED_PARAMETER") e: HealthEnv) {
    val ctx = LocalContext.current
    val metrics = remember { runCatching { Tracked.load(ctx) }.getOrDefault(emptyList()) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val selected = metrics.firstOrNull { it.id == open }
    if (selected != null) { TrackedDetail(selected, tick, { tick++ }) { open = null }; return }
    HTitle("Tracked", "Today, from Health Connect. Tap a card for its history or to add an entry.")
    if (metrics.isEmpty()) { HEmpty("health_tracked.json is missing from this build."); return }
    HcPanel(TrackedHc.readPerms(metrics.map { it.id }), { c -> metrics.associate { m -> m.id to runCatching { TrackedHc.today(c, m.id, zone) }.getOrNull() } }) { today ->
        metrics.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { m -> Box(Modifier.weight(1f).padding(end = 6.dp)) { TrackedCard(m, today[m.id]) { open = m.id } } }
                if (pair.size == 1) Box(Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun TrackedCard(m: TrackedMetric, v: Double?, onClick: () -> Unit) {
    HCard(Modifier.testTag("me:tracked:card:${m.id}"), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tracked.progress(v, m.goal)?.let { Ring(it, accent(), dim()); Box(Modifier.size(10.dp)) }
            Column {
                HText(m.label)
                HText("${Tracked.format(m, v)} ${m.unit}", ink(), 18)
                if (m.goal > 0) HText("of ${Tracked.format(m, m.goal)}")
            }
        }
    }
}

@Composable
private fun Ring(p: Float, on: Color, off: Color) {
    Canvas(Modifier.size(44.dp)) {
        val w = 5.dp.toPx(); val inset = w / 2
        val box = Size(size.width - w, size.height - w)
        drawArc(off.copy(alpha = 0.3f), -90f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
        drawArc(on, -90f, 360f * p, false, Offset(inset, inset), box, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun TrackedDetail(m: TrackedMetric, tick: Int, changed: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var range by rememberSaveable { mutableStateOf(TrackedRange.WEEK) }
    var entry by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(HealthConnectGateway.requestPermissionsContract()) { changed() }
    Pill("< Tracked") { back() }
    HTitle(m.label, "From Health Connect, merged across every app that writes it.")
    Choice(TrackedRange.entries.map { it.name to it.label }, range.name) { range = TrackedRange.valueOf(it) }
    key2(tick, range) {
        HcPanel(TrackedHc.readPerms(listOf(m.id)), { c -> TrackedHc.series(c, m.id, range, zone) }) { buckets ->
            HCard(Modifier.testTag("me:tracked:chart:${m.id}")) {
                HHead("${range.label}: ${if (m.avg) "average" else "total"} ${Tracked.format(m, Tracked.summary(m, buckets))} ${m.unit}")
                BarChart(buckets.map { it.value ?: 0.0 }, accent())
                Row(Modifier.fillMaxWidth()) { HText(buckets.first().label, modifier = Modifier.weight(1f)); HText(buckets.last().label) }
            }
            HCard(Modifier.testTag("me:tracked:history:${m.id}")) {
                HHead("History")
                val hist = Tracked.history(buckets)
                if (hist.isEmpty()) HText("Health Connect holds nothing for this ${range.label.lowercase()}.")
                hist.forEach { b -> HRow(if (range == TrackedRange.DAY) b.start.format(histHour) else b.start.format(histDay), "${Tracked.format(m, b.value)} ${m.unit}") }
            }
        }
    }
    HCard(Modifier.testTag("me:tracked:add:${m.id}")) {
        HHead("Add an entry")
        NumField("${m.entryLabel} (${m.entryUnit})", entry, { entry = it })
        Pill("Save to Health Connect", Tracked.validEntry(m, entry.toDoubleOrNull())) {
            val v = entry.toDoubleOrNull()
            if (!Tracked.validEntry(m, v)) { note = "Enter a positive ${m.entryUnit} value."; return@Pill }
            scope.launch {
                val perm = TrackedHc.writePerm(m.id)
                val c = HealthConnectGateway.client(ctx)
                when {
                    c == null -> note = "Health Connect is not available on this phone."
                    perm !in HealthConnectGateway.grantedPermissions(ctx) -> { note = "Allow Cloud Me to write ${m.label.lowercase()} in Health Connect, then save again."; ask.launch(setOf(perm)) }
                    else -> runCatching { TrackedHc.add(c, m, v!!, zone) }
                        .onSuccess { note = "Saved ${Tracked.format(m, v)} ${m.entryUnit}."; entry = ""; changed() }
                        .onFailure { note = "Health Connect refused the entry: ${it.javaClass.simpleName}" }
                }
            }
        }
        if (note.isNotBlank()) HText(note)
    }
}

@Composable
private fun key2(a: Any, b: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(a, b) { content() }
