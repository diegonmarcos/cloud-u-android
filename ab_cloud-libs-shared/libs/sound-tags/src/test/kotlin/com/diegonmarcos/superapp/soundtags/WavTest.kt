package com.diegonmarcos.superapp.soundtags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavTest {
    /** A RIFF/WAVE file: [fmt] chunk body, then any [extra] chunks, then data. */
    private fun wav(format: Int, channels: Int, rate: Int, bits: Int, data: ByteArray, extra: List<Pair<String, ByteArray>> = emptyList(),
                    dataSize: Long = data.size.toLong(), fmtSize: Int = 16, extSub: Int? = null): ByteArray {
        val o = ByteArrayOutputStream()
        fun s(x: String) = o.write(x.toByteArray(Charsets.US_ASCII))
        fun i16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        fun i32(v: Long) { i16((v and 0xFFFF).toInt()); i16(((v shr 16) and 0xFFFF).toInt()) }
        s("RIFF"); i32(0); s("WAVE")
        s("fmt "); i32(fmtSize.toLong()); i16(format); i16(channels); i32(rate.toLong()); i32(rate.toLong() * channels * bits / 8); i16(channels * bits / 8); i16(bits)
        if (fmtSize > 16) { i16(fmtSize - 18); repeat(fmtSize - 18) { o.write(0) } }
        if (extSub != null) { val b = o.toByteArray(); val at = b.size - (fmtSize - 18) + 6; b[at] = extSub.toByte(); b[at + 1] = 0; o.reset(); o.write(b) }
        for ((id, body) in extra) { s(id); i32(body.size.toLong()); o.write(body); if (body.size % 2 == 1) o.write(0) }
        s("data"); i32(dataSize); o.write(data)
        return o.toByteArray()
    }

    private fun pcm16(vararg v: Int): ByteArray = ByteArray(v.size * 2).also { b -> v.forEachIndexed { i, x -> b[2 * i] = (x and 0xFF).toByte(); b[2 * i + 1] = ((x shr 8) and 0xFF).toByte() } }

    @Test fun pcm16MonoDecodesToUnitRange() {
        val p = Wav.decode(wav(1, 1, 16000, 16, pcm16(0, 16384, -16384, 32767, -32768)))
        assertEquals(16000, p.rate)
        assertEquals(5, p.samples.size)
        assertEquals(0f, p.samples[0], 1e-6f)
        assertEquals(0.5f, p.samples[1], 1e-6f)
        assertEquals(-0.5f, p.samples[2], 1e-6f)
        assertEquals(32767 / 32768f, p.samples[3], 1e-6f)
        assertEquals(-1f, p.samples[4], 1e-6f)
    }

    @Test fun stereoIsAveragedToMono() {
        val p = Wav.decode(wav(1, 2, 8000, 16, pcm16(16384, 0, 32767, -32768)))
        assertEquals(2, p.samples.size)
        assertEquals(0.25f, p.samples[0], 1e-6f)
        assertEquals(-0.5f / 32768f, p.samples[1], 1e-6f)
    }

    @Test fun eightBitIsUnsignedAroundOneTwentyEight() {
        val p = Wav.decode(wav(1, 1, 8000, 8, byteArrayOf(128.toByte(), 0, 192.toByte())))
        assertEquals(0f, p.samples[0], 1e-6f)
        assertEquals(-1f, p.samples[1], 1e-6f)
        assertEquals(0.5f, p.samples[2], 1e-6f)
    }

    @Test fun twentyFourBitIsSignExtended() {
        val p = Wav.decode(wav(1, 1, 8000, 24, byteArrayOf(0, 0, 0x40, 0, 0, 0xC0.toByte(), 1, 0, 0)))
        assertEquals(0.5f, p.samples[0], 1e-6f)
        assertEquals(-0.5f, p.samples[1], 1e-6f)
        assertEquals(1 / 8388608f, p.samples[2], 1e-9f)
    }

    @Test fun thirtyTwoBitIntegerAndFloat() {
        val i = Wav.decode(wav(1, 1, 8000, 32, byteArrayOf(0, 0, 0, 0x40, 0, 0, 0, 0xC0.toByte())))
        assertEquals(0.5f, i.samples[0], 1e-6f)
        assertEquals(-0.5f, i.samples[1], 1e-6f)
        val fb = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-0.75f).array()
        val f = Wav.decode(wav(3, 1, 8000, 32, fb))
        assertEquals(0.25f, f.samples[0], 0f)
        assertEquals(-0.75f, f.samples[1], 0f)
    }

    @Test fun extensibleReadsItsSubformat() {
        val fb = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(0.125f).array()
        assertEquals(0.125f, Wav.decode(wav(0xFFFE, 1, 8000, 32, fb, fmtSize = 40, extSub = 3)).samples[0], 0f)
        assertEquals(0.5f, Wav.decode(wav(0xFFFE, 1, 8000, 16, pcm16(16384), fmtSize = 40, extSub = 1)).samples[0], 1e-6f)
    }

    @Test fun oddChunksArePaddedAndSkipped() {
        val p = Wav.decode(wav(1, 1, 8000, 16, pcm16(16384), extra = listOf("LIST" to byteArrayOf(1, 2, 3), "junk" to ByteArray(4))))
        assertEquals(1, p.samples.size)
        assertEquals(0.5f, p.samples[0], 1e-6f)
    }

    @Test fun anOverlongDataChunkIsReadToTheEndOfTheFile() {
        val p = Wav.decode(wav(1, 1, 8000, 16, pcm16(1, 2, 3), dataSize = 0xFFFFFFFFL))
        assertEquals(3, p.samples.size)
        val half = Wav.decode(wav(1, 1, 8000, 16, pcm16(1, 2, 3) + byteArrayOf(9)))
        assertEquals("a trailing half frame is not a sample", 3, half.samples.size)
    }

    @Test fun durationFollowsTheRate() {
        assertEquals(500L, Wav.decode(wav(1, 1, 8000, 16, ByteArray(8000))).durationMs)
        assertEquals(0L, Pcm(FloatArray(4), 0).durationMs)
    }

    private fun refused(b: ByteArray, says: String) {
        try { Wav.decode(b); fail("decoded what should be refused: $says") } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should say $says", e.message!!.contains(says))
        }
    }

    @Test fun refusesWhatItCannotRead() {
        refused("RIFX0000WAVE".toByteArray(), "not a RIFF/WAVE")
        refused("RIFF0000AVI ".toByteArray(), "not a RIFF/WAVE")
        refused("RIFF".toByteArray(), "not a RIFF/WAVE")
        refused(wav(1, 1, 8000, 16, ByteArray(0)).copyOf(36), "no data chunk")
        refused(wav(1, 1, 8000, 12, ByteArray(6)), "unsupported sample format")
        refused(wav(3, 1, 8000, 16, ByteArray(6)), "unsupported sample format")
        refused(wav(2, 1, 8000, 16, ByteArray(6)), "unsupported sample format")
        refused(wav(1, 0, 8000, 16, ByteArray(6)), "0 channels")
        refused(wav(1, 9, 8000, 16, ByteArray(18)), "9 channels")
        refused(wav(1, 1, 8000, 16, ByteArray(2)).copyOf(30), "fmt chunk is too short")
        val noFmt = "RIFF0000WAVEdata".toByteArray() + byteArrayOf(2, 0, 0, 0, 0, 0)
        refused(noFmt, "before any fmt")
    }

    @Test fun shortFmtSizeIsRefused() {
        val b = wav(1, 1, 8000, 16, pcm16(1))
        b[16] = 14
        refused(b, "fmt chunk is too short")
    }
}
