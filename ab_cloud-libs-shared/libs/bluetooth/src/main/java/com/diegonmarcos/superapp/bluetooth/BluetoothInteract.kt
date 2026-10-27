package com.diegonmarcos.superapp.bluetooth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Interact tab (#1221): the live dashboard and the GATT tree with read / write / notify / indicate and descriptors. */
internal fun LazyListScope.interactTab(c: BleController, s: BleState) {
    if (s.conn != ConnState.Connected) {
        item("none") {
            BtCard {
                BtTitle(if (s.selected == null) "No device selected" else "Not connected")
                BtMuted("Connect a device in Connect to browse its GATT tree.")
            }
        }
        return
    }
    item("dash") { Dashboard(c, s) }
    item("tree-head") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtTitle("GATT tree (${s.services.size} services)")
            BtButton("Read all", tag = "read-all") { c.readAll() }
        }
    }
    if (s.services.isEmpty()) item("no-services") { BtMuted("No services discovered yet. Use Reload services in Connect.") }
    s.services.forEach { sv -> item("svc:" + sv.uuid) { ServiceCard(c, s, sv) } }
}

@Composable
private fun ServiceCard(c: BleController, s: BleState, sv: GattService) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
    var expanded by remember { mutableStateOf(true) }
    BtCard(Modifier.testTag("bt:service:${sv.uuid}")) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BtTitle(c.catalog.serviceName(sv.uuid))
                BtMuted(Uuids.short(sv.uuid) + "  " + c.catalog.categoryLabel(c.catalog.categoryOf(sv.uuid)), mono = true)
            }
            BtMuted(if (expanded) "hide" else "show ${sv.characteristics.size}")
        }
        if (expanded) sv.characteristics.forEach { ch -> CharacteristicRow(c, s, ch, open) }
    }
}

