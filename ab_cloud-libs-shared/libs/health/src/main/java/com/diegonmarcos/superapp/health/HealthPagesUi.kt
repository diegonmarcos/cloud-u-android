package com.diegonmarcos.superapp.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.diegonmarcos.superapp.core.HealthStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/**
 * MyHealth — Compose surface.
 *
 * Single Compose entry [HealthScreen]: ONE page's body, chosen by the
 * page id the host hands in. It draws NO tab strip of its own.
 *
 * It used to. This module was written when MyHealth was a standalone
 * SuperApp section that owned its whole screen, so it carried a sticky
 * Summary | Timeline | Configs strip and treated the incoming page id as
 * a mere starting tab. Its only host today is Cloud-Me, where Health is
 * a container tab whose children ARE those three pages and whose strip
 * the shell already draws from build.json — so the surface came up with
 * two stacked tab rows navigating the same three destinations, one of
 * which the shell could not keep in sync. The host owns navigation; this
 * module owns a body. That is also what lets the shell add a fourth
 * child (Projects > Health > Gym) that this module knows nothing about.
 *
 *   ┌──────────────────────────────────────────┐
 *   │  Activity (10)                           │
 *   │    Steps        ·  7d 8420  · 30d 7890   │
 *   │    Distance     ·  7d 6.4km · 30d 5.9km  │
 *   │    …                                     │
 *   │  Vitals (9)                              │
 *   │    HR avg       ·  7d 72bpm · 30d 71bpm  │
 *   │    …                                     │
 *   └──────────────────────────────────────────┘
 */
@Composable
fun HealthScreen(
    pageId: String,
    metricId: String = "",
    recordNames: List<String> = emptyList(),
) {
    when (pageId) {
        HealthFragment.PAGE_TIMELINE -> TimelineBody()
        HealthFragment.PAGE_CONFIGS  -> ConfigsBody()
        HealthFragment.PAGE_METRIC   -> MetricBody(metricId, recordNames)
        // An unknown id is the host's page-to-body mapping drifting, not a
        // reason to draw nothing: Summary is the page every entry point
        // means when it does not say otherwise.
        else                         -> SummaryBody()
    }
}

// ── Summary tab ─────────────────────────────────────────────────────
// Flat scroll: every metric → every record, each row shows Avg 7d
// and Avg 30d side-by-side. No drill-down — everything visible at once.
@Composable
private fun SummaryBody() {
    val ctx = LocalContext.current
    val metrics = remember { HealthMetrics.all }
    var rows7d  by remember { mutableStateOf<Map<String, List<HealthConnectGateway.WindowRow>>>(emptyMap()) }
    var rows30d by remember { mutableStateOf<Map<String, List<HealthConnectGateway.WindowRow>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        val r7 = mutableMapOf<String, List<HealthConnectGateway.WindowRow>>()
        val r30 = mutableMapOf<String, List<HealthConnectGateway.WindowRow>>()
        metrics.forEach { m ->
            r7[m.id]  = HealthConnectGateway.readMetricWindow(ctx, m, 7)
            r30[m.id] = HealthConnectGateway.readMetricWindow(ctx, m, 30)
        }
        rows7d = r7
        rows30d = r30
        loading = false
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            item { InfoCard("Loading…", "Reading the last 30 days from Health Connect…") }
        }
        metrics.forEach { m ->
            item(key = "h-${m.id}") {
                SectionHeader("${m.label} · ${m.records.size}")
            }
            val seven  = rows7d[m.id].orEmpty()
            val thirty = rows30d[m.id].orEmpty()
            val merged: List<Triple<String, String, String>> = (0 until maxOf(seven.size, thirty.size)).map { i ->
                val s = seven.getOrNull(i)
                val t = thirty.getOrNull(i)
                val label = s?.label ?: t?.label ?: "—"
                Triple(label, s?.value ?: "—", t?.value ?: "—")
            }
            if (merged.isEmpty() && !loading) {
                item(key = "e-${m.id}") {
                    InfoCard("${m.label} — no data", "No records found for any of this metric's types.")
                }
            } else {
                items(merged, key = { "r-${m.id}-${it.first}" }) { (lbl, v7, v30) ->
                    DualValueRow(label = lbl, value7d = v7, value30d = v30)
                }
            }
        }
    }
}

