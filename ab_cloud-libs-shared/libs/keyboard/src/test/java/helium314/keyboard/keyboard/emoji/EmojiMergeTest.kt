// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1077 items 10, 11 and 12 on the emoji panel. */
class EmojiMergeTest {
    @Test fun pinnedIsOnTopAndRecentBelowWithoutRepeats() {
        assertEquals(listOf("a", "b", "x", "y"), EmojiMerge.merge(listOf("a", "b"), listOf("x", "b", "y", "a")))
        assertEquals(listOf("x", "y"), EmojiMerge.merge(emptyList(), listOf("x", "y")))
        assertEquals(listOf("a"), EmojiMerge.merge(listOf("a"), emptyList()))
        assertTrue(EmojiMerge.merge(emptyList<String>(), emptyList()).isEmpty())
    }

    @Test fun theMergedPageIsTheRecentsPageAndTheFirstOneOpened() {
        assertEquals(EmojiCategory.ID_RECENTS, EmojiMerge.FIRST_PAGE_CATEGORY)
        assertEquals(0, EmojiCategory.ID_RECENTS)
    }

    @Test fun aLongPressPinsOnTheMergedPageAndOpensThePickerOnTheOthers() {
        assertTrue(EmojiMerge.pinsOnLongPress(EmojiCategory.ID_RECENTS))
        for (id in 1..10) assertFalse("category $id keeps the skin-tone picker", EmojiMerge.pinsOnLongPress(id))
        assertFalse(EmojiMerge.pinsOnLongPress(EmojiCategory.ID_PINNED)) // it has no page of its own any more
    }

    @Test fun theSearchBoxLooksUpLowerCasedWordsOnce() {
        assertEquals(listOf("red", "heart"), EmojiSearchQuery.terms("  Red  HEART red "))
        assertEquals(listOf("smile"), EmojiSearchQuery.terms(":smile"))
        assertTrue(EmojiSearchQuery.terms("   ").isEmpty())
    }
}
