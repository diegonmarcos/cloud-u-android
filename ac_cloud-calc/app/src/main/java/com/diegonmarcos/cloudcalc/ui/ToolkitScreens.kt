@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.audio.Mic
import com.diegonmarcos.cloudcalc.audio.WavWriter
import com.diegonmarcos.superapp.light.Light
import com.diegonmarcos.superapp.light.LightPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The test tags the Toolkit pages are driven by. */
object ToolkitTags {
    const val RECORD = "recorder_record"
    const val ELAPSED = "recorder_elapsed"
    const val LIST = "recorder_list"
    const val TRACKER_MISSING = "tracker_missing"
    fun trackerPage(target: String) = "tracker_open_$target"
    fun play(name: String) = "recorder_play_$name"
    fun share(name: String) = "recorder_share_$name"
    fun delete(name: String) = "recorder_delete_$name"
}

// ── Light: libs:light's page, the one the SuperApp's Primary Tools Light opens ───────────────────

@Composable
fun LightMode(@Suppress("UNUSED_PARAMETER") mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val engine = remember { Light.engine(ctx) }
    LightPanel(engine, textColor = MaterialTheme.colorScheme.onSurface, background = Color.Transparent)
}

// ── Voice Recorder ───────────────────────────────────────────────────────────────────────────────

@Composable
fun RecorderMode(mode: Declarations.Mode) = MicGate(R.string.recorder_why) { RecorderBody(mode) }

private fun recordings(dir: File): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }.orEmpty().sortedByDescending { it.lastModified() }

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Composable
private fun RecorderBody(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val spec = remember(mode.id) { JSONObject(mode.spec) }
    val rate = spec.getInt("sample_rate")
    val maxMs = spec.getInt("max_minutes") * 60_000L
    val dir = remember(mode.id) { File(ctx.filesDir, spec.getString("dir")).apply { mkdirs() } }
    var files by remember(mode.id) { mutableStateOf(recordings(dir)) }
    var job by remember(mode.id) { mutableStateOf<Job?>(null) }
    var elapsedMs by remember(mode.id) { mutableLongStateOf(0L) }
    var error by remember(mode.id) { mutableStateOf("") }
    var playing by remember(mode.id) { mutableStateOf<String?>(null) }
    val player = remember(mode.id) { arrayOfNulls<MediaPlayer>(1) }
    val scope = rememberCoroutineScope()

    fun stopPlay() {
        player[0]?.let { runCatching { it.stop() }; it.release() }
        player[0] = null
        playing = null
    }
    DisposableEffect(mode.id) { onDispose { job?.cancel(); stopPlay() } }

    fun start() {
        error = ""
        elapsedMs = 0L
        stopPlay()
        val target = File(dir, "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".wav")
        val j = scope.launch(Dispatchers.IO) {
            try {
                // The writer is closed (its header patched) however the recording ends: Stop, the time limit, or leaving the page.
                WavWriter(target, rate).use { w ->
                    withTimeoutOrNull(maxMs) {
                        Mic.stream(ctx, rate, rate / 10) { pcm -> w.write(pcm); elapsedMs = w.samples * 1000 / rate }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                target.delete()
            }
        }
        j.invokeOnCompletion { files = recordings(dir); job = null }
        job = j
    }

    fun play(f: File) {
        stopPlay()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(f.path)
            mp.prepare()
            mp.setOnCompletionListener { stopPlay() }
            mp.start()
            player[0] = mp
            playing = f.name
        } catch (e: Exception) {
            mp.release()
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun share(f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("audio/x-wav").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.recorder_share_title)))
    }

    val recording = job != null
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        Text(
            if (recording) stringResource(R.string.recorder_recording, clock(elapsedMs)) else clock(0),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag(ToolkitTags.ELAPSED),
        )
        Button(onClick = { if (recording) job?.cancel() else start() }, modifier = Modifier.fillMaxWidth().testTag(ToolkitTags.RECORD)) {
            Text(stringResource(if (recording) R.string.recorder_stop else R.string.recorder_record))
        }
        if (error.isNotEmpty()) Text(stringResource(R.string.recorder_error, error), color = MaterialTheme.colorScheme.error)
        if (files.isEmpty()) Text(stringResource(R.string.recorder_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(ToolkitTags.LIST), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(files, key = { it.name }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                        Text(f.name.removePrefix("rec_").removeSuffix(".wav"), style = MaterialTheme.typography.titleSmall)
                        Text(clock(WavWriter.seconds(f.length(), rate) * 1000) + " · " + "%.1f MB".format(f.length() / 1_048_576.0), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                            OutlinedButton(onClick = { if (playing == f.name) stopPlay() else play(f) }, modifier = Modifier.testTag(ToolkitTags.play(f.name))) {
                                Text(stringResource(if (playing == f.name) R.string.recorder_pause else R.string.recorder_play))
                            }
                            OutlinedButton(onClick = { share(f) }, modifier = Modifier.testTag(ToolkitTags.share(f.name))) { Text(stringResource(R.string.recorder_share)) }
                            OutlinedButton(onClick = { if (playing == f.name) stopPlay(); f.delete(); files = recordings(dir) }, modifier = Modifier.testTag(ToolkitTags.delete(f.name))) {
                                Text(stringResource(R.string.recorder_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tracker: a deep link into Cloud Navigation, not a second tracker ──────────────────────────────

@Composable
fun TrackerLinkMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val spec = remember(mode.id) { JSONObject(mode.spec) }
    val extra = spec.getString("extra")
    val pages = remember(mode.id) { spec.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
    val launch = remember(mode.id) { ctx.packageManager.getLaunchIntentForPackage(BuildConfig.TRACKER_PACKAGE) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        Text(stringResource(R.string.tracker_why))
        if (launch == null) {
            Text(stringResource(R.string.tracker_missing), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ToolkitTags.TRACKER_MISSING))
        } else {
            pages.forEach { p ->
                val target = p.getString("target")
                Button(
                    onClick = { ctx.startActivity(Intent(launch).putExtra(extra, target)) },
                    modifier = Modifier.fillMaxWidth().testTag(ToolkitTags.trackerPage(target)),
                ) { Text(p.getString("label")) }
            }
        }
    }
}
