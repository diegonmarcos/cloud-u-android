package com.diegonmarcos.superapp.voice

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.translate.Translator
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

private const val SAMPLE_RATE = 16000
private const val POINTS = 96 // oscilloscope sample count per frame

/**
 * Offline voice dictation bar, hosted INSIDE the keyboard frame (added as the
 * first child of the vertical LinearLayout that owns strip_container — the exact
 * pattern the translate bar uses). NOT a PopupWindow: that avoids all the
 * focus / touch / mic-lifecycle bugs. It sits directly above the keyboard, the
 * keys stay usable below, and the mic key TOGGLES it (tap = open, tap = close).
 *
 * The mic lifecycle is tied to visibility: [onShown] starts capture + the engine,
 * [onHidden] stops them and RELEASES the mic — so closing always frees the mic.
 *
 * Audio capture runs in VoiceBarView (the foreground IME holds RECORD_AUDIO).
 * Recognition is delegated to [VoiceEngines.client]:
 *   - cloud-keyboard-libs (companion APK): LocalVoiceEngineClient — Vosk in-process
 *   - cloud-keyboard (standalone APK):     AidlVoiceEngineClient  — cross-process AIDL
 *
 * 2026-10-16 owner decision: all apps must be Compose. The bar is a Compose view under
 * CloudKitTheme (libs:ui-kit's IME host, which gives the input-method window the owners Compose
 * needs); the oscilloscope is drawn on a Compose canvas. LatinIME still constructs it with the
 * service context and drives it through [bind] / [onShown] / [onHidden].
 */
class VoiceBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : KitImeComposeView(context, attrs) {

    private val main = Handler(Looper.getMainLooper())

    // What the bar shows.
    private var hint by mutableStateOf("Listening…")
    private var editorText by mutableStateOf("")
    private var targetIndex by mutableIntStateOf(0)
    private var wave by mutableStateOf(FloatArray(0))
    private var waveTick by mutableIntStateOf(0)

    /** SAM interface so LatinIME can pass `this::getCurrentInputConnection`
     *  (a Java method reference). A Kotlin `() -> InputConnection?` would NOT
     *  accept the Java method ref — same pattern as TranslateBarView.IcProvider. */
    fun interface IcProvider { fun get(): InputConnection? }

    private var icSupplier: IcProvider? = null
    private var langTag: String = "en-us"
    private var onCloseRequest: Runnable? = null

