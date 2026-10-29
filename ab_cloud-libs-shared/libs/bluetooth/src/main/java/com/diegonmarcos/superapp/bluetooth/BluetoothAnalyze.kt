package com.diegonmarcos.superapp.bluetooth

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** How the Analyze list is ordered. */
enum class AnalyzeSort(val label: String) { Rssi("Signal"), Name("Name"), Recent("Recent"), Beacons("Beacons first") }

/** Analyze, the scanner as an instrument (#1264): parsed advertising data, beacons, RSSI history and distance, classic inquiry, export. */
internal fun LazyListScope.analyzeTab(c: BleController, s: BleState, open: (BtTab) -> Unit) {
    item("an-controls") { AnalyzeControls(c, s) }
    val devices = s.seen.values.toList()
    item("an-count") { BtTitle("Devices (${devices.size})  LE ${devices.count { !it.classic }}  classic ${devices.count { it.classic }}") }
    if (devices.isEmpty()) item("an-empty") { BtMuted(if (s.scanning || s.discovering) "Listening..." else "Start an LE scan or a classic discovery.") }
    items(sortForAnalyze(c, s, devices), key = { "an" + it.address }) { d -> AnalyzeRow(c, s, d, open) }
    if (s.bonded.isNotEmpty()) {
        item("an-bonded") { BtTitle("Bonded (${s.bonded.size})") }
        items(s.bonded, key = { "anb" + it.address }) { d ->
            BtCard(Modifier.testTag("bt:bonded:${d.address}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { BtTitle(d.label); BtMuted(d.address, mono = true) }
                    BtPill(if (d.classic) "classic" else "LE / dual", BtWarn)
                }
                d.deviceClass?.let { KeyValue("Class of device", "${BtClass.major(it)}  0x%06X".format(it)) }
            }
        }
    }
}

private val sortChoice = mutableStateOf(AnalyzeSort.Rssi)

private fun sortForAnalyze(c: BleController, s: BleState, list: List<BleDevice>): List<BleDevice> = when (sortChoice.value) {
    AnalyzeSort.Rssi -> list.sortedByDescending { c.smoothed(it.address, s) ?: it.rssi.toDouble() }
    AnalyzeSort.Name -> list.sortedBy { it.label.lowercase() }
    AnalyzeSort.Recent -> list.sortedByDescending { it.lastSeenMs }
    AnalyzeSort.Beacons -> list.sortedWith(compareByDescending<BleDevice> { c.advertisement(it).beacons.isNotEmpty() }.thenByDescending { it.rssi })
}

@Composable
private fun AnalyzeControls(c: BleController, s: BleState) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf("") }
    val write: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) } }
            .onSuccess { c.log.add(LogKind.Sys, "Exported ${pending.length} bytes") }.onFailure { c.log.add(LogKind.Error, "Export failed: ${it.message}") }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), write)
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), write)
    BtCard(Modifier.testTag("bt:analyze")) {
        BtRow {
            BtButton(if (s.scanning) "Stop LE scan" else "LE scan", primary = true, tag = "an-scan") { if (s.scanning) c.stopScan() else c.startScan() }
            BtButton(if (s.discovering) "Stop classic" else "Classic discovery", tag = "an-classic") { if (s.discovering) c.stopDiscovery() else c.startDiscovery() }
        }
        if (s.scanError.isNotEmpty()) BtPill(s.scanError, BtBad)
        BtMuted("Sort")
        BtRow { AnalyzeSort.entries.forEach { o -> BtChoice(o.label, sortChoice.value == o) { sortChoice.value = o } } }
        Text("Path-loss exponent n = ${"%.1f".format(s.pathLoss)} (2 open space, 3 indoors)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(s.pathLoss.toFloat(), { c.setPathLoss(it.toDouble()) }, valueRange = 1.5f..5f)
        BtRow {
            BtButton("Export JSON", enabled = s.seen.isNotEmpty() || s.bonded.isNotEmpty(), tag = "an-export-json") {
                pending = BleExport.json(c.exportRows(), BluetoothHub.companies(ctx), System.currentTimeMillis()); saveJson.launch("bluetooth-scan.json")
            }
            BtButton("Export CSV", enabled = s.seen.isNotEmpty() || s.bonded.isNotEmpty(), tag = "an-export-csv") {
                pending = BleExport.csv(c.exportRows(), BluetoothHub.companies(ctx)); saveCsv.launch("bluetooth-scan.csv")
            }
        }
    }
}

