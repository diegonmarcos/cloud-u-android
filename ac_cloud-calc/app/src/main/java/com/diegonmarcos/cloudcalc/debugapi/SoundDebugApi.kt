package com.diegonmarcos.cloudcalc.debugapi

import android.content.Context
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.audio.Mic
import com.diegonmarcos.cloudcalc.audio.Player
import com.diegonmarcos.cloudcalc.audio.SoundDecl
import com.diegonmarcos.cloudcalc.audio.SoundFlow
import com.diegonmarcos.cloudcalc.audio.SoundStore
import com.diegonmarcos.cloudcalc.sound.Analysis
import com.diegonmarcos.cloudcalc.sound.Dsp
import com.diegonmarcos.cloudcalc.sound.Generator
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.sound.SoundCapture
import com.diegonmarcos.superapp.sound.SoundConfig
import com.diegonmarcos.superapp.sound.SoundPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Sound tools on the fleet debug API (#772), a loopback self-test that works with the screen
 * locked:
 *
 *   /api/sound/generate?f=440&ms=500        play a guarded tone (kind, wave, f2, amp, colour, log
 *                                           as the Generator screen) and keep its samples
 *   /api/sound/analyze?ms=1000              record the microphone and report the detected
 *                                           frequency, note, level, events; source=generator
 *                                           re-reads the last generated buffer instead
 *   /api/sound/status                       permission, knobs, last generation, History session,
 *                                           saved sessions
 *   /api/sound/classify?test=tone           #798 "What is this sound?" ON DEVICE (YAMNet, the shared
 *                                           sound engine): ms=<n> from the microphone, path=<wav>
 *                                           from a file, test=tone|silence|noise a synthetic clip,
 *                                           source=generator the last generated buffer
 *
 * Same path as the screens (SoundFlow). Android silences a background app's microphone: analyze
 * says `silenced: true` rather than reporting the frequency of nothing.
 */
object SoundDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route(
            BuildConfig.DEBUG_API_SOUND_GROUP,
            listOf(
                AppDebugServer.Op("generate", "f=<Hz>&ms=<duration>&kind=<tone|sweep|noise|dual|beats>&wave=<sine|square|triangle|saw>&f2=<Hz>&amp=<0..1>&colour=<white|pink>&log=<0|1>", "play a tone through the safe-volume guard and keep its samples"),
                AppDebugServer.Op("analyze", "ms=<duration>&source=<mic|generator>", "record (or re-read the last generated tone) and report frequency, note, level, events"),
                AppDebugServer.Op("status", "", "microphone permission, knobs, last generation, History session, saved sessions"),
                AppDebugServer.Op("classify", "ms=<n> | path=<wav> | test=<${SoundCapture.TESTS.joinToString("|")}> | source=generator",
                    "#798 identify a sound on device through the shared engine (YAMNet): labels, timeline, peak, engine readiness"),
            ),
        ) { op, q ->
            when (op) {
                "generate" -> generate(app, q).toString()
                "analyze" -> analyze(app, q).toString()
                "status" -> status(app).toString()
                "classify" -> classify(app, q).toString()
                else -> null
            }
        }
    }

    fun generate(ctx: Context, q: Map<String, String>): JSONObject {
        val d = SoundDecl.config.generatorDefaults
        val spec = Generator.Spec(
            kind = q["kind"] ?: d.kind,
            wave = q["wave"] ?: d.wave,
            f1 = q["f"]?.toDoubleOrNull() ?: d.f1,
            f2 = q["f2"]?.toDoubleOrNull() ?: d.f2,
            amplitude = q["amp"]?.toDoubleOrNull() ?: d.amplitude,
            ms = q["ms"]?.toIntOrNull() ?: d.ms,
            colour = q["colour"] ?: "white",
            logSweep = q["log"] != "0",
        )
        val g = runCatching { SoundFlow.generate(ctx, spec, play = true) }
            .getOrElse { return JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
        return JSONObject().put("ok", true).put("spec", spec(g.spec)).put("notes", JSONArray(g.notes))
            .put("samples", g.pcm.size).put("sample_rate", g.sampleRate).put("played", g.played)
            .put("device_volume", Math.round(Player.deviceVolume(ctx) * 100) / 100.0)
    }

    fun analyze(ctx: Context, q: Map<String, String>): JSONObject {
        val cfg = SoundDecl.config
        val source = q["source"] ?: "mic"
        val ms = (q["ms"]?.toIntOrNull() ?: cfg.recordMs).coerceIn(50, cfg.maxRecordMs)
        val (pcm, sr) = when (source) {
            "generator" -> SoundStore.lastGenerated?.let { it.pcm to it.sampleRate }
                ?: return JSONObject().put("ok", false).put("error", "nothing generated yet — call generate first")
            "mic" -> runCatching { Mic.record(ctx, cfg.sampleRate, ms) to cfg.sampleRate }
                .getOrElse { return JSONObject().put("ok", false).put("source", source).put("error", it.message ?: it.javaClass.simpleName) }
            else -> return JSONObject().put("ok", false).put("error", "source must be mic or generator")
        }
        val knobs = SoundStore.knobs(ctx)
        val r = SoundFlow.analyze(pcm, sr, cfg)
        // An all-zero capture is Android silencing a background app's microphone, not a quiet room.
        val silenced = source == "mic" && Dsp.peakDbfs(pcm) <= Dsp.FLOOR_DB
        return SoundFlow.summary(r, knobs).put("ok", true).put("source", source).put("ms", pcm.size * 1000L / sr)
            .put("silenced", silenced).put("onset_times_s", JSONArray(r.onsets.map { Math.round(it * 1000) / 1000.0 }))
            .put("event_kinds", JSONArray(r.events.map { it.kind })).put("detected", detected(r))
    }

    /** #798 the on-device route of "What is this sound?", the same call the screen makes. */
    fun classify(ctx: Context, q: Map<String, String>): JSONObject {
        val cfg = SoundDecl.config
        val path = q["path"]?.takeIf { it.isNotBlank() }
        val test = q["test"]?.takeIf { it.isNotBlank() }
        val (r, source, pcm) = when {
            path != null -> {
                val f = if (path.startsWith("/")) File(path) else File(ctx.filesDir, path)
                if (!f.canRead()) return JSONObject().put("ok", false).put("error", "cannot read ${f.path}")
                Triple(SoundFlow.identifyOnDevice(ctx, f), "file:${f.path}", null)
            }
            test != null -> {
                if (test !in SoundCapture.TESTS) return JSONObject().put("ok", false).put("error", "test must be one of ${SoundCapture.TESTS}")
                val clip = SoundCapture.testClip(test, SoundConfig.captureMs(q["ms"]?.toLongOrNull()), cfg.sampleRate)
                Triple(SoundFlow.identifyOnDevice(ctx, clip, cfg.sampleRate), "test:$test", clip)
            }
            q["source"] == "generator" -> {
                val g = SoundStore.lastGenerated ?: return JSONObject().put("ok", false).put("error", "nothing generated yet — call generate first")
                Triple(SoundFlow.identifyOnDevice(ctx, g.pcm, g.sampleRate), "generator", g.pcm)
            }
            q["ms"] != null -> {
                val ms = (q["ms"]?.toIntOrNull() ?: cfg.recordMs).coerceIn(50, cfg.maxRecordMs)
                val clip = runCatching { Mic.record(ctx, cfg.sampleRate, ms) }
                    .getOrElse { return JSONObject().put("ok", false).put("source", "mic").put("error", it.message ?: it.javaClass.simpleName) }
                Triple(SoundFlow.identifyOnDevice(ctx, clip, cfg.sampleRate), "mic", clip)
            }
            else -> return JSONObject().put("ok", false).put("error", "one of ms=<n>, path=<wav>, test=<${SoundCapture.TESTS.joinToString("|")}> or source=generator is required")
        }
        return JSONObject().put("ok", r.ok).put("route", r.route).put("source", source).put("model", r.model).put("latency_ms", r.latencyMs)
            .put("labels", JSONArray().apply { r.labels.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } })
            .put("segments", JSONArray().apply { r.segments.forEach { put(JSONObject().put("label", it.label).put("p", it.p).put("start_ms", it.startMs).put("end_ms", it.endMs)) } })
            .put("peak", pcm?.let { SoundCapture.peak(it) } ?: JSONObject.NULL)
            .put("error", r.error ?: JSONObject.NULL)
            .put("chosen_route", SoundPrefs.route(ctx)).put("engine", SoundFlow.engineStatus(ctx) ?: "ready")
    }

    /** One line a human reads first: the frequency and what kind of sound carried it. */
    private fun detected(r: Analysis.Result): String {
        if (r.events.isEmpty()) return "nothing above the noise floor"
        val f = r.f0 ?: r.peakHz
        return "%.1f Hz, %s".format(java.util.Locale.ROOT, f, r.events.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.value} ${it.key}" })
    }

    fun status(ctx: Context): JSONObject {
        val k = SoundStore.knobs(ctx)
        val g = SoundStore.lastGenerated
        val live = SoundStore.live
        return JSONObject()
            .put("mic_granted", Mic.granted(ctx))
            .put("knobs", JSONObject().put("calibration_db", k.calibrationDb).put("a4_hz", k.a4Hz).put("temperature_c", k.temperatureC)
                .put("speed_of_sound_m_s", Math.round(k.speed * 10) / 10.0))
            .put("last_generated", g?.let { JSONObject().put("spec", spec(it.spec)).put("notes", JSONArray(it.notes)).put("at", it.at).put("samples", it.pcm.size) } ?: JSONObject.NULL)
            .put("history", live?.let { JSONObject().put("running", it.running).put("samples", it.snapshot().size).put("audio_seconds", it.audioLength.toDouble() / it.sampleRate) } ?: JSONObject.NULL)
            .put("sessions", JSONArray(SoundStore.sessions(ctx).map { JSONObject().put("name", it.name).put("bytes", it.length()) }))
            .put("model", SoundFlow.model(ctx))
    }

    private fun spec(s: Generator.Spec) = JSONObject().put("kind", s.kind).put("wave", s.wave).put("f1", s.f1).put("f2", s.f2)
        .put("amplitude", s.amplitude).put("ms", s.ms).put("colour", s.colour).put("log_sweep", s.logSweep)
}
