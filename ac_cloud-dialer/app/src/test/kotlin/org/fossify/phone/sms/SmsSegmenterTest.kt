package org.fossify.phone.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multipart split: GSM 7-bit (160 / 153) and UCS-2 (70 / 67), extension characters and surrogate pairs never cut. */
class SmsSegmenterTest {
    @Test
    fun emptyTextHasNoParts() {
        assertEquals(0, SmsSegmenter.split("").count)
    }

    @Test
    fun gsmTextOfExactlyOneSingleSmsIsOnePart() {
        val s = SmsSegmenter.split("a".repeat(160))
        assertEquals(SmsEncoding.Gsm7, s.encoding)
        assertEquals(1, s.count)
        assertEquals(160, s.unitsUsed)
        assertEquals(0, s.remainingInLastPart)
    }

    @Test
    fun gsmTextOneSeptetOverSplitsAt153() {
        val s = SmsSegmenter.split("a".repeat(161))
        assertEquals(2, s.count)
        assertEquals(153, s.parts[0].length)
        assertEquals(8, s.parts[1].length)
        assertEquals(153, s.unitsPerPart)
        assertEquals(2 * 153 - 161, s.remainingInLastPart)
    }

    @Test
    fun gsmPartCountsFollowTheConcatenatedLimit() {
        assertEquals(2, SmsSegmenter.split("a".repeat(306)).count)
        assertEquals(3, SmsSegmenter.split("a".repeat(307)).count)
        assertEquals(1, SmsSegmenter.split("a".repeat(150)).count)
        assertEquals(10, SmsSegmenter.split("a".repeat(150)).remainingInLastPart)
    }

    @Test
    fun anExtensionCharacterCostsTwoAndIsNotSplitAcrossParts() {
        val text = "a".repeat(152) + "€" + "b".repeat(10)
        val s = SmsSegmenter.split(text)
        assertEquals(SmsEncoding.Gsm7, s.encoding)
        assertEquals(164, s.unitsUsed)
        assertEquals(2, s.count)
        assertEquals("a".repeat(152), s.parts[0])
        assertEquals("€" + "b".repeat(10), s.parts[1])
        assertEquals(text, s.parts.joinToString(""))
    }

    @Test
    fun extensionCharactersCountDoubleTowardTheSingleSmsLimit() {
        assertEquals(1, SmsSegmenter.split("€".repeat(80)).count)
        val s = SmsSegmenter.split("€".repeat(81))
        assertEquals(162, s.unitsUsed)
        assertEquals(2, s.count)
        assertEquals(76, s.parts[0].length)
    }

    @Test
    fun anyCharacterOutsideGsmSwitchesToUcs2() {
        assertTrue(SmsSegmenter.isGsm("héllo wörld ñ"))
        assertFalse(SmsSegmenter.isGsm("ç"))
        val s = SmsSegmenter.split("ж".repeat(70))
        assertEquals(SmsEncoding.Ucs2, s.encoding)
        assertEquals(1, s.count)
        val two = SmsSegmenter.split("ж".repeat(71))
        assertEquals(2, two.count)
        assertEquals(67, two.parts[0].length)
        assertEquals(4, two.parts[1].length)
    }

    @Test
    fun aSurrogatePairIsNeverSplit() {
        val text = "ж".repeat(66) + "😀" + "ж".repeat(10)
        val s = SmsSegmenter.split(text)
        assertEquals(SmsEncoding.Ucs2, s.encoding)
        assertEquals(2, s.count)
        assertEquals(66, s.parts[0].length)
        assertTrue(s.parts[1].startsWith("😀"))
        assertEquals(text, s.parts.joinToString(""))
        s.parts.forEach { part ->
            assertFalse(part.first().isLowSurrogate())
            assertFalse(part.last().isHighSurrogate())
        }
    }
}
