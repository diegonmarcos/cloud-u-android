// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import helium314.keyboard.FakePrefs
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.grammarIconRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1103 item 5: the Grammar mode icon cycles Auto, Highlights, Off; each tap names the new mode; each mode has its own look. */
class GrammarTriStateTest {
    @Test fun eachTapMovesAutoToHighlightToOffAndRoundAgain() {
        val prefs = FakePrefs()
        GrammarMode.write(prefs, GrammarMode.AUTO_CORRECT)
        val seen = ArrayList<GrammarMode>()
        repeat(6) {
            GrammarMode.write(prefs, GrammarMode.read(prefs).next()) // what Settings.cycleGrammarMode does on a tap
            seen += GrammarMode.read(prefs)
        }
        assertEquals(listOf(GrammarMode.HIGHLIGHT, GrammarMode.OFF, GrammarMode.AUTO_CORRECT,
            GrammarMode.HIGHLIGHT, GrammarMode.OFF, GrammarMode.AUTO_CORRECT), seen)
    }

    @Test fun theTapIsWiredToTheCycleAndTheToast() {
        val settings = java.io.File("src/main/java/helium314/keyboard/latin/settings/Settings.java").readText()
        assertTrue(settings.contains("GrammarMode.write(mPrefs, GrammarMode.read(mPrefs).next())"))
        val ime = java.io.File("src/main/java/helium314/keyboard/latin/LatinIME.java").readText()
        val body = ime.substring(ime.indexOf("public void cycleGrammarMode()"))
        assertTrue(body.contains("Settings.getInstance().cycleGrammarMode()"))
        assertTrue("a toast names the new mode", body.take(900).contains("Toast.makeText") && body.take(900).contains("GrammarMode.Companion.toast("))
        val input = java.io.File("src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java").readText()
        assertTrue(input.contains("case KeyCode.GRAMMAR_MODE:") && input.contains("mLatinIME.cycleGrammarMode()"))
    }

    @Test fun theToastNamesTheNewMode() {
        val labels = GrammarMode.entries.map { GrammarMode.labelRes(it) }
        assertEquals("one name per mode", 3, labels.toSet().size)
        assertEquals(R.string.grammar_check_mode_auto_correct, GrammarMode.labelRes(GrammarMode.AUTO_CORRECT))
        assertEquals(R.string.grammar_check_mode_highlight, GrammarMode.labelRes(GrammarMode.HIGHLIGHT))
        assertEquals(R.string.grammar_check_mode_off, GrammarMode.labelRes(GrammarMode.OFF))
        assertEquals("Grammar mode: Highlight", GrammarMode.toast("Grammar mode", "Highlight"))
    }

    @Test fun eachModeHasItsOwnIcon() {
        val icons = GrammarMode.entries.map { grammarIconRes(it) }
        assertEquals(3, icons.toSet().size)
        assertTrue(icons.all { it != 0 })
        assertNotEquals(grammarIconRes(GrammarMode.AUTO_CORRECT), grammarIconRes(GrammarMode.OFF))
    }

    @Test fun theStripRedrawsTheIconWhenTheModeChanges() {
        val strip = java.io.File("src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt").readText()
        assertTrue(strip.contains("if (item.key == ToolbarKey.GRAMMAR) toolbarKeyIconNow(context, item.key)"))
        val utils = java.io.File("src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt").readText()
        assertTrue("the grammar mode pref is one that redraws the strip", utils.contains("key != Settings.PREF_GRAMMAR_CHECK_MODE"))
    }
}
