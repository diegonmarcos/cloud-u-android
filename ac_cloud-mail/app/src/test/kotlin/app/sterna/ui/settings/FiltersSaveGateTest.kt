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
            filtersSaveStep(scriptUnreadable = true, foreignActive = false),
        )
    }

    @Test fun `an ordinary save is not interrupted by a question`() {
        // The other half, and the reason this is a decision and not a habit: a confirmation on
        // every save is a confirmation nobody reads by the third one.
        assertEquals(FiltersSaveStep.WRITE, filtersSaveStep(scriptUnreadable = false, foreignActive = false))
    }

    @Test fun `the confirmation is a step added, never a door closed`() {
        // Measured: scriptUnreadable and rulesNotRunning cannot both be true (FilterScriptStatus
        // returns null on `enabledRuleCount == 0`, and an unreadable script yields no rule). This
        assertEquals(FiltersSaveStep.CONFIRM_OVERWRITE, filtersSaveStep(scriptUnreadable = true, foreignActive = false))
        assertTrue(
            "an unreadable script must not grey out Save — that would state the problem and lock " +
                "the door, which is the dead end #34 closed",
            filtersSaveEnabled(saving = false, dirty = false, rulesNotRunning = true),
        )
        assertTrue(filtersSaveEnabled(saving = false, dirty = true, rulesNotRunning = false))
    }

    /**
     * **THE DEFECT #209 CLOSES, AND THE EXPENSIVE HALF OF IT.** The account is filtered by a
     * script this app did not write. This screen lists nothing, because none of that script is
     * expressible as rules. The owner adds their first rule and taps Save — and Save regenerates
     * the `sterna` script from a list holding that one rule and makes it the account's ACTIVE
     * script, which stops the one holding every filter they actually rely on.
     *
     * Before this, the whole warning was one sentence above the button that read like a notice.
     * A write that stops somebody else's working script is a decision; it gets a door.
     */
    @Test fun `saving over a foreign active script asks first`() {
        assertEquals(
            "a save that DEACTIVATES the script filtering this account must go through a " +
                "confirmation, not straight to the write",
            FiltersSaveStep.CONFIRM_TAKEOVER,
            filtersSaveStep(scriptUnreadable = false, foreignActive = true),
        )
    }

    /**
     * The two questions are different losses and must not be collapsed. An unreadable `sterna`
     * script has its CONTENT replaced; a foreign active script is switched OFF and kept. The
     * screen picks a different sentence and a different verb from each, so the gate that returned
     * one enum for both would put the wrong words on the wrong write.
     */
    @Test fun `the sharper loss is named when both are true`() {
        assertEquals(
            "our own unreadable script wins: it says content is REPLACED, which the takeover " +
                "sentence does not claim — and the rules read raises foreignActive for that same " +
                "script, so this pair is the ordinary case, not a corner",
            FiltersSaveStep.CONFIRM_OVERWRITE,
            filtersSaveStep(scriptUnreadable = true, foreignActive = true),
        )
    }

    /** The whole table at once: exactly one input pair may reach the write unasked. */
    @Test fun `only an account with nothing at stake is written without a question`() {
        for (unreadable in listOf(true, false)) {
            for (foreign in listOf(true, false)) {
                val step = filtersSaveStep(scriptUnreadable = unreadable, foreignActive = foreign)
                assertEquals(
                    "a straight write is allowed ONLY when neither flag is up " +
                        "(scriptUnreadable=$unreadable, foreignActive=$foreign)",
                    !unreadable && !foreign,
                    step == FiltersSaveStep.WRITE,
                )
            }
        }
    }

    /** The takeover confirmation is a step added, not a door closed: Save stays reachable. */
    @Test fun `a foreign script does not grey out Save`() {
        assertTrue(
            "greying it out would state the problem and lock the only gesture that resolves it, " +
                "which is the dead end #34 closed — the question is asked, then the write happens",
            filtersSaveEnabled(saving = false, dirty = true, rulesNotRunning = false),
        )
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