    // Capture state — owned by VoiceBarView so the mic lives here (foreground IME).
    @Volatile private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    // Data-driven translate targets (build.json::voice.translate_targets). The
    // chip cycles through them; Translate uses the selected one. Lets you pick a
    // target different from the dictated language (same-language = no-op).
    private val targets: List<String> =
        BuildConfig.VOICE_TRANSLATE_TARGETS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("en") }

    /** Wire the bar to the IME. [ic] supplies the live InputConnection, [langTag]
     *  is the active subtype tag, [onClose] hides the bar (→ onHidden stops mic). */
    fun bind(ic: IcProvider, langTag: String, onClose: Runnable) {
        this.icSupplier = ic
        this.langTag = langTag
        this.onCloseRequest = onClose
    }

    /** Called by LatinIME when the bar becomes visible — start dictation. */
    fun onShown() {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // #1274 an IME has no activity: PermsLink opens the Permissions page libs:perms hosts in this app.
            hint = "Permissions needed: Microphone, then tap the mic key again"
            com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "voice", listOf("microphone"))
            onCloseRequest?.run() // close; user re-taps after granting
            return
        }
        val engine = VoiceEngines.client
        if (engine == null) {
            hint = "Voice unavailable — install cloud-keyboard-libs companion"
            return
        }
        editorText = ""
        hint = "Preparing voice engine…"
        // Propagate the current language tag to the engine if it supports it.
        if (engine is LanguageTagAware) engine.languageTag = langTag
        engine.start(
            onPartial = { t -> main.post { hint = if (t.isEmpty()) "Listening…" else "… $t" } },
            onFinal   = { t -> main.post { hint = "Listening…"; appendFinal(t) } },
            onError   = { msg -> main.post { hint = "Voice error: $msg"; stopCapture() } }
        )
        startCapture(engine)
        hint = "Listening…"
    }

    /** Called by LatinIME when the bar is hidden — STOP and release the mic. */
    fun onHidden() {
        stopCapture()
        VoiceEngines.client?.stop()
    }

    private fun startCapture(engine: VoiceEngineClient) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) minBuf * 2 else SAMPLE_RATE
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            main.post { hint = "Microphone unavailable" }
            rec.release()
            return
        }
        record = rec
        capturing = true
        captureThread = thread(name = "vosk-capture", isDaemon = true) {
            val buffer = ByteArray(bufSize)
            rec.startRecording()
            while (capturing) {
                val r = record ?: break
                val n = try { r.read(buffer, 0, buffer.size) } catch (_: Exception) { break }
                if (n <= 0) continue
                val wf = waveform(buffer, n)
                main.post { setWaveform(wf) }
                engine.feed(buffer.copyOf(n), n)
            }
        }
    }

    private fun stopCapture() {
        capturing = false
        val r = record ?: return
        record = null
        runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        runCatching { r.release() }
        captureThread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        captureThread = null
    }

    private fun appendFinal(text: String) {
        if (text.isEmpty()) return
        editorText = if (editorText.isBlank()) text else "${editorText.trimEnd()} $text"
    }

    private fun text(): String = editorText.trim()

    private fun copy() {
        val t = text(); if (t.isEmpty()) return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("dictation", t))
        hint = "Copied"
    }

    private fun translate() {
        val t = text(); if (t.isEmpty()) { toast("Nothing to translate"); return }
        val target = targets[targetIndex]
        hint = "Translating → $target…"
        Translator.liveTranslate(context, t, target) { result ->
            when {
                result == null -> {
                    hint = "Listening…"
                    toast("No translation — text may already be '$target'. Tap 🌐 to pick another target.")
                }
                result.isBlank() -> hint = "Listening…"
                else -> { editorText = result; hint = "Listening…" }
            }
        }
    }

    private fun cycleTarget() {
        targetIndex = (targetIndex + 1) % targets.size
        toast("Translate to: ${targets[targetIndex]}")
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun insert() {
        val t = text()
        if (t.isNotEmpty()) icSupplier?.get()?.commitText("$t ", 1)
        onCloseRequest?.run()
    }

    /** Temporal smoothing of the incoming scope frame, then a redraw. */
    private fun setWaveform(s: FloatArray) {
        val cur = if (wave.size != s.size) FloatArray(s.size) else wave
        val next = FloatArray(s.size) { i -> cur[i] * 0.4f + s[i] * 0.6f }
        wave = next
        waveTick++
    }

    private fun res(id: Int) = ContextCompat.getColor(context, id)

    override fun palette(): KitPalette = KitPalette.fromArgb(
        surface = res(R.color.voice_panel),
        surfaceSelected = res(R.color.voice_field),
        textPrimary = res(R.color.voice_text),
        textSecondary = res(R.color.voice_muted),
        accent = res(R.color.voice_accent),
        hairline = res(R.color.voice_neutral),
        tileInk = res(R.color.voice_on_accent),
    )

    @Composable
    override fun Body() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎤", fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
                Text("Voice", color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp))
                Scope(Modifier.weight(1f).height(40.dp))
                Pill("🌐 ${targets[targetIndex]}", p.hairline, p.textPrimary,
                    Modifier.padding(start = 8.dp).height(36.dp)) { cycleTarget() }
            }
            Text(hint, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            BasicTextField(
                value = editorText, onValueChange = { editorText = it },
                textStyle = TextStyle(color = p.textPrimary, fontSize = 17.sp),
                cursorBrush = SolidColor(p.textPrimary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surfaceSelected)
                        .padding(12.dp)) {
                        if (editorText.isEmpty()) {
                            Text("Speak — your words appear here…", color = colorResource(R.color.voice_hint),
                                fontSize = 17.sp)
                        }
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Pill("Copy", p.hairline, p.textPrimary, Modifier.weight(1f).padding(horizontal = 3.dp).height(44.dp)) { copy() }
                Pill("Translate", p.hairline, p.textPrimary, Modifier.weight(1f).padding(horizontal = 3.dp).height(44.dp)) { translate() }
                Pill("Insert", p.accent, p.tileInk, Modifier.weight(1f).padding(horizontal = 3.dp).height(44.dp)) { insert() }
                Pill("✕", colorResource(R.color.voice_danger), p.textPrimary,
                    Modifier.weight(0.6f).padding(horizontal = 3.dp).height(44.dp)) { onCloseRequest?.run() }
            }
        }
    }

    @Composable
    private fun Pill(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
        Box(modifier.clip(RoundedCornerShape(18.dp)).background(bg).clickable { onClick() }
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = fg, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }

    /**
     * Oscilloscope-style waveform: draws the actual captured audio wave as a
     * smooth gradient line (with a soft glow + faint mirror) across the strip,
     * scrolling/wiggling with the voice — a synthesizer/scope look, not bars.
     */
    @Composable
    private fun Scope(modifier: Modifier) {
        val gain = 2.8f
        val gradient = listOf(
            colorResource(R.color.voice_wave_start), colorResource(R.color.voice_wave_mid),
            colorResource(R.color.voice_wave_end))
        val baseline = colorResource(R.color.voice_wave_baseline)
        Canvas(modifier) {
            waveTick // read, so every smoothed frame redraws
            val smooth = wave
            val w = size.width; val h = size.height; val cy = h / 2f
            val brush = Brush.horizontalGradient(gradient, startX = 0f, endX = w)
            fun px(v: Float) = v * density
            drawLine(baseline, Offset(0f, cy), Offset(w, cy), strokeWidth = px(1f))
            val n = smooth.size
            val line = Stroke(px(2.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (n < 2) {
                drawLine(brush, Offset(0f, cy), Offset(w, cy), strokeWidth = px(2.2f), cap = StrokeCap.Round)
                return@Canvas
            }
            val path = Path(); val mirrorPath = Path()
            val dx = w / (n - 1); val amp = h * 0.46f
            for (i in 0 until n) {
                val x = i * dx
                val v = (smooth[i] * gain).coerceIn(-1f, 1f)
                val y = cy - v * amp
                if (i == 0) { path.moveTo(x, y); mirrorPath.moveTo(x, cy + v * amp) }
                else { path.lineTo(x, y); mirrorPath.lineTo(x, cy + v * amp) }
            }
            drawPath(mirrorPath, brush, alpha = 38f / 255f, style = Stroke(px(1.6f), cap = StrokeCap.Round))
            drawPath(path, brush, alpha = 60f / 255f,
                style = Stroke(px(7f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, brush, style = line)
        }
    }

    /**
     * Downsample a PCM16LE buffer to [POINTS] signed, normalised (-1..1) samples
     * by taking the peak (most-extreme) sample in each slice.
     */
    private fun waveform(buf: ByteArray, len: Int): FloatArray {
        val total = len / 2
        val out = FloatArray(POINTS)
        if (total <= 0) return out
        val step = max(1, total / POINTS)
        for (p in 0 until POINTS) {
            var peak = 0
            val start = p * step
            var j = 0
            while (j < step && start + j < total) {
                val idx = (start + j) * 2
                val s = ((buf[idx].toInt() and 0xff) or (buf[idx + 1].toInt() shl 8)).toShort().toInt()
                if (abs(s) > abs(peak)) peak = s
                j++
            }
            out[p] = (peak / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }
}

/** Implemented by engines that can be told which language to transcribe. */
interface LanguageTagAware {
    var languageTag: String?
}
