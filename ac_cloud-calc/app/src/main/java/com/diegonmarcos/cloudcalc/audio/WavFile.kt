package com.diegonmarcos.cloudcalc.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A 16-bit mono PCM WAV file written as it is recorded: the header goes first with a zero length, the chunks are appended, and
 * [close] patches the two length fields, so a long voice memo never sits in memory. The result reads back with sound/Wav.decode.
 */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")

    /** Samples written so far. */
    var samples: Long = 0
        private set

    init {
        raf.setLength(0)
        raf.write(header(0L, sampleRate))
    }

    fun write(pcm: ShortArray, count: Int = pcm.size) {
        val b = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) b.putShort(pcm[i])
        raf.write(b.array())
        samples += count
    }

    override fun close() {
        raf.seek(0)
        raf.write(header(samples * 2, sampleRate))
        raf.close()
    }

    companion object {
        const val HEADER_BYTES = 44

        fun header(dataBytes: Long, sampleRate: Int): ByteArray {
            val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            b.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
            return b.array()
        }

        /** Seconds of audio in a finished file of [bytes] bytes. */
        fun seconds(bytes: Long, sampleRate: Int): Long = if (bytes <= HEADER_BYTES) 0 else (bytes - HEADER_BYTES) / (sampleRate * 2L)
    }
}
