// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.KeyboardViewState.Exit
import helium314.keyboard.keyboard.KeyboardViewState.Inputs
import helium314.keyboard.keyboard.KeyboardViewState.Mode
import helium314.keyboard.keyboard.KeyboardViewState.PanelKind
import helium314.keyboard.keyboard.KeyboardViewState.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #843 invariant: every non-typing state renders a visible, working exit - under every
 * combination of the settings the render depends on, including the locked ones.
 */
class KeyboardViewStateTest {

    private val allInputs: List<Inputs> = listOf(true, false).flatMap { strip ->
        listOf(true, false).flatMap { secondary ->
            listOf(true, false).flatMap { suppressed ->
                listOf(true, false).map { toolbarOnly -> Inputs(strip, secondary, suppressed, toolbarOnly) }
            }
        }
    }

    /** SettingsValues while the keyguard is locked: toolbar HIDDEN -> no strip, secondary strip hidden. */
    private val locked = Inputs(stripAvailable = false, secondaryStripVisible = false, imeSuppressed = false, showToolbarOnly = false)
    private val unlocked = Inputs(stripAvailable = true, secondaryStripVisible = true, imeSuppressed = false, showToolbarOnly = false)

    @Test
    fun everyPanelRendersAVisibleExitUnderEverySetting() {
        for (kind in PanelKind.entries) for (inputs in allInputs) for (suppressed in listOf(true, false)) {
            val plan = KeyboardViewState.plan(Mode.Panel(kind), suppressed, inputs)
            assertTrue("PANEL($kind) with $inputs has no exit: ${plan.visible}", plan.exits.isNotEmpty())
            assertTrue("PANEL($kind) with $inputs: bottom-row ABC hidden", Exit.BOTTOM_ROW_ALPHA in plan.exits)
            assertFalse("PANEL($kind) shows the typing keyboard", plan.shows(Piece.MAIN_KEYBOARD))
            assertFalse("PANEL($kind) shows the toolbar strip", plan.shows(Piece.SUGGESTION_STRIP))
        }
    }

    @Test
    fun clipboardAlwaysShowsItsCloseKey() {
        for (inputs in allInputs) {
            val plan = KeyboardViewState.plan(Mode.Panel(PanelKind.CLIPBOARD), false, inputs)
            assertTrue("clipboard with $inputs: close key not visible", Exit.PANEL_CLOSE_KEY in plan.exits)
            val keys = KeyboardViewState.clipboardStripKeys(listOf("A", "B"), inputs.secondaryStripVisible, "CLOSE")
            assertTrue("clipboard strip keys lack the close key: $keys", "CLOSE" in keys)
        }
        // a user layout that disabled CLOSE_HISTORY still gets it, once, last
        assertEquals(listOf("A", "B", "CLOSE"), KeyboardViewState.clipboardStripKeys(listOf("A", "CLOSE", "B"), true, "CLOSE"))
        // strip hidden (locked, or toolbar hiding global): the close key alone
        assertEquals(listOf("CLOSE"), KeyboardViewState.clipboardStripKeys(listOf("A", "B"), false, "CLOSE"))
    }

    @Test
    fun panelsAreMutuallyExclusiveAndTypingShowsNoPanel() {
        val emoji = KeyboardViewState.plan(Mode.Panel(PanelKind.EMOJI), false, unlocked)
        assertFalse(emoji.shows(Piece.CLIPBOARD_HISTORY)); assertFalse(emoji.shows(Piece.CLIPBOARD_STRIP))
        val clip = KeyboardViewState.plan(Mode.Panel(PanelKind.CLIPBOARD), false, unlocked)
        assertFalse(clip.shows(Piece.EMOJI_PALETTES)); assertFalse(clip.shows(Piece.EMOJI_TAB_STRIP))
        for (inputs in allInputs) {
            val typing = KeyboardViewState.plan(Mode.Typing, false, inputs)
            for (p in listOf(Piece.EMOJI_PALETTES, Piece.CLIPBOARD_HISTORY, Piece.EMOJI_TAB_STRIP, Piece.CLIPBOARD_STRIP))
                assertFalse("typing shows $p", typing.shows(p))
            assertTrue(typing.shows(Piece.MAIN_KEYBOARD))
            assertEquals(inputs.stripAvailable, typing.shows(Piece.SUGGESTION_STRIP))
        }
    }

    @Test
    fun toolbarKeyAndBackLeadBackToTyping() {
        for (kind in PanelKind.entries) {
            val s = KeyboardViewState()
            assertFalse(s.consumesBack())
            assertEquals(Mode.Panel(kind), s.toggleTarget(kind))
            s.openPanel(kind)
            assertTrue("back must be consumed in PANEL($kind)", s.consumesBack())
            assertEquals("the panel's own toolbar key closes it", Mode.Typing, s.toggleTarget(kind))
            s.toTyping(false)
            assertEquals(Mode.Typing, s.mode)
        }
    }

    /** The #843 report: clipboard open, config change while locked, unlock, clipboard again. */
    @Test
    fun lockedConfigChangeWhileClipboardOpenKeepsTheExit() {
        val s = KeyboardViewState()
        s.openPanel(PanelKind.CLIPBOARD)
        assertTrue(Exit.PANEL_CLOSE_KEY in s.plan(unlocked).exits)

        // night mode / rotation while locked: updateKeyboardTheme re-inflates the input view
        s.onInputViewRecreated()
        assertEquals("fresh views are the typing layout", Mode.Typing, s.mode)

        // the user opens the clipboard while still locked: the strip carries the close key alone
        s.openPanel(PanelKind.CLIPBOARD)
        val lockedPlan = s.plan(locked)
        assertTrue(Exit.PANEL_CLOSE_KEY in lockedPlan.exits)
        assertTrue(Exit.BOTTOM_ROW_ALPHA in lockedPlan.exits)
        assertEquals(listOf("CLOSE"), KeyboardViewState.clipboardStripKeys(listOf("A"), locked.secondaryStripVisible, "CLOSE"))

        // the #776 self-heal fires on onStartInputView after the unlock - with the panel open it
        // is deferred, never run underneath it, and handed back on the way out
        assertTrue(s.deferToolbarCheck("onStartInputView(restarting)"))
        assertTrue(Exit.PANEL_CLOSE_KEY in s.plan(unlocked).exits)
        assertEquals("onStartInputView(restarting)", s.toTyping(false))
        assertNull("handed back once", s.toTyping(false))
        assertFalse("typing runs the check at once", s.deferToolbarCheck("onStartInputView"))
    }
}
