package com.diegonmarcos.superapp.image.mlkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #772 the dominant-colour maths, pure: bucket means, shares, the redmean naming. */
class ColoursTest {
    private val names = mapOf("red" to 0xE53935, "blue" to 0x1E88E5, "white" to 0xFFFFFF, "black" to 0x000000)

    @Test fun `the most common colour first, with its exact mean and share`() {
        val px = IntArray(100) { if (it < 70) 0xFF102030.toInt() else 0xFFF0E0D0.toInt() }
        val s = Colours.dominant(px, 5)
        assertEquals(2, s.size)
        assertEquals("#102030", s[0].hex)
        assertEquals(0.7, s[0].share, 1e-12)
        assertEquals("#F0E0D0", s[1].hex)
        assertEquals(0.3, s[1].share, 1e-12)
        assertEquals(1, Colours.dominant(px, 1).size)
    }

    @Test fun `near colours share a bucket and answer their mean`() {
        val px = intArrayOf(0xFF101010.toInt(), 0xFF1E1E1E.toInt())
        val s = Colours.dominant(px, 3).single()
        assertEquals("#171717", s.hex)
        assertEquals(1.0, s.share, 0.0)
    }

    @Test fun `transparent pixels are not counted`() {
        assertTrue(Colours.dominant(IntArray(10), 3).isEmpty())
        val s = Colours.dominant(intArrayOf(0, 0, 0xFFFF0000.toInt()), 3).single()
        assertEquals(1.0, s.share, 0.0)
    }

    @Test fun `ties are broken by bucket so the order is stable`() {
        val px = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        assertEquals(listOf("#0000FF", "#FF0000"), Colours.dominant(px, 2).map { it.hex })
    }

    @Test fun `names are the nearest declared colour`() {
        assertEquals("red", Colours.name(0xD03030, names))
        assertEquals("blue", Colours.name(0x2060E0, names))
        assertEquals("white", Colours.name(0xF8F8F8, names))
        assertEquals("black", Colours.name(0x101010, names))
        assertNull(Colours.name(0x123456, emptyMap()))
    }

    @Test fun `redmean distance - zero for equal, symmetric, green weighs most`() {
        assertEquals(0.0, Colours.distance(0x336699, 0x336699), 0.0)
        assertEquals(Colours.distance(0x000000, 0x0A0000), Colours.distance(0x0A0000, 0x000000), 1e-12)
        assertTrue(Colours.distance(0x000000, 0x000A00) > Colours.distance(0x000000, 0x0A0000))
        assertTrue(Colours.distance(0x000000, 0x000A00) > Colours.distance(0x000000, 0x00000A))
        assertEquals(Math.sqrt(4.0 * 100), Colours.distance(0x000000, 0x000A00), 1e-9)
    }

    @Test fun `hex parsing accepts #RRGGBB and refuses anything else`() {
        assertEquals(0xE53935, Colours.parseHex("#E53935"))
        assertEquals(0xE53935, Colours.parseHex(" e53935 "))
        assertNull(Colours.parseHex("#FFF"))
        assertNull(Colours.parseHex("#GGGGGG"))
    }
}
