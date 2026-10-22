// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.KeyboardViewState.Exit
import helium314.keyboard.keyboard.KeyboardViewState.Inputs
import helium314.keyboard.keyboard.KeyboardViewState.Mode
import helium314.keyboard.keyboard.KeyboardViewState.PanelKind
import helium314.keyboard.keyboard.KeyboardViewState.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1103: the Autocomplete panel is a panel of its own: own view, the clipboard's strip and bottom row, never the clipboard's view. */
class AutocompleteViewStateTest {
    private val inputs: List<Inputs> = listOf(true, false).flatMap { a -> listOf(true, false).flatMap { b ->
        listOf(true, false).flatMap { c -> listOf(true, false).map { d -> Inputs(a, b, c, d) } } } }

    @Test fun itShowsItsOwnViewAndNotTheClipboardOrEmojiOnes() {
        for (i in inputs) {
            val plan = KeyboardViewState.plan(Mode.Panel(PanelKind.AUTOCOMPLETE), false, i)
            assertTrue(plan.shows(Piece.AUTOCOMPLETE_VIEW))
            assertFalse(plan.shows(Piece.CLIPBOARD_HISTORY)); assertFalse(plan.shows(Piece.EMOJI_PALETTES)); assertFalse(plan.shows(Piece.MAIN_KEYBOARD))
        }
        for (kind in listOf(PanelKind.CLIPBOARD, PanelKind.EMOJI))
            assertFalse(KeyboardViewState.plan(Mode.Panel(kind), false, inputs[0]).shows(Piece.AUTOCOMPLETE_VIEW))
    }

    @Test fun itAlwaysHasTheCloseKeyAndTheAbcRowSoThereIsAWayOut() {
        for (i in inputs) {
            val exits = KeyboardViewState.plan(Mode.Panel(PanelKind.AUTOCOMPLETE), false, i).exits
            assertTrue("close key with $i", Exit.PANEL_CLOSE_KEY in exits)
            assertTrue("ABC row with $i", Exit.BOTTOM_ROW_ALPHA in exits)
        }
    }

    @Test fun backAndItsToolbarKeyLeadBackToTyping() {
        val s = KeyboardViewState()
        s.openPanel(PanelKind.AUTOCOMPLETE)
        assertTrue(s.consumesBack())
        assertEquals(Mode.Typing, s.toggleTarget(PanelKind.AUTOCOMPLETE))
        s.toTyping(false)
        assertEquals(Mode.Typing, s.mode)
    }

    @Test fun theSwitcherWiresTheNewPanelThroughTheOneRenderer() {
        val ks = java.io.File("src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java").readText()
        assertTrue(ks.contains("show(mAutocompleteView, plan.shows(KeyboardViewState.Piece.AUTOCOMPLETE_VIEW))"))
        assertFalse("no direct visibility change", ks.contains("mAutocompleteView.setVisibility("))
    }
}
