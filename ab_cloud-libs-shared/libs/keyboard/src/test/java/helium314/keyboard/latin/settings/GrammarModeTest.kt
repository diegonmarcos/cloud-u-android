// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1077 items 2, 3 and 9: the merged Grammar mode, its engines and the new defaults. */
class GrammarModeTest {
    @Test fun theModeCyclesHighlightAutoCorrectOffAndBack() {
        assertEquals(GrammarMode.AUTO_CORRECT, GrammarMode.HIGHLIGHT.next())
        assertEquals(GrammarMode.OFF, GrammarMode.AUTO_CORRECT.next())
        assertEquals(GrammarMode.HIGHLIGHT, GrammarMode.OFF.next())
    }

    @Test fun aStoredModeWins() {
        assertEquals(GrammarMode.OFF, GrammarMode.resolve("off", legacyAutoCorrect = true, legacyEngine = "remote"))
        assertEquals(GrammarMode.HIGHLIGHT, GrammarMode.resolve("highlight", true, "off"))
        assertEquals(GrammarMode.AUTO_CORRECT, GrammarMode.resolve("auto_correct", false, "off"))
    }

    @Test fun withoutAStoredModeTheOldSettingsDecide() {
        assertEquals(GrammarMode.AUTO_CORRECT, GrammarMode.resolve(null, legacyAutoCorrect = true, legacyEngine = "remote"))
        assertEquals(GrammarMode.HIGHLIGHT, GrammarMode.resolve(null, legacyAutoCorrect = false, legacyEngine = "local"))
        assertEquals(GrammarMode.OFF, GrammarMode.resolve(null, legacyAutoCorrect = false, legacyEngine = "off"))
        assertEquals(GrammarMode.AUTO_CORRECT, GrammarMode.resolve("nonsense", true, "off")) // an unknown value is not a mode
    }

    @Test fun onlyAutoCorrectCorrectsAndOnlyOffStopsChecking() {
        assertTrue(GrammarMode.AUTO_CORRECT.autoCorrects)
        assertFalse(GrammarMode.HIGHLIGHT.autoCorrects)
        assertFalse(GrammarMode.OFF.autoCorrects)
        assertTrue(GrammarMode.HIGHLIGHT.checksGrammar)
        assertFalse(GrammarMode.OFF.checksGrammar)
        assertNull(GrammarMode.fromId("auto-correct"))
    }

    @Test fun theModeIdsAreStable() {
        assertEquals(listOf("auto_correct", "highlight", "off"), GrammarMode.entries.map { it.id })
        assertEquals("grammar_check_mode", Settings.PREF_GRAMMAR_CHECK_MODE)
    }

    @Test fun everyEngineIsListedAndMarkedNativeOrExternal() {
        val byId = GrammarEngines.ALL.associateBy { it.id }
        assertEquals(GrammarEngines.ALL.size, byId.size) // no id twice
        for (id in listOf("dictionary", "spellchecker", "ngram", "rules", "personal", "languagetool", "ai", "ngram-api", "ondevice-ml"))
            assertTrue("engine $id is listed", id in byId)
        assertEquals(EngineOrigin.NATIVE, byId.getValue("dictionary").origin)
        assertEquals(EngineOrigin.NATIVE, byId.getValue("rules").origin)
        assertEquals(EngineOrigin.EXTERNAL, byId.getValue("languagetool").origin)
        assertEquals(EngineOrigin.EXTERNAL, byId.getValue("ai").origin)
        assertFalse("the n-gram service is declared but not called", byId.getValue("ngram-api").wired)
        assertTrue(GrammarEngines.ALL.all { it.title.isNotBlank() && it.what.isNotBlank() })
    }

    @Test fun theGrammarFixesAndTheSpaceBarLanguageSwitchAreOffByDefault() {
        assertFalse(Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS) // capitalise first word
        assertFalse(Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS) // remove repeated word
        assertFalse(Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I) // capitalise i
        assertFalse(Defaults.PREF_SPACE_TO_CHANGE_LANG) // change language by swiping the space bar
    }
}
