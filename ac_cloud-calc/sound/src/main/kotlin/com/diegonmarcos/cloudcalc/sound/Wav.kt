package com.diegonmarcos.cloudcalc.sound

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV, mono out; mono or stereo in (stereo is averaged). Nothing else is accepted. */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }

    data class Audio(val sampleRate: Int, val pcm: ShortArray)

    /** The audio, or IllegalArgumentException naming why the bytes are not a WAV this reads. */
    fun decode(bytes: ByteArray): Audio {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "not a RIFF/WAVE file" }
        var at = 12
        var channels = 0; var rate = 0; var bits = 0; var format = 0
        while (at + 8 <= bytes.size) {
            val id = tag(at); val len = b.getInt(at + 4)
            require(len >= 0 && at + 8 + len <= bytes.size) { "chunk $id runs past the end of the file" }
            if (id == "fmt ") {
                format = b.getShort(at + 8).toInt(); channels = b.getShort(at + 10).toInt()
                rate = b.getInt(at + 12); bits = b.getShort(at + 22).toInt()
            } else if (id == "data") {
                require(format == 1 && bits == 16) { "only 16-bit PCM is read (format $format, $bits bits)" }
                require(channels == 1 || channels == 2) { "only mono or stereo is read ($channels channels)" }
                val frames = len / (2 * channels)
                val pcm = ShortArray(frames) { i ->
                    var s = 0
                    for (c in 0 until channels) s += b.getShort(at + 8 + (i * channels + c) * 2)
                    (s / channels).toShort()
                }
                return Audio(rate, pcm)
            }
            at += 8 + len + (len and 1)
        }
        throw IllegalArgumentException("no data chunk")
    }
}

/** RFC 4180 CSV: a header and rows, a field quoted only when it must be. */
object Csv {
    fun write(header: List<String>, rows: List<List<Any?>>): String = buildString {
        append(header.joinToString(",") { field(it) }).append("\r\n")
        rows.forEach { r -> append(r.joinToString(",") { field(it) }).append("\r\n") }
    }

    private fun field(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Double -> if (v.isNaN()) "" else v.toString()
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
