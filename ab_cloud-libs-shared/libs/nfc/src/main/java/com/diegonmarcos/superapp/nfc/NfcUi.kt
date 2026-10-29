package com.diegonmarcos.superapp.nfc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The tabs of the NFC page (#1264). [id] is the mode / page id suffix a host declares (`nfc-scan`, ...). */
enum class NfcTab(val id: String, val label: String) {
    Scan("scan", "Scan"), Write("write", "Write"), History("history", "History"), Status("status", "Status");

    companion object { fun of(id: String?): NfcTab = entries.firstOrNull { it.id == id } ?: Scan }
}

private val NfcOk = Color(0xFF7FC98F)
private val NfcWarn = Color(0xFFE2B85C)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Android's NFC settings screen (apps cannot switch NFC on themselves); the wireless settings where the NFC screen is missing. */
fun openNfcSettings(ctx: Context) {
    val i = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** Share a text export (JSON or CSV) through the system share sheet. */
fun shareText(ctx: Context, subject: String, mime: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * One tab of the NFC page over the shared [controller]. Reader mode runs on the hosting activity while this page is resumed
 * (Scan and Write both listen: a pending write is applied to the next tag), and stops on pause.
 */
@Composable
fun NfcPanel(controller: NfcController, tab: NfcTab, modifier: Modifier = Modifier) {
    val s by controller.state.collectAsState()
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, controller) {
        val act = ctx.activity()
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> act?.let { controller.startReader(it) } ?: controller.refresh()
                Lifecycle.Event.ON_PAUSE -> act?.let { controller.stopReader(it) }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) act?.let { controller.startReader(it) } ?: controller.refresh()
        onDispose { owner.lifecycle.removeObserver(obs); act?.let { controller.stopReader(it) } }
    }
    LazyColumn(modifier.fillMaxSize().testTag("nfc:${tab.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("gate") { NfcGate(s) }
        when (tab) {
            NfcTab.Scan -> scanTab(s)
            NfcTab.Write -> item("write") { WriteCard(controller, s) }
            NfcTab.History -> historyTab(controller, s)
            NfcTab.Status -> item("status") { StatusCard(controller, s) }
        }
        item("end") { Spacer(Modifier.height(24.dp)) }
    }
}

// ---- shared bits ----

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Muted(text: String, mono: Boolean = false) =
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (mono) FontFamily.Monospace else null)

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.width(140.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = if (selected) 0.22f else 0.08f)).clickable { onClick() }
        .padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, color = c)
}

@Composable
private fun Btn(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (primary) Button(onClick, Modifier.testTag("nfc:btn:$label"), enabled = enabled) { Text(label, fontSize = 12.sp) }
    else OutlinedButton(onClick, Modifier.testTag("nfc:btn:$label"), enabled = enabled) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun NfcGate(s: NfcState) {
    val ctx = LocalContext.current
    when {
        !s.available -> Card { Title("This phone has no NFC"); Muted("The NFC tools need an NFC radio.") }
        !s.enabled -> Card {
            Title("NFC is off")
            Muted("Android lets only the system switch NFC on. Open the NFC settings, turn it on, and come back.")
            Btn("Open NFC settings", primary = true) { openNfcSettings(ctx) }
        }
        else -> Text(
            if (s.pending != null) "Waiting for a tag to write: ${s.pending.label}" else if (s.listening) "Ready: hold a tag to the back of the phone" else "Reader paused",
            fontSize = 12.sp, color = if (s.listening) NfcOk else NfcWarn,
        )
    }
    if (s.status.isNotEmpty()) Muted(s.status)
}

// ---- Scan ----

private fun LazyListScope.scanTab(s: NfcState) {
    val t = s.last
    if (t == null) { item("none") { Card { Title("No tag yet"); Muted("Tap a tag: UID, ATQA / SAK, ATS, technologies, memory and its NDEF records appear here.") } }; return }
    item("head") {
        Card {
            Title(t.type)
            KeyValue("Read at", stamp(t.atMs))
            if (t.error.isNotEmpty()) Text(t.error, fontSize = 11.sp, color = NfcWarn)
        }
    }
    items(t.sections, key = { "sec:" + it.title }) { sec ->
        Card {
            Title(sec.title)
            sec.rows.forEach { (k, v) -> KeyValue(k, v) }
            if (sec.dump.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) { Muted(sec.dump, mono = true) }
        }
    }
    if (t.ndef.isNotEmpty()) item("ndefhead") { Title("NDEF records (${t.ndef.size})") }
    items(t.ndef.withIndex().toList(), key = { "rec:" + it.index }) { (i, r) -> RecordCard(i, r) }
}

@Composable
private fun RecordCard(i: Int, r: NdefRecord) {
    val d = r.decoded
    Card {
        Title("#${i + 1} ${d.kind}")
        Text(d.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        KeyValue("TNF", "${r.tnf} ${r.tnfName}")
        if (r.type.isNotEmpty()) KeyValue("Type", r.typeText)
        if (r.id.isNotEmpty()) KeyValue("ID", Bytes.hex(r.id, " "))
        KeyValue("Payload", "${r.payload.size} bytes")
        d.fields.forEach { (k, v) -> KeyValue(k, v) }
        d.nested.forEachIndexed { j, n -> Muted("  ${j + 1}. ${n.decoded.kind}: ${n.decoded.summary}") }
        // The Wi-Fi key stays out of the dump as well: a WSC record shows its decoded fields only.
        if (r.typeText.lowercase() != "application/vnd.wfa.wsc") Row(Modifier.horizontalScroll(rememberScrollState())) { Muted(Bytes.dump(r.payload), mono = true) }
    }
}

private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

// ---- Write ----

private val KINDS = listOf("Text", "URI", "Smart poster", "Wi-Fi", "App (AAR)", "MIME", "External", "Erase")

@Composable
private fun WriteCard(controller: NfcController, s: NfcState) {
    var kind by rememberSaveable { mutableStateOf("Text") }
    var a by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(true) }
    Card {
        Title("Write NDEF to your own tag")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { KINDS.forEach { k -> Choice(k, k == kind) { kind = k } } }
        val (la, lb) = when (kind) {
            "Text" -> "Text" to "Language (en)"
            "URI" -> "URI" to ""
            "Smart poster" -> "URI" to "Title"
            "Wi-Fi" -> "SSID" to "Password (empty = open)"
            "App (AAR)" -> "Package name" to ""
            "MIME" -> "MIME type (text/plain)" to "Content"
            "External" -> "domain:type" to "Payload (text)"
            else -> "" to ""
        }
        if (la.isNotEmpty()) OutlinedTextField(a, { a = it }, Modifier.fillMaxWidth().testTag("nfc:write:a"), label = { Text(la) }, singleLine = kind != "Text")
        if (lb.isNotEmpty()) OutlinedTextField(b, { b = it }, Modifier.fillMaxWidth().testTag("nfc:write:b"), label = { Text(lb) })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(format, { format = it }); Spacer(Modifier.width(8.dp)); Muted("Format a blank tag to NDEF") }
        val built = remember(kind, a, b) { runCatching { build(kind, a, b) } }
        built.exceptionOrNull()?.let { Text(it.message ?: "Invalid", fontSize = 11.sp, color = NfcWarn) }
        built.getOrNull()?.let { bytes -> Muted("${bytes.size} bytes" + (s.last?.ndefCapacity?.let { " (last tag holds $it)" } ?: "")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Write on next tap", primary = true, enabled = built.isSuccess && s.enabled) {
                controller.armWrite(PendingWrite(if (kind == "Wi-Fi") "Wi-Fi $a" else "$kind ${a.take(40)}", built.getOrThrow(), format))
            }
            if (s.pending != null) Btn("Cancel") { controller.armWrite(null) }
        }
        Muted("Writes only to tags you own. Making a tag read-only is permanent and is not offered here.")
    }
}

