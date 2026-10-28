package com.diegonmarcos.superapp.browser

import android.app.Activity
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/**
 * The native chooser and prompts of Web Serial, WebUSB and Web NFC (#1225), in Compose, hosted like Web Bluetooth's chooser: a
 * ComposeView added to the activity's content for the dialog's lifetime, so it sits under the activity's lifecycle owners and leaves
 * no window behind. Every dialog names the requesting origin, and ends in exactly one of allow / pick / cancel.
 */
internal class ComposeHwChooser(private val w: WebView) : WebHwChooser {
    private val main = Handler(Looper.getMainLooper())

    private fun activity(): Activity? = generateSequence(w.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

    /** Adds [content] to the activity; returns what removes it. */
    private fun host(activity: Activity, content: @Composable (dismiss: () -> Unit) -> Unit): () -> Unit {
        lateinit var view: ComposeView
        val remove = { main.post { (view.parent as? ViewGroup)?.removeView(view); Unit }; Unit }
        view = ComposeView(activity).apply { setContent { MaterialTheme(colorScheme = darkColorScheme()) { content(remove) } } }
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return remove
    }

    override fun pick(request: HwChoiceRequest, onPick: (String) -> Unit, onCancel: () -> Unit) {
        main.post {
            val a = activity()
            if (a == null) { onCancel(); return@post }
            val items = mutableStateOf(request.items)
            var done = false
            var remove: () -> Unit = {}
            fun finish(key: String?) {
                if (done) return
                done = true
                request.onUpdate = null
                remove()
                if (key != null) onPick(key) else onCancel()
            }
            request.onUpdate = { main.post { items.value = request.items } }
            remove = host(a) { WebHwChooserDialog(request.origin, request.kind, items.value, { finish(it) }, { finish(null) }) }
        }
    }

    override fun allowNfc(origin: String, writing: Boolean, onAllow: () -> Unit, onDeny: () -> Unit) {
        main.post {
            val a = activity()
            if (a == null) { onDeny(); return@post }
            var done = false
            var remove: () -> Unit = {}
            fun finish(allow: Boolean) { if (done) return; done = true; remove(); if (allow) onAllow() else onDeny() }
            remove = host(a) { WebNfcPromptDialog(origin, writing, { finish(true) }, { finish(false) }) }
        }
    }

    override fun tapTag(origin: String, onCancel: () -> Unit): () -> Unit {
        var remove: () -> Unit = {}
        var done = false
        main.post {
            val a = activity()
            if (a == null) { done = true; onCancel(); return@post }
            remove = host(a) { WebNfcTapDialog(origin) { if (!done) { done = true; remove(); onCancel() } } }
        }
        return { main.post { if (!done) { done = true; remove() } } }
    }
}

@Composable
internal fun WebHwChooserDialog(origin: String, kind: String, choices: List<HwChoice>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val what = if (kind == "serial") "a serial port" else "a USB device"
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp).testTag("webhw:chooser:$kind")) {
                Text("${origin.substringAfter("://")} wants to connect to $what", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (choices.isEmpty()) "No device is plugged in. Connect one with a USB cable or OTG adapter." else "${choices.size} found",
                    fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp).testTag("webhw:count"))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(choices, key = { it.key }) { d ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(d.key) }.padding(vertical = 8.dp).testTag("webhw:device:${d.key}")) {
                            Text(d.title, fontSize = 14.sp)
                            Text(d.subtitle, fontSize = 11.sp)
                        }
                    }
                }
                TextButton(onCancel, Modifier.align(Alignment.End).testTag("webhw:cancel")) { Text("Cancel") }
            }
        }
    }
}

@Composable
internal fun WebNfcPromptDialog(origin: String, writing: Boolean, onAllow: () -> Unit, onDeny: () -> Unit) {
    Dialog(onDismissRequest = onDeny) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp).testTag("webhw:nfc:prompt")) {
                Text("${origin.substringAfter("://")} wants to ${if (writing) "read and write" else "read"} NFC tags", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("The site can use NFC while it is open. You can remove this under Settings > Site data.", fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDeny, Modifier.testTag("webhw:nfc:deny")) { Text("Block") }
                    TextButton(onAllow, Modifier.testTag("webhw:nfc:allow")) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
internal fun WebNfcTapDialog(origin: String, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp).testTag("webhw:nfc:tap")) {
                Text("Hold an NFC tag to the back of the phone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${origin.substringAfter("://")} will write to it.", fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                TextButton(onCancel, Modifier.align(Alignment.End).testTag("webhw:nfc:cancel")) { Text("Cancel") }
            }
        }
    }
}
