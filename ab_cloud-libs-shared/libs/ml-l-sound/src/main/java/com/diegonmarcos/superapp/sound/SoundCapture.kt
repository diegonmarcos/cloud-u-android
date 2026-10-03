package com.diegonmarcos.superapp.sound

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.sin

/**
 * #798 the two ways a consumer gets a clip without a file: the microphone, in THIS app's process
 * (RECORD_AUDIO is granted per package, so recording cannot move into the engine), and a
 * synthetic test clip for the debug routes, so a screen-locked check needs neither a microphone
 * nor a file. Both apps record and test the same way, which is why this lives in the contract.
 */
object SoundCapture {
    /** The synthetic clips [testClip] makes, the same ones lib-apks/test/test-ml-goldens.sh holds YAMNet to. */
    val TESTS = listOf("tone", "silence", "noise")

    /**
     * [ms] of 16-bit mono from the microphone at [rate] Hz. The caller holds RECORD_AUDIO; a
     * backgrounded app is handed silence by Android, which the answer's labels then say.
     */
    @SuppressLint("MissingPermission")
    fun record(ms: Long, rate: Int = SoundConfig.sampleRate()): ShortArray {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "this device cannot record 16-bit mono at $rate Hz" }
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2))
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "the microphone could not be opened" }
            val out = ShortArray((rate * ms / 1000).toInt())
            rec.startRecording()
            var n = 0
            while (n < out.size) {
                val r = rec.read(out, n, out.size - n)
                if (r <= 0) break
                n += r
            }
            return if (n == out.size) out else out.copyOf(n)
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** A synthetic clip of [ms] at [rate] Hz: a 1 kHz tone at half scale, digital silence, or seeded white noise. */
    fun testClip(kind: String, ms: Long, rate: Int = SoundConfig.sampleRate()): ShortArray {
        require(kind in TESTS) { "test must be one of $TESTS" }
        val n = (rate * ms / 1000).toInt()
        val rnd = java.util.Random(7)
        return ShortArray(n) { i ->
            when (kind) {
                "tone" -> (0.5 * sin(2 * PI * 1000 * i / rate) * 32767).toInt().toShort()
                "noise" -> (rnd.nextGaussian() * 0.3 * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort()
                else -> 0
            }
        }
    }

    /** The clip's peak as a fraction of full scale: 0 means the microphone heard nothing at all. */
    fun peak(pcm: ShortArray): Double = (pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0) / 32768.0
}
