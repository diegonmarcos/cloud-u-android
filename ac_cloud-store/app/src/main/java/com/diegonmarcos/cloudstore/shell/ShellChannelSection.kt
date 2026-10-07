package com.diegonmarcos.cloudstore.shell

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.adbdebug.AdbPairingService
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.appstore.StoreDensity
import kotlin.concurrent.thread

/**
 * #894 Settings > Silent installs: the pairing flow for Cloud Store's own shell channel.
 *
 * One-time, on the phone: turn on Developer options > Wireless debugging, tap "Pair", then in
 * the Wireless debugging screen open "Pair device with pairing code" and type the six digits
 * into the notification this app raises (the same flow Shizuku uses). After that the channel
 * reconnects by itself on every start and boot, and installs and updates carry no prompt.
 */
@Composable
fun ShellChannelSection(ctx: Context) {
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    val status = remember(tick) {
        ShellChannels.active(ctx)?.let { "On: ${it.name()} - installs and updates carry no prompt." }
            ?: "Off: installs and updates ask for one tap per app until this is paired."
    }
    Column(Modifier.fillMaxWidth().padding(top = StoreDensity.dpValue(StoreDensity.S12).dp)) {
        Text("Silent installs", fontSize = StoreDensity.T_TITLE.sp)
        Text(status, fontSize = StoreDensity.T_CAPTION.sp)
        Text(
            "Pair once: turn on Wireless debugging, tap Pair, open \"Pair device with pairing code\" " +
                "and type the code into the notification.",
            fontSize = StoreDensity.T_CAPTION.sp,
            modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp)) {
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                runCatching { AdbPairingService.start(ctx) }
                note = "Pairing notification is up - enter the code there."
            }) { Text("Pair", fontSize = StoreDensity.T_BODY.sp) }
            OutlinedButton(onClick = {
                note = "Connecting..."
                thread(name = "cloudstore-shell-reconnect") {
                    val (_, msg) = EmbeddedAdbChannel.autoConnect(ctx)
                    note = msg; tick++
                }
            }, modifier = Modifier.padding(start = 8.dp)) { Text("Reconnect", fontSize = StoreDensity.T_BODY.sp) }
            OutlinedButton(onClick = { tick++; note = "" },
                modifier = Modifier.padding(start = 8.dp)) { Text("Refresh", fontSize = StoreDensity.T_BODY.sp) }
        }
        if (note.isNotEmpty()) Text(note, fontSize = StoreDensity.T_CAPTION.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
