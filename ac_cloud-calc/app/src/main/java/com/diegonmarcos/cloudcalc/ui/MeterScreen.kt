package com.diegonmarcos.cloudcalc.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.sound.Dsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/** One meter refresh: what the screen shows. */
data class MeterReading(val levelDbfs: Double, val aWeightedDbfs: Double, val peakHz: Double, val spectrumDb: DoubleArray)

/**
 * The sound meter. RECORD_AUDIO is requested HERE and nowhere else, when this mode opens — the
 * rest of the calculator never asks for the microphone (test/test-calc-shell.sh holds that).
 */
@Composable
fun MeterMode(mode: Declarations.Mode) {
    val meter = mode.meter ?: return
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        if (!granted) {
            Text(stringResource(R.string.mic_needed))
            Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.mic_allow)) }
        } else {
            MeterLive(mode, meter)
        }
    }
}

@Composable
private fun MeterLive(mode: Declarations.Mode, meter: Declarations.Meter) {
    var reading by remember { mutableStateOf<MeterReading?>(null) }
    var error by remember { mutableStateOf("") }
    var calibration by rememberSaveable { mutableStateOf(meter.calibrationDb.toString()) }
    LaunchedEffect(meter) {
        error = withContext(Dispatchers.Default) { runCatching { record(meter) { reading = it } }.exceptionOrNull()?.message.orEmpty() }
    }
    val offset = calibration.toDoubleOrNull() ?: 0.0
    val r = reading
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    Text(
        if (r == null) "—" else "%.1f dB".format(r.levelDbfs + offset),
        style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag(CalcTags.RESULT),
    )
    if (r != null) {
        if (meter.aWeighting) Text("%.1f dB(A)".format(r.aWeightedDbfs + offset), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.peak_hz, "%.0f".format(r.peakHz)), style = MaterialTheme.typography.bodyLarge)
        // #770 a reading is a result too: score it (asked about the level at the moment of asking).
        AskAboutResult(mode.id, mode.label, "%.1f dB".format(r.levelDbfs + offset) + if (meter.aWeighting) ", %.1f dB(A)".format(r.aWeightedDbfs + offset) else "")
    }
    Text(stringResource(R.string.meter_relative), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = calibration, onValueChange = { calibration = it },
        label = { Text(stringResource(R.string.calibration)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap),
    )
    val bar = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(CalcMetrics.spectrumHeight)) {
        val s = r?.spectrumDb ?: return@Canvas
        val floor = -100.0
        // Log-spaced columns, one per pixel step: a linear axis spends 90% of the width above 2 kHz.
        val cols = max(1, (size.width / CalcMetrics.stroke.toPx()).toInt())
        val lo = Math.log10(20.0); val hi = Math.log10(meter.sampleRate / 2.0)
        for (c in 0 until cols) {
            val f = Math.pow(10.0, lo + (hi - lo) * c / cols)
            val k = (f * s.size * 2 / meter.sampleRate).toInt().coerceIn(1, s.size - 1)
            val h = ((s[k] - floor) / -floor).coerceIn(0.0, 1.0).toFloat() * size.height
            val x = c * size.width / cols
            drawLine(bar, Offset(x, size.height), Offset(x, size.height - h), strokeWidth = CalcMetrics.stroke.toPx())
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("20 Hz", style = MaterialTheme.typography.labelSmall)
        Text("%.0f kHz".format(meter.sampleRate / 2000.0), style = MaterialTheme.typography.labelSmall)
    }
}

/** Reads the microphone until the calling coroutine is cancelled (the mode is left). */
@SuppressLint("MissingPermission") // MeterMode composes this only once RECORD_AUDIO is granted
private suspend fun record(meter: Declarations.Meter, emit: (MeterReading) -> Unit) {
    val n = meter.fftSize
    val minBuf = AudioRecord.getMinBufferSize(meter.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) error("this device cannot record at ${meter.sampleRate} Hz")
    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, meter.sampleRate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, max(minBuf, n * 2))
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("the microphone could not be opened") }
    val pcm = ShortArray(n)
    var last = 0L
    try {
        rec.startRecording()
        while (currentCoroutineContext().isActive) {
            var got = 0
            while (got < n) {
                val r = rec.read(pcm, got, n - got)
                if (r < 0) error("microphone read failed ($r)")
                got += r
            }
            val now = System.currentTimeMillis()
            if (now - last < meter.refreshMs) continue
            last = now
            val level = Dsp.levelDbfs(pcm)
            val spectrum = Dsp.spectrumDb(pcm, n)
            emit(MeterReading(level, Dsp.aWeightedDbfs(level, spectrum, meter.sampleRate), Dsp.peakHz(spectrum, meter.sampleRate), spectrum))
        }
    } finally {
        runCatching { rec.stop() }
        rec.release()
    }
}
