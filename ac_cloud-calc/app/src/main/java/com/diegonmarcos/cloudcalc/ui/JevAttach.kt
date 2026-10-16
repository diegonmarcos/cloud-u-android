package com.diegonmarcos.cloudcalc.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.decide.Attachment
import com.diegonmarcos.cloudcalc.decide.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AttachTags {
    const val FILES = "jev_attach_files"
    const val IMAGES = "jev_attach_images"
    const val LISTEN = "jev_attach_listen"
    const val CAMERA = "jev_attach_camera"
    const val PANEL = "jev_attach_panel"
    const val RATIONALE = "jev_attach_rationale"
    fun chip(i: Int) = "jev_attach_chip_$i"
}

private fun displayName(ctx: android.content.Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"

/**
 * Under the Jev input: attach files, images (the system photo picker, no storage permission), listen (speech-to-text into
 * the input) and the camera (a live preview, a photo). Each shows what it needs and why BEFORE the permission dialog; the
 * attached things are chips that can be removed. The microphone and the camera are asked for through MicGate / CameraGate.
 */
@Composable
fun AttachBar(attachments: SnapshotStateList<Attachment>, onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf("") }   // "" | "listen" | "camera"

    fun add(uris: List<Uri>) = scope.launch {
        for (u in uris) {
            val a = withContext(Dispatchers.IO) { runCatching { Attachments.read(ctx.contentResolver, u, displayName(ctx, u)) }.getOrNull() }
            if (a != null) attachments.add(a)
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { add(it) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
        OutlinedButton(onClick = { files.launch(arrayOf("*/*")) }, modifier = Modifier.testTag(AttachTags.FILES)) { Text(stringResource(R.string.attach_files)) }
        OutlinedButton(onClick = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.testTag(AttachTags.IMAGES)) { Text(stringResource(R.string.attach_images)) }
        OutlinedButton(onClick = { panel = if (panel == "listen") "" else "listen" }, modifier = Modifier.testTag(AttachTags.LISTEN)) { Text(stringResource(R.string.attach_listen)) }
        OutlinedButton(onClick = { panel = if (panel == "camera") "" else "camera" }, modifier = Modifier.testTag(AttachTags.CAMERA)) { Text(stringResource(R.string.attach_camera)) }
    }
    if (attachments.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
        itemsIndexed(attachments) { i, a ->
            AssistChip(
                onClick = { attachments.removeAt(i) },
                label = { Text(a.name + " ✕", maxLines = 1) },
                modifier = Modifier.testTag(AttachTags.chip(i)),
            )
        }
    }
    if (panel.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag(AttachTags.PANEL)) {
        Text(
            stringResource(if (panel == "listen") R.string.attach_listen_why else R.string.attach_camera_why),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(AttachTags.RATIONALE),
        )
        if (panel == "listen") MicGate { ListenPanel(onText) { panel = "" } }
        else CameraGate {
            PhotoSource { bmp ->
                attachments.add(Attachments.image("photo-${attachments.size + 1}.jpg", bmp))
                panel = ""
            }
        }
        TextButton(onClick = { panel = "" }) { Text(stringResource(R.string.sheet_cancel)) }
    }
}

/** Speech-to-text with the system recognizer: partial words show as you speak, the result goes into the input. */
@Composable
private fun ListenPanel(onText: (String) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val available = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
    var heard by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val rec = remember { if (available) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    DisposableEffect(rec) {
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status = ctx.getString(R.string.attach_listening) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { status = ctx.getString(R.string.attach_listen_error, error) }
            override fun onPartialResults(partialResults: Bundle?) {
                heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onText(text)
                onDone()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { rec?.destroy() }
    }
    Column(Modifier.fillMaxWidth()) {
        if (rec == null) Text(stringResource(R.string.attach_listen_none))
        else OutlinedButton(onClick = {
            heard = ""
            rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }) { Text(stringResource(R.string.attach_start)) }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall)
        if (heard.isNotBlank()) Text(heard)
    }
}
