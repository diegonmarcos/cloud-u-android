package com.diegonmarcos.cloudcalc

import com.diegonmarcos.cloudcalc.audio.WavWriter
import com.diegonmarcos.cloudcalc.sound.Wav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WavFileTest {
    private fun temp(): File = File.createTempFile("rec", ".wav").also { it.deleteOnExit() }

    @Test fun `chunks written as they arrive read back as the same audio`() {
        val f = temp()
        val a = ShortArray(1600) { (it * 7 - 4000).toShort() }
        val b = ShortArray(800) { (-it).toShort() }
        WavWriter(f, 16000).use { w -> w.write(a); w.write(b); assertEquals(2400L, w.samples) }
        val back = Wav.decode(f.readBytes())
        assertEquals(16000, back.sampleRate)
        assertArrayEquals(a + b, back.pcm)
        assertEquals(44L + 2400 * 2, f.length())
    }

    @Test fun `the header is byte for byte the one the sound module's encoder writes`() {
        assertArrayEquals(Wav.encode(ShortArray(3) { 1 }, 16000).copyOf(44), WavWriter.header(6, 16000))
        assertArrayEquals(Wav.encode(ShortArray(0), 44100).copyOf(44), WavWriter.header(0, 44100))
    }

    @Test fun `a partial chunk and an empty recording are valid files`() {
        val f = temp()
        WavWriter(f, 8000).use { w -> w.write(ShortArray(10) { 5 }, 4) }
        assertArrayEquals(ShortArray(4) { 5 }, Wav.decode(f.readBytes()).pcm)
        val e = temp()
        WavWriter(e, 8000).close()
        assertEquals(WavWriter.HEADER_BYTES.toLong(), e.length())
        assertEquals(0, Wav.decode(e.readBytes()).pcm.size)
    }

    @Test fun `an existing file is overwritten, not appended to`() {
        val f = temp()
        WavWriter(f, 16000).use { it.write(ShortArray(100)) }
        WavWriter(f, 16000).use { it.write(ShortArray(10)) }
        assertEquals(44L + 20, f.length())
    }

    @Test fun `the length shown for a file is whole seconds of audio`() {
        assertEquals(0L, WavWriter.seconds(0, 16000))
        assertEquals(0L, WavWriter.seconds(44, 16000))
        assertEquals(1L, WavWriter.seconds(44 + 32000, 16000))
        assertEquals(1L, WavWriter.seconds(44 + 63999, 16000))
        assertEquals(60L, WavWriter.seconds(44 + 60L * 32000, 16000))
    }
}