@Composable
private fun AnalyzeRow(c: BleController, s: BleState, d: BleDevice, open: (BtTab) -> Unit) {
    val ctx = LocalContext.current
    val companies = remember { runCatching { BluetoothHub.companies(ctx) }.getOrNull() }
    var expanded by rememberSaveable(d.address) { mutableStateOf(false) }
    val adv = remember(d.raw) { c.advertisement(d) }
    val hist = s.history[d.address].orEmpty()
    val sm = c.smoothed(d.address, s)
    val dist = c.distance(d, s)
    BtCard(Modifier.clickable { expanded = !expanded }.testTag("bt:an:${d.address}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(d.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                BtMuted(d.address, mono = true)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (d.rssi > -127) "${d.rssi} dBm" else "--", fontSize = 12.sp, color = rssiColor(d.rssi))
                sm?.let { BtMuted("avg %.1f".format(it)) }
                dist?.let { BtMuted("~%.1f m".format(it)) }
            }
        }
        if (hist.size >= 2) Sparkline(hist, Modifier.fillMaxWidth().height(28.dp))
        BtRow {
            if (d.classic) BtPill("classic", BtWarn) else BtPill("LE", MaterialTheme.colorScheme.primary)
            if (d.bonded) BtPill("bonded", BtOk)
            if (!d.connectable) BtPill("non-connectable", BtBad)
            adv.beacons.forEach { BtPill(it.kind, BtOk) }
            adv.manufacturer.keys.forEach { k -> BtPill(companies?.name(k) ?: "0x%04X".format(k), MaterialTheme.colorScheme.onSurfaceVariant) }
            if (d.phy.isNotEmpty()) BtPill(d.phy, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            d.deviceClass?.let { KeyValue("Class of device", "${BtClass.major(it)}  0x%06X".format(it)) }
            if (adv.flagNames.isNotEmpty()) KeyValue("Flags 0x%02X".format(adv.flags ?: 0), adv.flagNames.joinToString(", "))
            adv.name?.let { KeyValue(if (adv.shortName) "Short name" else "Local name", it) }
            adv.txPower?.let { KeyValue("TX power", "$it dBm") }
            adv.txAt1m?.let { KeyValue("1 m reference", "$it dBm") }
            adv.appearance?.let { KeyValue("Appearance", "0x%04X".format(it)) }
            if (adv.serviceUuids.isNotEmpty()) {
                BtMuted("Service UUIDs")
                adv.serviceUuids.forEach { BtMuted("  ${Uuids.short(it)}  ${c.catalog.service(it)?.name ?: ""}", mono = true) }
            }
            if (adv.solicited.isNotEmpty()) BtMuted("Solicited: " + adv.solicited.joinToString(", ") { Uuids.short(it) }, mono = true)
            adv.serviceData.forEach { (u, v) ->
                BtMuted("Service data ${Uuids.short(u)} ${companies?.serviceDataName(u)?.let { "($it)" } ?: ""}: ${Hex.format(v)}", mono = true)
            }
            adv.manufacturer.forEach { (k, v) ->
                val apple = companies?.appleType(k, v)
                BtMuted("Manufacturer ${companies?.name(k) ?: ""} 0x%04X%s: %s".format(k, apple?.let { " [$it]" } ?: "", Hex.format(v)), mono = true)
            }
            adv.beacons.forEach { b ->
                BtTitle(b.kind)
                b.fields.forEach { (k, v) -> KeyValue(k, v) }
            }
            if (adv.structures.isNotEmpty()) {
                BtMuted("AD structures" + if (adv.malformed) " (truncated record)" else "")
                adv.structures.forEach { st -> BtMuted("  0x%02X %s: %s".format(st.type, st.typeName, st.hex), mono = true) }
            }
            if (d.raw.isNotEmpty()) BtMuted("Raw: ${d.raw}", mono = true)
            BtRow {
                BtButton("Locate", tag = "an-locate") { c.setLocate(d.address); open(BtTab.Locate) }
                if (d.connectable && !d.classic) BtButton("GATT explorer", tag = "an-gatt") { c.select(d.address); open(BtTab.Connect) }
            }
        }
    }
}

