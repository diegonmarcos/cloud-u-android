// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The bar under the emoji pages: where it sits for a given scroll position. Pure JVM. */
class EmojiPageIndicatorMathTest {

    @Test fun aCategoryOfOnePageHasNoBar() {
        assertNull(EmojiPageIndicatorMath.bar(1, 0, 0f, 1000f))
        assertNull(EmojiPageIndicatorMath.bar(0, 0, 0f, 1000f))
    }

    @Test fun theBarCoversOnePageUnitOfTheWidth() {
        val bar = EmojiPageIndicatorMath.bar(4, 1, 0f, 1000f)
        assertNotNull(bar)
        assertEquals(250f, bar!!.first, 1e-3f)
        assertEquals(500f, bar.second, 1e-3f)
    }

    @Test fun theBarSlidesWithThePartOfThePageScrolledAndNeverLeavesTheIndicator() {
        val half = EmojiPageIndicatorMath.bar(4, 1, 0.5f, 1000f)!!
        assertEquals(375f, half.first, 1e-3f)
        val last = EmojiPageIndicatorMath.bar(4, 3, 0.9f, 1000f)!!
        assertEquals(750f, last.first, 1e-3f) // clamped to the last unit
        assertEquals(1000f, last.second, 1e-3f)
    }

    @Test fun thePositionFollowsHowFarTheListHasScrolled() {
        // 3 pages of 300 px in a 300 px window: 600 px to scroll
        val top = EmojiPageIndicatorMath.position(0, 900, 300, 3)
        assertEquals(0, top.pageId)
        assertEquals(0f, top.offset, 1e-6f)
        val middle = EmojiPageIndicatorMath.position(300, 900, 300, 3)
        assertEquals(1, middle.pageId)
        assertEquals(0.5f, middle.offset, 1e-6f)
        val end = EmojiPageIndicatorMath.position(600, 900, 300, 3)
        assertEquals(3, end.pageId) // the bar clamps it to the last unit
    }

    @Test fun aListThatFitsReadsAsTheTop() {
        val p = EmojiPageIndicatorMath.position(0, 200, 300, 2)
        assertEquals(0, p.pageId)
        assertEquals(0f, p.offset, 1e-6f)
    }
}
