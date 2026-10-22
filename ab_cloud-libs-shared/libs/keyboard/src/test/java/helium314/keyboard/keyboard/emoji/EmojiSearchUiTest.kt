// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1103 item 7: the emoji page search. A tap brings the letter pad back, the results box shows the matching emoji, and it is half as tall. */
class EmojiSearchUiTest {
    private val src = File("src/main/java/helium314/keyboard/keyboard/emoji")

    @Test fun tappingTheSearchFieldReturnsToTheLetterPadThenOpensTheResultsBox() {
        assertEquals(listOf(KeyCode.ALPHA, KeyCode.EMOJI_SEARCH), EmojiSearchUi.TAP_CODES)
        val panel = File(src, "EmojiPalettesView.kt").readText()
        val box = panel.substring(panel.indexOf("private fun SearchBox()"))
        assertTrue("the search field sends the codes in TAP_CODES order", box.take(1200).contains("EmojiSearchUi.TAP_CODES.forEach"))
        // and nothing else sends EMOJI_SEARCH from this panel (it would skip the return to the alphabet pad)
        assertEquals(0, Regex("onCodeInput\\(KeyCode\\.EMOJI_SEARCH").findAll(panel).count())
    }

    @Test fun theResultsBoxIsHalfTheHeightItWas() {
        assertEquals(190, EmojiSearchUi.LEGACY_BOX_HEIGHT_DP) // 6 + 32 + 3 x 48 + 8: typed line and three stacked result rows
        assertEquals(EmojiSearchUi.LEGACY_BOX_HEIGHT_DP / 2, EmojiSearchUi.BOX_HEIGHT_DP)
        assertEquals(95, EmojiSearchUi.BOX_HEIGHT_DP)
        assertTrue("typed line + one result row still fit in the half height",
            EmojiSearchUi.CONTENT_HEIGHT_DP <= EmojiSearchUi.BOX_HEIGHT_DP)
    }

    @Test fun theBarDrawsOneFixedHeightBoxWithOneResultRowShowingTheEmoji() {
        val bar = File(src, "EmojiSearchBarView.kt").readText()
        val body = bar.substring(bar.indexOf("override fun Body()"), bar.indexOf("private fun ResultRow"))
        assertTrue(body.contains("height(EmojiSearchUi.BOX_HEIGHT_DP.dp)"))
        assertEquals("one results row, not three stacked", 1, Regex("ResultRow \\{").findAll(body).count())
        assertTrue("the matching emoji are drawn in that row", body.contains("emojis.forEach"))
    }

    @Test fun theResultsComeFromTheBundledEmojiDictionaryByNameAndKeyword() {
        assertEquals(listOf("red", "heart"), EmojiSearchQuery.terms("Red heart"))
        val bar = File(src, "EmojiSearchBarView.kt").readText()
        assertTrue(bar.contains("facilitator.getSuggestions(EmojiSearchQuery.terms(query))"))
        assertTrue(bar.contains(".filter { it.isEmoji }"))
    }
}
