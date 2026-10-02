package com.diegonmarcos.cloudcalc.audio

import android.content.Context
import com.diegonmarcos.cloudcalc.sound.Air
import com.diegonmarcos.cloudcalc.sound.Generator
import com.diegonmarcos.cloudcalc.sound.Session
import com.diegonmarcos.cloudcalc.sound.Wav
import java.io.File

/**
 * What the Sound tools keep: the three per-device knobs (SharedPreferences `cloud_sound`), the
 * last generated buffer (the debug loopback re-reads it), the running History session, and the
 * saved sessions — CSV and WAV in the app's own storage, exported anywhere the user picks
 * (ui/SoundScreens.kt, the system file picker; the Cloud Drive store is one tap away there).
 */
object SoundStore {
    private const val PREFS = "cloud_sound"
    private const val K_CAL = "calibration_db"
    private const val K_A4 = "a4_hz"
    private const val K_TEMP = "temperature_c"

    data class Knobs(val calibrationDb: Double, val a4Hz: Double, val temperatureC: Double) {
        val speed: Double get() = Air.speedOfSound(temperatureC)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun knobs(ctx: Context): Knobs {
        val c = SoundDecl.config
        val p = prefs(ctx)
        fun d(k: String, def: Double) = p.getString(k, null)?.toDoubleOrNull() ?: def
        return Knobs(d(K_CAL, c.calibrationDb), d(K_A4, c.a4Hz), d(K_TEMP, c.temperatureC))
    }

    fun setKnobs(ctx: Context, k: Knobs) {
        prefs(ctx).edit().putString(K_CAL, k.calibrationDb.toString()).putString(K_A4, k.a4Hz.toString())
            .putString(K_TEMP, k.temperatureC.toString()).commit()
    }

    data class Generated(val spec: Generator.Spec, val notes: List<String>, val pcm: ShortArray, val sampleRate: Int, val at: Long, val played: Boolean)

    @Volatile var lastGenerated: Generated? = null

    /** The History mode's running session, kept outside the screen so leaving the tab keeps it. */
    class Live(val sampleRate: Int, val maxSamples: Int, val maxAudio: Int, rows: Int, bands: Int) {
        val samples = ArrayList<Session.Sample>()
        val spectrogram = Spectrogram(rows, bands)
        private val audio = ShortArray(maxAudio)
        @Volatile var audioLength = 0; private set
        @Volatile var running = false
        val startedAt = System.currentTimeMillis()

        @Synchronized fun add(s: Session.Sample) {
            samples += s
            if (samples.size > maxSamples) samples.removeAt(0)
        }

        @Synchronized fun snapshot(): List<Session.Sample> = samples.toList()

        @Synchronized fun keep(pcm: ShortArray) {
            val n = minOf(pcm.size, maxAudio - audioLength)
            if (n > 0) { pcm.copyInto(audio, audioLength, 0, n); audioLength += n }
        }

        @Synchronized fun audio(): ShortArray = audio.copyOf(audioLength)
    }

    @Volatile var live: Live? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "sound").apply { mkdirs() }

    /** `session-<stamp>.csv` and, when audio was kept, `.wav`; the files written. */
    fun save(ctx: Context, session: Live): List<File> {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(session.startedAt))
        val base = File(dir(ctx), "session-$stamp")
        val out = mutableListOf(File(base.path + ".csv").apply { writeText(Session.csv(session.snapshot())) })
        val audio = session.audio()
        if (audio.isNotEmpty()) out += File(base.path + ".wav").apply { writeBytes(Wav.encode(audio, session.sampleRate)) }
        return out
    }

    fun sessions(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()
}
