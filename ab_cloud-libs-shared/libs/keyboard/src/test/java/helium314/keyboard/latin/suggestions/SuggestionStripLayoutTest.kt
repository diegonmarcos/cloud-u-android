// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import helium314.keyboard.latin.SuggestedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which word the suggestion row puts where, and how wide each slot is. The Compose strip only draws
 * what [SuggestionStripLayout] decides, so these rules are what stops an autocorrect from landing off
 * centre or a typed word from vanishing. Pure JVM: nothing here touches the Android framework.
 */
class SuggestionStripLayoutTest {
    private val center = 1 // three slots: 0 1 2
    private val typedWhenAutocorrect = 0

    private fun position(index: Int, willAutoCorrect: Boolean, omitTyped: Boolean) =
        SuggestionStripLayout.getPositionInSuggestionStrip(index, willAutoCorrect, omitTyped, center, typedWhenAutocorrect)

    @Test fun theTypedWordSitsInTheCentreWhenNothingWillAutocorrect() {
        assertEquals(center, position(SuggestedWords.INDEX_OF_TYPED_WORD, willAutoCorrect = false, omitTyped = false))
    }

    @Test fun theAutocorrectionTakesTheCentreAndTheTypedWordMovesLeft() {
        assertEquals(center, position(SuggestedWords.INDEX_OF_AUTO_CORRECTION, willAutoCorrect = true, omitTyped = false))
        assertEquals(typedWhenAutocorrect, position(SuggestedWords.INDEX_OF_TYPED_WORD, willAutoCorrect = true, omitTyped = false))
    }

    @Test fun withoutAutocorrectTheFirstSuggestionIsLeftOfTheTypedWord() {
        assertEquals(typedWhenAutocorrect, position(SuggestedWords.INDEX_OF_AUTO_CORRECTION, willAutoCorrect = false, omitTyped = false))
    }

    @Test fun furtherSuggestionsGoRightThenOffTheRowOfThree() {
        assertEquals(2, position(2, willAutoCorrect = false, omitTyped = false))
        // the fourth word has no slot in a row of three: negative means "not in the strip" (it is in more suggestions)
        assertTrue(position(3, willAutoCorrect = false, omitTyped = false) < 0)
    }

    @Test fun whileTypingTheTypedWordIsLeftOutAndTheAutocorrectionIsCentred() {
        assertTrue(position(SuggestedWords.INDEX_OF_TYPED_WORD, willAutoCorrect = true, omitTyped = true) < 0)
        assertEquals(center, position(SuggestedWords.INDEX_OF_AUTO_CORRECTION, willAutoCorrect = true, omitTyped = true))
        // then left of the centre first, then right of it
        assertEquals(0, position(2, willAutoCorrect = true, omitTyped = true))
        assertEquals(2, position(3, willAutoCorrect = true, omitTyped = true))
    }

    @Test fun theTypedWordIsOmittedOnlyForTypingAndGestureStyles() {
        assertTrue(SuggestionStripLayout.shouldOmitTypedWord(SuggestedWords.INPUT_STYLE_TYPING, false, true))
        assertTrue(SuggestionStripLayout.shouldOmitTypedWord(SuggestedWords.INPUT_STYLE_TAIL_BATCH, false, true))
        assertTrue(SuggestionStripLayout.shouldOmitTypedWord(SuggestedWords.INPUT_STYLE_UPDATE_BATCH, true, true))
        assertFalse(SuggestionStripLayout.shouldOmitTypedWord(SuggestedWords.INPUT_STYLE_UPDATE_BATCH, false, true))
        assertFalse(SuggestionStripLayout.shouldOmitTypedWord(SuggestedWords.INPUT_STYLE_PREDICTION, true, true))
        assertFalse("when the UI cannot accept the typed word it is never omitted",
            SuggestionStripLayout.shouldOmitTypedWord(SuggestedWords.INPUT_STYLE_TYPING, true, false))
    }

    @Test fun theCentreSlotGetsItsConfiguredShareAndTheRestSplitTheRemainder() {
        val centreShare = 0.40f
        val total = (0 until 3).sumOf { SuggestionStripLayout.suggestionWeight(it, center, centreShare, 3).toDouble() }
        assertEquals(1.0, total, 1e-6)
        assertEquals(0.40f, SuggestionStripLayout.suggestionWeight(center, center, centreShare, 3), 1e-6f)
        assertEquals(0.30f, SuggestionStripLayout.suggestionWeight(0, center, centreShare, 3), 1e-6f)
    }

    @Test fun slotWidthIsTheShareOfTheRowLeftAfterPaddingsAndDividers() {
        // 1000 px row, 12 px padding per slot (3 slots), 1 px dividers (2 of them): 1000 - 36 - 2 = 962 px to share
        assertEquals((962 * 0.40f).toInt(), SuggestionStripLayout.suggestionWidth(1, 1000, 12, 1, center, 0.40f, 3))
        assertEquals((962 * 0.30f).toInt(), SuggestionStripLayout.suggestionWidth(0, 1000, 12, 1, center, 0.40f, 3))
    }

    @Test fun textThatFitsIsNotSqueezedAndTextThatDoesNotIsScaledToTheSlot() {
        assertEquals(1.0f, SuggestionStripLayout.textScaleX(80, 100), 0f)
        assertEquals(0.5f, SuggestionStripLayout.textScaleX(200, 100), 1e-6f)
        // a slot with no known width (the row is not measured yet) squeezes nothing
        assertEquals(1.0f, SuggestionStripLayout.textScaleX(200, 0), 0f)
        assertEquals(1.0f, SuggestionStripLayout.textScaleX(200, -5), 0f)
    }
}