internal fun rssiColor(rssi: Int): Color = when { rssi >= -60 -> BtOk; rssi >= -80 -> BtWarn; else -> BtBad }

@Composable
internal fun Sparkline(values: List<Int>, modifier: Modifier, lo: Int = -100, hi: Int = -30) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.18f))) {
        if (values.size < 2) return@Canvas
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - ((v.coerceIn(lo, hi) - lo).toFloat() / (hi - lo)) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2f))
    }
}

// ---- Locate ----

/** Locate (#1264): a hot / cold finder for one device over the live scan, with a beep whose rate follows the signal. */
internal fun LazyListScope.locateTab(c: BleController, s: BleState) {
    val target = s.locate?.let { s.seen[it] }
    if (s.locate == null || target == null) {
        item("lo-pick") {
            BtCard(Modifier.testTag("bt:locate-pick")) {
                BtTitle("Pick a device to locate")
                if (!s.scanning) BtButton("LE scan", primary = true, tag = "lo-scan") { c.startScan() }
                val near = s.seen.values.sortedByDescending { it.rssi }.take(15)
                if (near.isEmpty()) BtMuted("Nothing seen yet.")
                near.forEach { d -> BtChoice("${d.label}  ${d.rssi} dBm  ${d.address}", selected = false) { c.setLocate(d.address) } }
            }
        }
        return
    }
    item("lo-target") { LocateCard(c, s, target) }
}

@Composable
private fun LocateCard(c: BleController, s: BleState, d: BleDevice) {
    val hist = s.history[d.address].orEmpty()
    val sm = c.smoothed(d.address, s)
    val dist = c.distance(d, s)
    var beep by rememberSaveable { mutableStateOf(false) }
    val level by rememberUpdatedState(sm ?: d.rssi.toDouble())
    LaunchedEffect(Unit) { if (!c.state.value.scanning) c.startScan(fresh = false) }
    if (beep) {
        val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
        DisposableEffect(tone) { onDispose { tone?.release() } }
        LaunchedEffect(tone) {
            while (true) {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                delay(Locator.beepIntervalMs(level))
            }
        }
    }
    BtCard(Modifier.testTag("bt:locate")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { BtTitle(d.label); BtMuted(d.address, mono = true) }
            BtButton("Change", tag = "lo-change") { c.setLocate(null) }
        }
        Text("%.0f dBm".format(sm ?: d.rssi.toDouble()), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = rssiColor((sm ?: d.rssi.toDouble()).toInt()))
        Text(Locator.trend(hist), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        val p = Locator.proximity(sm ?: d.rssi.toDouble())
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(p.toFloat()).fillMaxHeight().background(rssiColor((sm ?: d.rssi.toDouble()).toInt())))
        }
        KeyValue("Last sample", "${d.rssi} dBm")
        KeyValue("Distance estimate", dist?.let { "%.2f m (n=%.1f)".format(it, s.pathLoss) } ?: "no 1 m reference in the advertisement")
        KeyValue("Samples", "${hist.size}")
        Sparkline(hist, Modifier.fillMaxWidth().height(80.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(beep, { beep = it }, Modifier.testTag("bt:locate-beep")); Spacer(Modifier.width(6.dp)); BtMuted("Beep: faster when closer")
        }
        if (!s.scanning) BtButton("Resume scan", primary = true, tag = "lo-resume") { c.startScan(fresh = false) }
    }
}
