@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Display names of the four modes; the one place they are spelled. */
fun ChannelMode.title(): String = when (this) {
    ChannelMode.LOCAL_SERVER -> "Local server"
    ChannelMode.EMBEDDED_ONLY -> "Embedded adb"
    ChannelMode.SHIZUKU -> "Shizuku"
    ChannelMode.AUTO -> "Auto"
}

/** What a mode does, one line. */
fun ChannelMode.hint(): String = when (this) {
    ChannelMode.LOCAL_SERVER -> "Commands run through the local server on 127.0.0.1; adb only starts it."
    ChannelMode.EMBEDDED_ONLY -> "Embedded adb only; the local server is skipped."
    ChannelMode.SHIZUKU -> "The external Shizuku app only."
    ChannelMode.AUTO -> "Embedded adb, then the local server, then Shizuku."
}

private val TIGHT = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
private val FLAT = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)   // dense: no minimum height

/**
 * The "Privileged channel" mode selector: the SAME composable in Cloud Store's settings and the
 * SuperApp's ADB Shell page. Local server first (the default), then the owner's order.
 */
@Composable
fun ChannelModeBar(onChanged: (ChannelMode) -> Unit = {}) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(ChannelModePrefs.current(ctx)) }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in listOf(ChannelMode.LOCAL_SERVER, ChannelMode.EMBEDDED_ONLY, ChannelMode.SHIZUKU, ChannelMode.AUTO)) {
                val pick = { cur = m; ChannelModePrefs.set(ctx, m); onChanged(m) }
                if (m == cur) Button(onClick = pick, modifier = FLAT, contentPadding = TIGHT) { Text(m.title(), fontSize = 12.sp) }
                else OutlinedButton(onClick = pick, modifier = FLAT, contentPadding = TIGHT) { Text(m.title(), fontSize = 12.sp) }
            }
        }
        Text(cur.hint(), fontSize = 11.sp)
    }
}

/**
 * The full control for the privileged channel: mode, every layer's live status, the route, and the
 * actions. Hosted by the SuperApp's Configs page; the mode bar alone is Cloud Store's setting.
 */
@Composable
fun ShellChannelPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Reading the layers...") }
    var output by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(tick) {
        status = withContext(Dispatchers.IO) {
            runCatching { ShellChannelProbe.layers(ctx).describe().joinToString("\n") { (k, v) -> "$k: $v" } }
                .getOrElse { "Could not read the layers: ${it.message}" }
        }
    }
    @Composable fun actionButton(label: String, f: (Context) -> String) =
        OutlinedButton(onClick = {
            output = "..."
            scope.launch {
                output = withContext(Dispatchers.IO) { runCatching { f(ctx) }.getOrElse { it.message ?: "failed" } }
                tick++
            }
        }, modifier = FLAT, contentPadding = TIGHT) { Text(label, fontSize = 12.sp) }

    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Privileged channel", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        ChannelModeBar { tick++ }
        Text(status, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            actionButton("Pair") { ShellChannelProbe.pair(it) }
            actionButton("Reconnect") { ShellChannelProbe.reconnect(it) }
            actionButton("Start server") { ShellChannelProbe.startServer(it, restart = false) }
            actionButton("Restart") { ShellChannelProbe.startServer(it, restart = true) }
            actionButton("Stop server") { ShellChannelProbe.stopServer(it) }
            actionButton("Open Shizuku") { ShizukuStatus.act(it) }
            actionButton("Developer options") { ShellChannelProbe.openDeveloperOptions(it); "Opened Developer options." }
            actionButton("Test") { ShellChannelProbe.test(it) }
        }
        if (output.isNotEmpty()) SelectionContainer { Text(output, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
    }
}
