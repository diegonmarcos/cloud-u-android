package com.diegonmarcos.superapp.browser

import android.app.Activity
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.diegonmarcos.superapp.bluetooth.BleDevice

/**
 * The native Web Bluetooth device chooser (#1222), in Compose: a dialog over the page that names the requesting origin and lists
 * the devices the scan finds, strongest first, live. Exactly one of pick / cancel. It is hosted in a ComposeView added to the
 * activity's content for the dialog's lifetime, so it sits under the activity's lifecycle owners and leaves no window behind.
 */
internal class ComposeChooser(private val w: WebView) : WebBleChooser {
    private val main = Handler(Looper.getMainLooper())

    override fun show(request: ChooserRequest, onPick: (BleDevice) -> Unit, onCancel: () -> Unit) {
        main.post {
            val activity = generateSequence(w.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
            if (activity == null) { onCancel(); return@post }
            val devices = mutableStateOf(request.devices)
            var done = false
            lateinit var host: ComposeView
            fun finish(pick: BleDevice?) {
                if (done) return
                done = true
                request.onUpdate = null
                (host.parent as? ViewGroup)?.removeView(host)
                if (pick != null) onPick(pick) else onCancel()
            }
            host = ComposeView(activity).apply {
                setContent { MaterialTheme(colorScheme = darkColorScheme()) { WebBleChooserDialog(request.origin, devices.value, { finish(it) }, { finish(null) }) } }
            }
            request.onUpdate = { main.post { devices.value = request.devices } }
            activity.addContentView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}

@Composable
internal fun WebBleChooserDialog(origin: String, devices: List<BleDevice>, onPick: (BleDevice) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp).testTag("webble:chooser")) {
                Text("${origin.substringAfter("://")} wants to connect to a Bluetooth device", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (devices.isEmpty()) "Scanning for devices..." else "${devices.size} found", fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(devices, key = { it.address }) { d ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 8.dp).testTag("webble:device:${d.address}")) {
                            Text(d.label, fontSize = 14.sp)
                            Text("${d.address}  ${d.rssi} dBm", fontSize = 11.sp)
                        }
                    }
                }
                TextButton(onCancel, Modifier.align(Alignment.End).testTag("webble:cancel")) { Text("Cancel") }
            }
        }
    }
}
