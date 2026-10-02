package com.diegonmarcos.cloudcalc.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * The microphone. Whether it may be used is read here; asking for it is ui/SoundScreens.kt's
 * MicGate alone (test/test-calc-shell.sh C4). Every capture is 16-bit mono at the declared rate,
 * computed on and discarded unless a History session keeps it for its WAV export.
 */
object Mic {
    fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // every caller checks granted() first
    private fun open(sampleRate: Int, samples: Int): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) error("this device cannot record at $sampleRate Hz")
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, samples * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("the microphone could not be opened") }
        return rec
    }

    /** [ms] of audio, blocking. Stops early (short) only if the device stops delivering. */
    fun record(ctx: Context, sampleRate: Int, ms: Int): ShortArray {
        if (!granted(ctx)) error("RECORD_AUDIO is not granted — open Measure ▸ Sound once and allow the microphone")
        val n = (sampleRate.toLong() * ms / 1000).toInt()
        val rec = open(sampleRate, n)
        val pcm = ShortArray(n)
        var got = 0
        try {
            rec.startRecording()
            val deadline = System.currentTimeMillis() + ms * 2L + 2000
            while (got < n && System.currentTimeMillis() < deadline) {
                val r = rec.read(pcm, got, n - got)
                if (r < 0) error("microphone read failed ($r)")
                got += r
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return if (got == n) pcm else pcm.copyOf(got)
    }

    /** Chunks of [chunk] samples until the calling coroutine is cancelled (the screen is left). */
    suspend fun stream(ctx: Context, sampleRate: Int, chunk: Int, onChunk: (ShortArray) -> Unit) {
        if (!granted(ctx)) error("RECORD_AUDIO is not granted")
        val rec = open(sampleRate, chunk)
        try {
            rec.startRecording()
            while (currentCoroutineContext().isActive) {
                val pcm = ShortArray(chunk)
                var got = 0
                while (got < chunk) {
                    val r = rec.read(pcm, got, chunk - got)
                    if (r < 0) error("microphone read failed ($r)")
                    got += r
                }
                onChunk(pcm)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}

/** The speaker: one generated buffer at a time, on the media stream. */
object Player {
    @Volatile private var track: AudioTrack? = null

    fun play(pcm: ShortArray, sampleRate: Int) {
        stop()
        if (pcm.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    /** The media stream's volume as a fraction of its maximum: the safe-volume guard's input. */
    fun deviceVolume(ctx: Context): Double {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val m = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (m <= 0) 0.0 else am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / m
    }
}