// ── One metric, on a page of its own ────────────────────────────────
// The same 7d/30d rows the Summary draws, narrowed to ONE metric and, when
// the host page says so, to a subset of its record types. That is what makes
// Workout > Steps a page about walking without a second taxonomy standing
// beside the first one.
//
// It will not print a number it cannot source. "Health Connect is absent" and
// "you did not grant this" are two different facts, and neither of them is
// "no data" — a page that answered all three with the same empty list would
// be inviting its reader to conclude they had not walked. Record types whose
// read permission was refused are dropped BEFORE the query rather than
// summed to zero afterwards, because a confident 0 km is a lie and a named
// gap is not.
@Composable
private fun MetricBody(metricId: String, recordNames: List<String>) {
    val ctx = LocalContext.current
    val metric = remember(metricId, recordNames) {
        HealthMetrics.byId(metricId)?.let { m ->
            if (recordNames.isEmpty()) m
            // orEmpty() because KClass.simpleName is nullable and a List<String>
            // will not be asked whether it contains a String? — a local class has no
            // simple name, and no page narrows to one.
            else m.copy(records = m.records.filter { it.simpleName.orEmpty() in recordNames })
        }
    }
    var availability by remember { mutableStateOf<HealthConnectGateway.Availability?>(null) }
    var refused by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows7d  by remember { mutableStateOf<List<HealthConnectGateway.WindowRow>>(emptyList()) }
    var rows30d by remember { mutableStateOf<List<HealthConnectGateway.WindowRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Bumped by the permission launcher to re-run the read. The launcher's own
    // result set is not trusted for that: the user may grant some of what was
    // asked and deny the rest, and the page has to redraw around what it
    // actually got rather than around what it requested.
    var grantRound by remember { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        HealthStore.recordPermissionGrant(ctx)
        grantRound++
    }

    LaunchedEffect(metric, grantRound) {
        loading = true
        availability = HealthConnectGateway.availability(ctx)
        val granted = HealthConnectGateway.grantedPermissions(ctx)
        val wanted  = metric?.records.orEmpty()
        val allowed = wanted.filter { HealthPermission.getReadPermission(it) in granted }
        refused = wanted.filterNot { it in allowed }.mapNotNull { it.simpleName?.removeSuffix("Record") }
        if (metric != null && allowed.isNotEmpty()) {
            val readable = metric.copy(records = allowed)
            rows7d  = HealthConnectGateway.readMetricWindow(ctx, readable, 7)
            rows30d = HealthConnectGateway.readMetricWindow(ctx, readable, 30)
        } else {
            rows7d  = emptyList()
            rows30d = emptyList()
        }
        loading = false
    }

    PageScroll {
        SectionHeader(metric?.let { "${it.label} · daily average" } ?: "Unknown metric")
        when {
            metric == null -> InfoCard(
                "No such metric",
                "This page asked for '$metricId', which the metric taxonomy in build.json does not declare. Nothing was read and nothing is shown.",
            )
            loading -> InfoCard("Loading…", "Reading the last 30 days from Health Connect…")
            availability == HealthConnectGateway.Availability.NotInstalled -> InfoCard(
                "Health Connect is not installed",
                "Every value on this page is a local query against the Health Connect store. Without the provider there is no source, and this page will not invent one.",
            )
            availability == HealthConnectGateway.Availability.UpdateRequired -> InfoCard(
                "Health Connect needs updating",
                "The installed provider is older than this app can read. Nothing was read.",
            )
            availability != HealthConnectGateway.Availability.Installed -> InfoCard(
                "Health Connect is not supported on this device",
                "There is no provider to read, so this page has no data source at all.",
            )
            else -> {
                if (refused.isNotEmpty()) {
                    InfoCard(
                        "Not allowed to read ${refused.joinToString(", ")}",
                        "Health Connect has not granted this app those record types. They are missing from the rows below rather than shown as zero — nothing here is estimated.",
                    )
                    OutlinedButton(
                        onClick = {
                            launcher.launch(
                                metric.records.map { HealthPermission.getReadPermission(it) }.toSet()
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) { Text("Grant the missing read permissions") }
                }
                val merged = (0 until maxOf(rows7d.size, rows30d.size)).map { i ->
                    val seven  = rows7d.getOrNull(i)
                    val thirty = rows30d.getOrNull(i)
                    Triple(seven?.label ?: thirty?.label ?: "—", seven?.value ?: "—", thirty?.value ?: "—")
                }
                if (merged.isEmpty()) {
                    InfoCard(
                        "Nothing to show",
                        "No record type on this page is both granted and populated. A producer — Garmin Connect, Google Fit, Samsung Health — has to be syncing into Health Connect for a number to exist.",
                    )
                } else {
                    merged.forEach { (label, value7d, value30d) ->
                        DualValueRow(label = label, value7d = value7d, value30d = value30d)
                    }
                }
            }
        }
    }
}

// ── Timeline tab ────────────────────────────────────────────────────
// Date picker at the top + flat list of every metric → every record
// with THAT day's value. Use ← / → / Today buttons to navigate.
@Composable
private fun TimelineBody() {
    val ctx = LocalContext.current
    val metrics = remember { HealthMetrics.all }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var rows by remember { mutableStateOf<Map<String, List<HealthConnectGateway.WindowRow>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(day) {
        loading = true
        val r = mutableMapOf<String, List<HealthConnectGateway.WindowRow>>()
        metrics.forEach { m -> r[m.id] = HealthConnectGateway.readMetricDay(ctx, m, day) }
        rows = r
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        DatePickerStrip(date = day, onChange = { day = it })
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            if (loading) item { InfoCard("Loading…", "Reading $day from Health Connect…") }
            metrics.forEach { m ->
                item(key = "h-${m.id}") { SectionHeader("${m.label} · ${m.records.size}") }
                val list = rows[m.id].orEmpty()
                if (list.isEmpty() && !loading) {
                    item(key = "e-${m.id}") {
                        InfoCard("${m.label} — no data on $day", "Nothing recorded for any of this metric's types.")
                    }
                } else {
                    items(list, key = { "r-${m.id}-${it.label}" }) { wr ->
                        SingleValueRow(label = wr.label, value = wr.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun DatePickerStrip(date: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { onChange(date.minusDays(1)) }) { Text("‹") }
        Spacer(Modifier.width(8.dp))
        Text(
            text = date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
            color = Color(0xFFEDE7FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onChange(date.plusDays(1)) }, enabled = date.isBefore(LocalDate.now())) { Text("›") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(LocalDate.now()) }) { Text("Today", fontSize = 12.sp) }
    }
}

// ── Configs tab — sub-tabs Sources / Permissions / Stats / Raw / About
private enum class ConfigsSubTab(val label: String) {
    Sources("Sources"), Permissions("Permissions"), Stats("Stats"),
    Raw("Raw"), About("About"),
}

@Composable
private fun ConfigsBody() {
    var sub by remember { mutableStateOf(ConfigsSubTab.Sources) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConfigsSubTab.values().forEach { t ->
                val sel = t == sub
                OutlinedButton(
                    onClick = { sub = t },
                    modifier = Modifier.weight(1f),
                ) { Text(t.label, fontSize = 10.sp, color = if (sel) Color.White else Color(0xFFCFC2F0)) }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (sub) {
                ConfigsSubTab.Sources     -> ConfigsSources()
                ConfigsSubTab.Permissions -> ConfigsPermissions()
                ConfigsSubTab.Stats       -> ConfigsStats()
                ConfigsSubTab.Raw         -> ConfigsRaw()
                ConfigsSubTab.About       -> ConfigsAbout()
            }
        }
    }
}

@Composable
private fun ConfigsSources() {
    val ctx = LocalContext.current
    var sources by remember { mutableStateOf<List<HealthConnectGateway.SourceBreakdown>>(emptyList()) }
    LaunchedEffect(Unit) { sources = HealthConnectGateway.readSourceBreakdown(ctx) }
    PageScroll {
        SectionHeader("Producers · last 30 days")
        if (sources.isEmpty()) {
            InfoCard("No producers seen", "Garmin Connect / Google Fit / Samsung Health / Fitbit / Oura must be configured to sync into Health Connect.")
        } else {
            sources.forEach { row ->
                MetricCard(
                    title    = row.packageName,
                    value    = "${row.recordCount}",
                    subtitle = row.lastWrite?.let { "last write " + DateTimeFormatter.ISO_INSTANT.format(it).take(19) } ?: "—",
                )
            }
        }
    }
}

@Composable
private fun ConfigsPermissions() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grants ->
        granted = grants
        HealthStore.recordPermissionGrant(ctx)
    }
    LaunchedEffect(Unit) { granted = HealthConnectGateway.grantedPermissions(ctx) }

    PageScroll {
        SectionHeader("Health Connect · permissions")
        InfoCard("Status", "${granted.size} / ${HealthMetrics.allPermissions.size} read perms granted.")
        OutlinedButton(
            onClick = { launcher.launch(HealthMetrics.allPermissions) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) { Text("Request all read perms") }

        SectionHeader("Per-metric perm state")
        HealthMetrics.all.forEach { m ->
            val ok = m.perms.count { it in granted }
            MetricCard(
                title    = m.label,
                value    = "$ok / ${m.perms.size}",
                subtitle = if (ok == m.perms.size && ok > 0) "all granted"
                           else if (ok == 0)               "none granted"
                           else                            "partial",
            )
        }
    }
}

@Composable
private fun ConfigsStats() {
    val ctx = LocalContext.current
    val historyLen = remember { HealthStore.history(ctx).length() }
    val lastTs     = remember { HealthStore.lastSnapshotTs(ctx) }
    val footprint  = remember { HealthStore.footprintBytes(ctx) }
    val sources    = remember { HealthStore.sourceLastSeen(ctx) }
    PageScroll {
        SectionHeader("Local cache")
        MetricCard("Snapshots cached", "$historyLen", "of 365 cap")
        MetricCard("Cache footprint",  "$footprint B", "HealthStore SharedPrefs")
        MetricCard("Last refresh",
            if (lastTs > 0L) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastTs)) else "—",
            "")
        SectionHeader("Producers — last seen")
        if (sources.isEmpty()) {
            InfoCard("None yet", "Open the Summary tab once with HC perms granted to populate.")
        } else {
            sources.entries.sortedByDescending { it.value }.forEach { (pkg, ts) ->
                MetricCard(pkg, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(ts)), "last write")
            }
        }
    }
}

@Composable
private fun ConfigsRaw() {
    val ctx = LocalContext.current
    val types: List<Pair<String, KClass<out Record>>> = listOf(
        "Steps"          to StepsRecord::class,
        "Heart rate"     to HeartRateRecord::class,
        "Sleep sessions" to SleepSessionRecord::class,
        "Weight"         to WeightRecord::class,
    )
    var picked by remember { mutableStateOf(types.first()) }
    var rows by remember { mutableStateOf<List<HealthConnectGateway.RawRow>>(emptyList()) }
    LaunchedEffect(picked) { rows = HealthConnectGateway.readRawWindow(ctx, picked.second) }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SectionHeader("Raw · ${picked.first} · last 7 days")
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { t ->
                OutlinedButton(onClick = { picked = t }) { Text(t.first, fontSize = 11.sp) }
            }
        }
        if (rows.isEmpty()) InfoCard("Empty window", "No ${picked.first} records in the last 7 days.")
        else LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(r.summary, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${r.ts}", color = Color(0xFFAAA0CC), fontSize = 11.sp)
                        Text("from ${r.origin}", color = Color(0xFFAAA0CC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("uid ${r.uid}", color = Color(0xFF6E5C95), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigsAbout() = PageScroll {
    SectionHeader("MyHealth · About")
    InfoCard("Local-only", "Every value rendered here came from a local SQLite query against Health Connect. No HTTPS, no OAuth, no upload. The instrumented test asserts this with StrictMode.")
    InfoCard("Sources", "Health Connect aggregates: Garmin Connect, Google Fit, Samsung Health, Fitbit (Health API bridge), Oura, Whoop. Toggle sync inside each producer app.")
    InfoCard("Setup", "1) Install Health Connect (Play Store).\n2) Garmin Connect → Settings → Health Connect → enable.\n3) Repeat for any other tracker.\n4) Open MyHealth → Configs → Permissions → Request all.")
}

// ── Shared rows + helpers ───────────────────────────────────────────
@Composable
private fun DualValueRow(label: String, value7d: String, value30d: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFEDE7FF), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("7d  $value7d",  color = Color.White,        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("30d $value30d", color = Color(0xFFB6A8DC), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SingleValueRow(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFEDE7FF), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PageScroll(content: @Composable () -> Unit) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { item { content() } }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text       = text,
        color      = Color(0xFFEDE7FF),
        fontSize   = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier   = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun MetricCard(title: String, value: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title,    color = Color(0xFFCFC2F0), fontSize = 13.sp)
                if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xFF8A7DAC), fontSize = 11.sp)
            }
            Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = Color(0xFFEDE7FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body,  color = Color(0xFFB6A8DC), fontSize = 12.sp)
        }
    }
}
