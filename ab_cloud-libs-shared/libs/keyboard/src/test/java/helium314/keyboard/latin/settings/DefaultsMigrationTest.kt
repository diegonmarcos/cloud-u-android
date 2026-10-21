// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1077 items 2 and 9: a changed default reaches users still on the old one, and nobody who chose. */
class DefaultsMigrationTest {
    private val trio = listOf(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS, Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS, Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I)
    private val space = Settings.PREF_SPACE_TO_CHANGE_LANG

    @Test fun nothingStoredNeedsNothing() {
        assertTrue(DefaultsMigration.keysToReset(emptyMap<String, Any>()).isEmpty()) // the new default applies by itself
    }

    @Test fun aUserStillOnTheOldDefaultsIsMoved() {
        val stored = trio.associateWith { true } + (space to true)
        assertEquals((trio + space).toSet(), DefaultsMigration.keysToReset(stored).toSet())
    }

    @Test fun aUserWhoTurnedAFixOffKeepsAllThreeAsTheyAre() {
        val stored = mapOf(trio[0] to true, trio[1] to false, trio[2] to true)
        assertTrue(DefaultsMigration.keysToReset(stored).none { it in trio })
    }

    @Test fun aPartlyStoredGroupStillOnTheOldDefaultIsMoved() {
        assertEquals(listOf(trio[2]), DefaultsMigration.keysToReset(mapOf(trio[2] to true)))
    }

    @Test fun theSpaceBarChoiceIsIndependentOfTheFixes() {
        val stored = mapOf(trio[0] to false, space to true)
        assertEquals(listOf(space), DefaultsMigration.keysToReset(stored))
        assertTrue(DefaultsMigration.keysToReset(mapOf(space to false)).isEmpty()) // chose off already: nothing to drop
    }

    @Test fun everyGroupNamesTheValueItWasShippedWith() {
        assertTrue(DefaultsMigration.groups.all { it.oldDefault })
        assertTrue(DefaultsMigration.REVISION >= 1)
    }
}
