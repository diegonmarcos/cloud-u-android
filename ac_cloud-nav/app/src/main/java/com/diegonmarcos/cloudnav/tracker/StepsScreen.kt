package com.diegonmarcos.cloudnav.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatTile
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object StepsTags {
    const val SOURCE = "steps:source"
    const val GRANT = "steps:grant"
    const val INSTALL = "steps:install"
    const val CHART = "steps:chart"
    const val PERIOD = "steps"
    fun total(which: String) = "steps:total:$which"
    fun day(d: java.time.LocalDate) = "steps:day:$d"
    fun place(d: java.time.LocalDate, part: DayPart) = "steps:place:$d:${part.name.lowercase()}"
    fun seg(d: java.time.LocalDate, i: Int) = "steps:seg:$d:$i"
    fun bar(i: Int) = "steps:bar:$i"
}

object StepsFormat {
    fun n(v: Long) = "%,d".format(Locale.US, v)
    fun duration(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
    fun distance(m: Double) = if (m >= 1000) "%.1f km".format(Locale.US, m / 1000) else "${m.toInt()} m"
    fun time(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))
    fun segment(s: Segment, zone: ZoneId): String {
        val span = "${time(s.startMs, zone)}–${time(s.endMs, zone)} · ${duration(s.durationMs)}"
        return if (s.kind == SegKind.STOP) "Stop  $span" else "Moving  $span · ${n(s.steps)} steps · ${distance(s.distanceM)}"
    }
    fun place(v: PlaceView?): String = when {
        v == null -> "no fix"
        v.predicted -> "≈ ${v.label} (predicted, ±${v.radiusM} m)"
        else -> v.label
    }
    fun bucketLabel(b: Bucket, p: Period): String = when (p) {
        Period.DAY -> b.start.dayOfMonth.toString()
        Period.WEEK -> "W" + b.start.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        Period.MONTH -> b.start.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}

@Composable
fun StepsScreen(
    ui: StepsUi, period: Period, onPeriod: (Period) -> Unit,
    onGrant: () -> Unit = {}, onInstall: () -> Unit = {}, zone: ZoneId = ZoneId.systemDefault(),
) {
    val p = LocalKitPalette.current
    LazyColumn(Modifier.fillMaxSize().background(p.surface).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Box(Modifier.height(4.dp)) }
        item {
            KitSectionHeader("Steps", "Where you stood and where you walked.")
            SourceBanner(ui, onGrant, onInstall)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitStatTile(StepsFormat.n(ui.totals.day), "Today", KitState.OK, Modifier.weight(1f), tag = StepsTags.total("day"))
                KitStatTile(StepsFormat.n(ui.totals.week), "This week", KitState.OK, Modifier.weight(1f), tag = StepsTags.total("week"))
                KitStatTile(StepsFormat.n(ui.totals.month), "This month", KitState.OK, Modifier.weight(1f), tag = StepsTags.total("month"))
            }
        }
        item {
            KitSegmented(listOf("DAY" to "Per day", "WEEK" to "Per week", "MONTH" to "Per month"), period.name, { onPeriod(Period.valueOf(it)) }, tag = StepsTags.PERIOD)
        }
        item { Chart(ui, period) }
        item { KitSectionHeader("Days", "", eyebrow = true) }
        if (ui.days.isEmpty() && !ui.loading) item { Text("No steps recorded yet.", color = p.textSecondary) }
        items(ui.days, key = { it.date.toString() }) { DayCardView(it, zone) }
        item { Box(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SourceBanner(ui: StepsUi, onGrant: () -> Unit, onInstall: () -> Unit) {
    val (text, state) = when (ui.status) {
        SourceStatus.READY -> "Source: Health Connect" to KitState.OK
        SourceStatus.NEEDS_PERMISSION -> "Health Connect is installed but steps are not allowed yet. Showing this phone's own step counter (on-device fallback)." to KitState.WARN
        SourceStatus.HEALTH_CONNECT_MISSING -> "Health Connect is not available. Showing this phone's own step counter (on-device fallback)." to KitState.WARN
        SourceStatus.DEVICE_FALLBACK -> "Source: this phone's own step counter (on-device fallback)" to KitState.WARN
    }
    KitStatusBanner(text, state, tag = StepsTags.SOURCE)
    if (ui.status == SourceStatus.NEEDS_PERMISSION) OutlinedButton(onClick = onGrant, modifier = Modifier.testTag(StepsTags.GRANT)) { Text("Allow reading steps") }
    if (ui.status == SourceStatus.HEALTH_CONNECT_MISSING) OutlinedButton(onClick = onInstall, modifier = Modifier.testTag(StepsTags.INSTALL)) { Text("Get Health Connect") }
}

@Composable
private fun Chart(ui: StepsUi, period: Period) {
    val p = LocalKitPalette.current
    val buckets = StepStats.buckets(ui.daily, period, ui.today, if (period == Period.DAY) 14 else 12)
    val max = (buckets.maxOfOrNull { it.steps } ?: 0L).coerceAtLeast(1L)
    KitCard(Modifier.testTag(StepsTags.CHART)) {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            buckets.forEachIndexed { i, b ->
                Box(Modifier.weight(1f).fillMaxHeight().testTag(StepsTags.bar(i)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((b.steps.toFloat() / max).coerceIn(0.02f, 1f)).clip(MaterialTheme.shapes.extraSmall)
                        .background(if (i == buckets.lastIndex) p.accent else p.textSecondary))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in buckets) Text(StepsFormat.bucketLabel(b, period), Modifier.weight(1f), color = p.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Text("Peak ${StepsFormat.n(max)} steps per ${period.name.lowercase()}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DayCardView(d: DayCard, zone: ZoneId) {
    val p = LocalKitPalette.current
    KitCard(Modifier.testTag(StepsTags.day(d.date))) {
        Row(Modifier.fillMaxWidth()) {
            Text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.date, Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(StepsFormat.n(d.steps) + " steps", color = p.accent, style = MaterialTheme.typography.titleMedium)
        }
        for (part in DayPart.values()) {
            Row {
                Text(part.label, Modifier.padding(end = 12.dp).weight(0.28f), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text(StepsFormat.place(d.places[part]), Modifier.weight(0.72f).testTag(StepsTags.place(d.date, part)), color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (d.segments.isNotEmpty()) Text("Events", color = p.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        d.segments.forEachIndexed { i, s ->
            Text(StepsFormat.segment(s, zone), Modifier.testTag(StepsTags.seg(d.date, i)),
                color = if (s.kind == SegKind.MOVING) p.accent else p.textPrimary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
