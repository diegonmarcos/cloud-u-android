package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Save button of the filters screen, as plain logic so it can be executed rather than read —
 */
class FiltersSaveGateTest {

    @Test fun `nothing to push while the rules are running and unchanged`() {
        // The #34 rule, unchanged: an untouched screen must not offer a write.
        assertFalse(filtersSaveEnabled(saving = false, dirty = false, rulesNotRunning = false))
    }

    @Test fun `an edited rule may be pushed`() {
        assertTrue(filtersSaveEnabled(saving = false, dirty = true, rulesNotRunning = false))
    }

    @Test fun `stopped rules may be saved back even though nothing was edited`() {
        // THE POINT. This is the state the return from holiday leaves the account in: the screen
        // says the rules are not being applied, and Save is what re-activates the script. Greying
        // it out is telling the truth and locking the door.
        assertTrue(
            "Save must be reachable when the server holds the rules but is not running them — " +
                "it is the only gesture that puts them back",
            filtersSaveEnabled(saving = false, dirty = false, rulesNotRunning = true),
        )
    }

    @Test fun `saving over a script this app could not read asks first`() {
        // THE OVERWRITE. Save regenerates the whole `sterna` script from the list on screen, and
        // over an unreadable script that list is EMPTY because nothing could be read out of it: one
        // tap replaces a hand-written `fileinto` with nothing, on the server, with no way back.
        assertEquals(
            "an unreadable script must send Save through a confirmation, not straight to the write",
            FiltersSaveStep.CONFIRM_OVERWRITE,
            filtersSaveStep(scriptUnreadable = true),
        )
    }

    @Test fun `an ordinary save is not interrupted by a question`() {
        // The other half, and the reason this is a decision and not a habit: a confirmation on
        // every save is a confirmation nobody reads by the third one.
        assertEquals(FiltersSaveStep.WRITE, filtersSaveStep(scriptUnreadable = false))
    }

    @Test fun `the confirmation is a step added, never a door closed`() {
        // Measured: scriptUnreadable and rulesNotRunning cannot both be true (FilterScriptStatus
        // returns null on `enabledRuleCount == 0`, and an unreadable script yields no rule). This
        assertEquals(FiltersSaveStep.CONFIRM_OVERWRITE, filtersSaveStep(scriptUnreadable = true))
        assertTrue(
            "an unreadable script must not grey out Save — that would state the problem and lock " +
                "the door, which is the dead end #34 closed",
            filtersSaveEnabled(saving = false, dirty = false, rulesNotRunning = true),
        )
        assertTrue(filtersSaveEnabled(saving = false, dirty = true, rulesNotRunning = false))
    }

    @Test fun `never two writes at once`() {
        for (dirty in listOf(true, false)) {
            for (notRunning in listOf(true, false)) {
                assertFalse(
                    "a write in flight closes the button whatever else is true " +
                        "(dirty=$dirty, rulesNotRunning=$notRunning)",
                    filtersSaveEnabled(saving = true, dirty = dirty, rulesNotRunning = notRunning),
                )
            }
        }
    }
}
