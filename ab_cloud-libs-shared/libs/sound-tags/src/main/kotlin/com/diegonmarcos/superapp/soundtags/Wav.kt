package com.diegonmarcos.superapp.soundtags

/** Mono samples in [-1, 1] at [rate] Hz. */
class Pcm(val samples: FloatArray, val rate: Int) {
    val durationMs: Long get() = if (rate > 0) samples.size * 1000L / rate else 0L
}

/**
 * #798 the RIFF/WAVE files the sound engine is handed: 8/16/24/32-bit integer PCM or 32-bit float
 * (plain or WAVE_FORMAT_EXTENSIBLE), any channel count, averaged to mono. Anything else is refused
 * with the reason, never guessed at. A data chunk that claims more bytes than the file holds (a
 * recorder that never patched its header) is read to the end of the file.
 */
object Wav {
    const val PCM = 1
    const val FLOAT = 3
    const val EXTENSIBLE = 0xFFFE

    fun decode(b: ByteArray): Pcm {
        require(b.size >= 12 && ascii(b, 0) == "RIFF" && ascii(b, 8) == "WAVE") { "not a RIFF/WAVE file" }
        var pos = 12
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= b.size) {
            val id = ascii(b, pos)
            val size = u32(b, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                require(size >= 16 && body + 16 <= b.size) { "the fmt chunk is too short" }
                format = u16(b, body)
                channels = u16(b, body + 2)
                rate = u32(b, body + 4).toInt()
                bits = u16(b, body + 14)
                if (format == EXTENSIBLE && size >= 26 && body + 26 <= b.size) format = u16(b, body + 24)
            } else if (id == "data") {
                require(format >= 0) { "the data chunk comes before any fmt chunk" }
                return Pcm(mono(b, body, minOf(b.size.toLong(), body + size).toInt(), format, channels, bits), rate)
            }
            pos = (body + size + (size and 1L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        throw IllegalArgumentException("no data chunk")
    }

    private fun mono(b: ByteArray, start: Int, end: Int, format: Int, channels: Int, bits: Int): FloatArray {
        require(channels in 1..8) { "$channels channels" }
        require(
            (format == PCM && bits in setOf(8, 16, 24, 32)) || (format == FLOAT && bits == 32),
        ) { "unsupported sample format $format with $bits bits (integer PCM 8/16/24/32 or 32-bit float only)" }
        val width = bits / 8
        val frame = width * channels
        val n = (end - start) / frame
        return FloatArray(n) { i ->
            var acc = 0f
            for (c in 0 until channels) acc += sample(b, start + i * frame + c * width, format, bits)
            acc / channels
        }
    }

    private fun sample(b: ByteArray, at: Int, format: Int, bits: Int): Float = when {
        format == FLOAT -> Float.fromBits(u32(b, at).toInt())
        bits == 8 -> ((b[at].toInt() and 0xFF) - 128) / 128f
        bits == 16 -> (u16(b, at).toShort().toInt()) / 32768f
        bits == 24 -> (((b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() shl 16))) / 8388608f
        else -> u32(b, at).toInt() / 2147483648f
    }
}

internal fun ascii(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)
internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
internal fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)