/** The message a Write form describes; throws with a human message on bad input. */
fun build(kind: String, a: String, b: String): ByteArray = Ndef.encode(when (kind) {
    "Text" -> { require(a.isNotEmpty()) { "Enter the text" }; listOf(Ndef.textRecord(a, b.ifBlank { "en" })) }
    "URI" -> { require(a.isNotBlank()) { "Enter the URI" }; listOf(Ndef.uriRecord(a.trim())) }
    "Smart poster" -> { require(a.isNotBlank()) { "Enter the URI" }; listOf(Ndef.smartPosterRecord(a.trim(), b)) }
    "Wi-Fi" -> { require(a.isNotEmpty()) { "Enter the SSID" }; listOf(Ndef.wifiRecord(a, b)) }
    "App (AAR)" -> { require(Regex("[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+").matches(a.trim())) { "Enter a package name like com.example.app" }; listOf(Ndef.aarRecord(a.trim())) }
    "MIME" -> { require(Regex("[\\w.+-]+/[\\w.+-]+").matches(a.trim())) { "Enter a MIME type like text/plain" }; listOf(Ndef.mimeRecord(a.trim(), b.toByteArray(Charsets.UTF_8))) }
    "External" -> { require(Regex("[\\w.-]+:[\\w.-]+").matches(a.trim())) { "Enter a type like example.com:mytype" }; listOf(Ndef.externalRecord(a.trim(), b.toByteArray(Charsets.UTF_8))) }
    else -> emptyList()
})

// ---- History ----

private fun LazyListScope.historyTab(controller: NfcController, s: NfcState) {
    item("tools") {
        val ctx = LocalContext.current
        Card {
            Title("Scan history (${s.history.size})")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Export JSON", enabled = s.history.isNotEmpty()) { shareText(ctx, "NFC scans.json", "application/json", NfcHistory.json(s.history)) }
                Btn("Export CSV", enabled = s.history.isNotEmpty()) { shareText(ctx, "NFC scans.csv", "text/csv", NfcHistory.csv(s.history)) }
                Btn("Clear", enabled = s.history.isNotEmpty()) { controller.clearHistory() }
            }
        }
    }
    items(s.history.withIndex().toList(), key = { "h:" + it.index }) { (_, e) ->
        Card {
            Title(e.type)
            KeyValue("When", stamp(e.atMs))
            KeyValue("UID", e.uid)
            KeyValue("Tech", e.techs.joinToString(" "))
            if (e.ndef.isNotEmpty()) Muted(e.ndef)
            if (e.note.isNotEmpty()) Text(e.note, fontSize = 11.sp, color = NfcWarn)
        }
    }
}

// ---- Status ----

@Composable
private fun StatusCard(controller: NfcController, s: NfcState) {
    val ctx = LocalContext.current
    Card {
        Title("NFC status")
        KeyValue("NFC hardware", if (s.available) "present" else "absent")
        KeyValue("NFC", if (s.enabled) "on" else "off")
        KeyValue("Reader mode", if (s.listening) "listening" else "stopped")
        KeyValue("Card emulation (HCE)", if (ctx.packageManager.hasSystemFeature("android.hardware.nfc.hce")) "supported" else "not supported")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Open NFC settings", primary = true) { openNfcSettings(ctx) }
            Btn("Refresh") { controller.refresh() }
        }
        Muted("Android does not let apps switch NFC on or off: the switch is in the system settings.")
    }
}