@Composable
private fun CharacteristicRow(c: BleController, s: BleState, ch: GattCharacteristic, open: MutableMap<String, Boolean>) {
    val value = s.values[ch.key]
    val mode = s.notifying[ch.key]
    var hex by remember { mutableStateOf("") }
    var withResponse by remember { mutableStateOf(!ch.canWriteNoResponse || ch.properties and GattProps.WRITE != 0) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(c.catalog.characteristicName(ch.uuid), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        BtMuted(Uuids.short(ch.uuid) + "  " + GattProps.names(ch.properties).joinToString(" / "), mono = true)
        if (value != null) {
            Text(value.decoded?.text ?: Hex.format(value.bytes), fontSize = 12.sp, color = BtOk)
            BtMuted(Hex.format(value.bytes) + if (value.notified) "  (notified)" else "", mono = true)
            value.decoded?.fields?.forEach { (k, v) -> BtMuted("$k: $v") }
        }
        BtRow {
            if (ch.canRead) BtButton("Read", tag = "read:${ch.key}") { c.read(ch) }
            if (ch.canNotify) BtButton(if (mode == NotifyMode.Notify) "Stop notify" else "Notify", tag = "notify:${ch.key}") {
                c.setNotify(ch, if (mode == NotifyMode.Notify) NotifyMode.Off else NotifyMode.Notify)
            }
            if (ch.canIndicate) BtButton(if (mode == NotifyMode.Indicate) "Stop indicate" else "Indicate", tag = "indicate:${ch.key}") {
                c.setNotify(ch, if (mode == NotifyMode.Indicate) NotifyMode.Off else NotifyMode.Indicate)
            }
            if (ch.canWrite) BtButton(if (open[ch.key] == true) "Close write" else "Write", tag = "write:${ch.key}") { open[ch.key] = open[ch.key] != true }
        }
        if (open[ch.key] == true && ch.canWrite) {
            OutlinedTextField(hex, { hex = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Hex bytes", fontSize = 11.sp) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ch.canWriteNoResponse && ch.properties and GattProps.WRITE != 0) {
                    Switch(withResponse, { withResponse = it }); Spacer(Modifier.width(6.dp)); BtMuted("With response"); Spacer(Modifier.width(12.dp))
                }
                BtButton("Send", primary = true, enabled = hex.isNotBlank(), tag = "send:${ch.key}") { c.writeHex(ch, hex, withResponse) }
            }
        }
        ch.descriptors.forEach { d ->
            Row(Modifier.fillMaxWidth().clickable { c.readDescriptor(ch, d.uuid) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                BtMuted("descriptor " + c.catalog.descriptorName(d.uuid) + "  " + Uuids.short(d.uuid), Modifier.weight(1f))
                BtMuted("read")
            }
        }
    }
}

// ---- dashboard ----

private enum class Profile(val label: String) { Ring("Smart ring"), Hrm("Heart rate"), Env("Environment"), Cycling("Cycling"), Info("Device info") }

private fun profileOf(family: String): Profile = when (family) {
    "ring" -> Profile.Ring; "hrm" -> Profile.Hrm; "env" -> Profile.Env; "cycling" -> Profile.Cycling; else -> Profile.Info
}

private fun valueText(s: BleState, assigned: Int): String? =
    s.values.entries.firstOrNull { Uuids.assigned(it.key.substringAfter('/')) == assigned }?.value?.decoded?.text

@Composable
private fun Dashboard(c: BleController, s: BleState) {
    val auto = profileOf(c.catalog.deviceCategory(s.services.map { it.uuid }))
    var chosen by remember { mutableStateOf<Profile?>(null) }
    val profile = chosen ?: auto
    BtCard(Modifier.testTag("bt:dashboard")) {
        BtTitle("Live dashboard")
        BtRow { Profile.entries.forEach { p -> BtChoice(p.label + if (p == auto) " (detected)" else "", p == profile) { chosen = p } } }
        Waveform(s.telemetry, s.telemetryLabel, onClear = { c.clearTelemetry() })
        when (profile) {
            Profile.Ring -> {
                Metric("Heart rate", valueText(s, 0x2A37) ?: "--")
                Metric("Battery", valueText(s, 0x2A19) ?: "--")
                val write = preferredWrite(c.catalog, s.services.flatMap { it.characteristics }.filter { it.canWrite })
                if (write != null && c.catalog.probes.isNotEmpty()) {
                    BtMuted("Quick actions write to " + c.catalog.characteristicName(write.uuid))
                    BtRow { c.catalog.probes.take(3).forEach { p -> BtButton(p.label, tag = "probe:${p.hex}") { c.writeHex(write, p.hex, true) } } }
                }
            }
            Profile.Hrm -> {
                Metric("Heart rate", valueText(s, 0x2A37) ?: "--")
                Metric("Sensor location", valueText(s, 0x2A38) ?: "--")
                Metric("Battery", valueText(s, 0x2A19) ?: "--")
            }
            Profile.Env -> {
                Metric("Temperature", valueText(s, 0x2A6E) ?: valueText(s, 0x2A1C) ?: "--")
                Metric("Humidity", valueText(s, 0x2A6F) ?: "--")
                Metric("Pressure", valueText(s, 0x2A6D) ?: "--")
            }
            Profile.Cycling -> Metric("Speed and cadence", valueText(s, 0x2A5B) ?: "--")
            Profile.Info -> {
                Metric("Manufacturer", valueText(s, 0x2A29) ?: "--")
                Metric("Model", valueText(s, 0x2A24) ?: "--")
                Metric("Serial", valueText(s, 0x2A25) ?: "--")
                Metric("Firmware", valueText(s, 0x2A26) ?: "--")
                Metric("Hardware", valueText(s, 0x2A27) ?: "--")
                Metric("Software", valueText(s, 0x2A28) ?: "--")
                Metric("Battery", valueText(s, 0x2A19) ?: "--")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) = KeyValue(label, value)

@Composable
private fun Waveform(samples: List<Double>, label: String, onClear: () -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BtMuted(if (samples.isEmpty()) "Awaiting samples. Subscribe to a measurement or read a value." else "$label, latest ${fmt(samples.last())} (n=${samples.size})", Modifier.weight(1f))
        if (samples.isNotEmpty()) BtMuted("clear", Modifier.clickable { onClear() })
    }
    Canvas(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.25f)).testTag("bt:waveform")) {
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        if (samples.size >= 2) {
            val lo = samples.min(); val hi = samples.max()
            val span = if (hi - lo < 1e-9) 1.0 else hi - lo
            val path = Path()
            samples.forEachIndexed { i, v ->
                val x = size.width * i / (BleController.MAX_SAMPLES - 1).coerceAtLeast(1)
                val y = (size.height - 8f) - ((v - lo) / span * (size.height - 16f)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 3f))
        }
    }
}

private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(d)
